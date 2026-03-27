package com.example.rankinglog;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class AutoSubmitter {

    private static final AtomicLong lastSubmitAt = new AtomicLong(0);
    private static final long MIN_INTERVAL_MS = 1500;

    public static void submitAsync(String player, String track, String timeStr, long timeMillis,
                                   int engine, String engineName, String bodyName,
                                   String tireName,
                                   String modesCsv) {

        long now = System.currentTimeMillis();
        long prev = lastSubmitAt.get();
        if (now - prev < MIN_INTERVAL_MS) return;
        lastSubmitAt.set(now);

        // ✅ 캐싱된 카트 색상을 가져옵니다.
        String bodyColor = BodyCaptureManager.getCachedKartColorOrHex();

        CompletableFuture
                .supplyAsync(() -> {
                    // ✅ AddRankingScreen.submitRecord에 bodyColor 매개변수 추가
                    return AddRankingScreen.submitRecord(player, track, timeStr, timeMillis, engineName, bodyName, bodyColor, tireName, modesCsv);
                }, Util.getIoWorkerExecutor())
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    JsonObject fail = new JsonObject();
                    fail.addProperty("ok", false);
                    fail.addProperty("error", "network exception");
                    return fail;
                })
                .thenAccept(res -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> {
                        if (client.player == null) return;

                        boolean ok = res != null && res.has("ok") && res.get("ok").getAsBoolean();
                        if (ok) {
                            client.player.sendMessage(
                                    Text.literal("§a[MCRiderRanking] 등록 성공: " + track + " / " + timeStr + " / " + bodyName + " / " + engineName),
                                    false
                            );
                        } else {
                            String err = (res != null && res.has("error")) ? res.get("error").getAsString() : "unknown";

                            if ("not better".equals(err)) {
                                client.player.sendMessage(
                                        Text.literal("[MCRiderRanking] 등록 실패 : 새 기록이 기존 기록과 같거나 더 느립니다"),
                                        false
                                );
                            } else if ("BAD JSON RESPONSE".equalsIgnoreCase(err)) {
                                client.player.sendMessage(
                                        Text.literal("[MCRiderRanking] 등록 실패 : 응답이 유효한 JSON 응답이 아닙니다. 설치된 MCRiderRanking 모드의 버전을 확인하고 최신 버전을 이용해주세요."),
                                        false
                                );
                            } else {
                                client.player.sendMessage(
                                        Text.literal("[MCRiderRanking] 등록 실패 : " + err),
                                        false
                                );
                            }
                        }
                    });
                });
    }
}