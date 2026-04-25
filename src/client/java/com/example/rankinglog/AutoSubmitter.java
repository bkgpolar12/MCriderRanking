package com.example.rankinglog;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.HoverEvent; // ★ 반드시 이 경로(net.minecraft.text)여야 합니다!
import net.minecraft.util.Formatting;
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

        String bodyColor = BodyCaptureManager.getCachedKartColorOrHex();

        CompletableFuture
                .supplyAsync(() -> {
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

                        if (res != null && res.has("achievementUnlocked") && res.get("achievementUnlocked").getAsBoolean()) {
                            client.player.sendMessage(
                                    Text.literal("§e[MCRiderRanking] 트랙 업적 달성! [")
                                            .append(Text.keybind("key.rankinglog.open_ranking").formatted(Formatting.YELLOW))
                                            .append(Text.literal("§e]키 - 프로필을 들어가서 확인할 수 있습니다")),
                                    false
                            );
                        }

                        boolean ok = res != null && res.has("ok") && res.get("ok").getAsBoolean();
                        if (ok) {
                            Text hoverText = Text.literal("§b[ 등록된 기록 정보 ]\n")
                                    .append("§7트랙: §f" + track + "\n")
                                    .append("§7기록: §f" + timeStr + "\n")
                                    .append("§7카트: §f" + bodyName + "\n")
                                    .append("§7엔진: §f" + engineName);

                            Text message = Text.literal("§a[MCRiderRanking] 등록 성공! [")
                                    // 설정된 키를 동적으로 가져옵니다. (강조를 위해 노란색을 입혀주는 센스!)
                                    .append(Text.keybind("key.rankinglog.open_ranking").formatted(Formatting.YELLOW))
                                    .append(Text.literal("§a]키 - 랭킹 보기를 눌러서 확인할 수 있습니다 "))
                                    .append(
                                            Text.literal("§e§n(마우스를 올려 요약 확인)")
                                                    .styled(style -> style
                                                            .withHoverEvent(new HoverEvent.ShowText(hoverText))
                                                    )
                                    );

                            client.player.sendMessage(message, false);
                        } else {
                            String err = (res != null && res.has("error")) ? res.get("error").getAsString() : "unknown";

                            if ("not better".equals(err)) {
                                client.player.sendMessage(
                                        Text.literal("[MCRiderRanking] 등록 실패 : 새 기록이 기존 기록과 같거나 더 느립니다"),
                                        false
                                );
                            } else if ("BAD JSON RESPONSE".equalsIgnoreCase(err)) {
                                client.player.sendMessage(
                                        Text.literal("[MCRiderRanking] 등록 실패 : 응답이 유효한 JSON이 아닙니다. 모드의 최신 버전을 확인하세요."),
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