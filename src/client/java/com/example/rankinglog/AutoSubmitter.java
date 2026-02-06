package com.example.rankinglog;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class AutoSubmitter {

    // 너무 자주 보내는 것 방지(중복 감지/서버 렉/두 번 트리거 대비)
    private static final AtomicLong lastSubmitAt = new AtomicLong(0);

    // 최소 간격(ms) - 필요하면 조절
    private static final long MIN_INTERVAL_MS = 1500;

    /**
     * render()에서 호출해도 안전하게 만들기 위해:
     * - 네트워크는 IO 스레드에서
     * - 채팅 출력/상태 변경은 메인에서
     */
    public static void submitAsync(String player, String track, String timeStr, long timeMillis,
                                   int engine, String engineName, String bodyName)
 {
        long now = System.currentTimeMillis();
        long prev = lastSubmitAt.get();
        if (now - prev < MIN_INTERVAL_MS) return;
        lastSubmitAt.set(now);

        // IO 스레드에서 전송
        CompletableFuture
                .supplyAsync(() -> {
                    // 네트워크는 무조건 여기서만
                    // submitRecord가 JsonObject를 반환하도록 만들어둔 버전이면 그걸 사용
                    // 지금 너 AddRankingScreen 코드에는 submitRecord가 JsonObject 반환 버전이 있었지.
                    // 없으면 AddRankingScreen.submitRecord를 JsonObject 반환형으로 바꿔줘.
                    return AddRankingScreen.submitRecord(player, track, timeStr, timeMillis, engineName, bodyName);
                }, Util.getIoWorkerExecutor())
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    JsonObject fail = new JsonObject();
                    fail.addProperty("ok", false);
                    fail.addProperty("error", "network exception");
                    return fail;
                })
                .thenAccept(res -> {
                    // 메인 스레드에서 채팅 출력
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> {
                        if (client.player == null) return;

                        boolean ok = res != null && res.has("ok") && res.get("ok").getAsBoolean();
                        if (ok) {
                            client.player.sendMessage(
                                    Text.literal("§a등록 성공: " + track + " / " + timeStr + " / " + engineName),
                                    false
                            );
                        } else {
                            String err = (res != null && res.has("error")) ? res.get("error").getAsString() : "unknown";
                            if ("not better".equals(err)) {
                                client.player.sendMessage(
                                        Text.literal("등록 실패 : 새 기록이 기존 기록과 같거나 더 느립니다"),
                                        false
                                );
                            } else {
                                client.player.sendMessage(
                                        Text.literal("등록 실패 : " + err),
                                        false
                                );
                            }
                        }
                    });
                });
    }
}
