package com.example.rankinglog;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class AddRankingScreen extends Screen {

    private TextFieldWidget playerField;
    private TextFieldWidget trackField;
    private TextFieldWidget timeField;

    private final String player;
    private final String track;
    private final String time;

    private long lastSubmitTime = 0;

    public AddRankingScreen(String player, String track, String time) {
        super(Text.literal("기록 추가"));
        this.player = player;
        this.track = track;
        this.time = time;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        playerField = new TextFieldWidget(textRenderer, cx - 100, cy - 50, 200, 20, Text.literal("플레이어"));
        playerField.setText(player);

        trackField = new TextFieldWidget(textRenderer, cx - 100, cy - 20, 200, 20, Text.literal("트랙"));
        trackField.setText(track);

        timeField = new TextFieldWidget(textRenderer, cx - 100, cy + 10, 200, 20, Text.literal("기록"));
        timeField.setText(time);

        addDrawableChild(playerField);
        addDrawableChild(trackField);
        addDrawableChild(timeField);

        addDrawableChild(
                ButtonWidget.builder(Text.literal("전송"), btn -> {

                    long now = System.currentTimeMillis();
                    if (now - lastSubmitTime < 5000) return; // 5초 쿨타임

                    String p = playerField.getText();
                    String tr = trackField.getText();
                    String t = timeField.getText();

                    long newTimeMillis = parseTimeToMillis(t);
                    if (newTimeMillis < 0) {
                        if (client != null && client.player != null) {
                            client.player.sendMessage(Text.literal("등록 실패 : 기록 형식이 올바르지 않습니다"), false);
                        }
                        return;
                    }

                    btn.active = false;
                    lastSubmitTime = now;

                    // 네트워크 작업은 반드시 백그라운드 스레드에서
                    new Thread(() -> {
                        try {
                            String engineName = "[X]"; // AutoSubmitter에서 올바른 값을 넘겨줌
                            String bodyName = BodyCaptureManager.getCachedKartBodyNameOrUnknown();
                            String bodyColor = BodyCaptureManager.getCachedKartColorOrHex();
                            String modesCsv = "없음";
                            String tireName = "레이싱 타이어";

                            // 1) Supabase에 전송!
                            JsonObject submitRes = submitRecord(p, tr, t, newTimeMillis, engineName, bodyName, bodyColor, tireName, modesCsv);

                            boolean ok = submitRes.has("ok") && submitRes.get("ok").getAsBoolean();
                            if (!ok) {
                                String err = submitRes.has("error") ? submitRes.get("error").getAsString() : "unknown error";
                                MinecraftClient.getInstance().execute(() -> {
                                    if (client != null && client.player != null) {
                                        client.player.sendMessage(Text.literal("등록 실패 : " + err), false);
                                    }
                                    btn.active = true;
                                });
                                return;
                            }

                            // 2) 성공: 화면 닫기(메인 스레드에서)
                            MinecraftClient.getInstance().execute(this::close);

                        } catch (Exception e) {
                            e.printStackTrace();
                            MinecraftClient.getInstance().execute(() -> {
                                if (client != null && client.player != null) {
                                    client.player.sendMessage(Text.literal("등록 실패 : 통신 오류"), false);
                                }
                                btn.active = true;
                            });
                        }
                    }, "ranking-submit-thread").start();

                }).dimensions(cx - 40, cy + 40, 80, 20).build()
        );

        setInitialFocus(playerField);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public static long parseTimeToMillis(String time) {


        try {


            String[] minSplit = time.split(":");


            int minutes = Integer.parseInt(minSplit[0]);







            String[] secSplit = minSplit[1].split("\\.");



            int seconds = Integer.parseInt(secSplit[0]);



            int millis = Integer.parseInt(secSplit[1]);







            return minutes * 60_000L + seconds * 1_000L + millis;



        } catch (Exception e) {



            return -1;



        }



    }

    /**
     * 완벽하게 Supabase 형식(p_변수명)으로 맞춰진 기록 제출 함수
     */
    public static JsonObject submitRecord(String player, String track, String timeStr, long timeMillis,
                                          String engineName, String bodyName, String bodyColor, String tireName, String modesCsv) {

        JsonObject json = new JsonObject();
        json.addProperty("p_player", player);
        json.addProperty("p_track", track);
        json.addProperty("p_time_millis", timeMillis);
        json.addProperty("p_time_str", timeStr);
        json.addProperty("p_engine_name", engineName);
        json.addProperty("p_body_name", bodyName);
        json.addProperty("p_body_color", bodyColor);
        json.addProperty("p_mode", modesCsv);
        json.addProperty("p_tire", tireName);

        try {
            java.net.URI uri = java.net.URI.create(RankingScreen.SUPABASE_RPC_URL + "submit_racing_record_v3");
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) uri.toURL().openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("apikey", RankingScreen.SUPABASE_KEY);
            con.setRequestProperty("Authorization", "Bearer " + RankingScreen.SUPABASE_KEY);
            con.setDoOutput(true);

            // 데이터 전송
            byte[] input = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            con.getOutputStream().write(input);

            // 응답 코드 확인
            int code = con.getResponseCode();

            // 💡 핵심: 200번대(성공)가 아니면 에러 스트림을 강제로 열어서 읽습니다!
            java.io.InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();

            if (is == null) {
                JsonObject fail = new JsonObject();
                fail.addProperty("ok", false);
                fail.addProperty("error", "HTTP " + code + " (응답 없음)");
                return fail;
            }

            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)) {
                com.google.gson.JsonElement el = com.google.gson.JsonParser.parseReader(reader);

                // 데이터베이스가 거절한 경우 (400, 404, 500 등)
                if (code >= 400) {
                    JsonObject fail = new JsonObject();
                    fail.addProperty("ok", false);

                    // Supabase가 보내준 진짜 에러 메시지 추출
                    String errorMsg = el.toString();
                    if (el.isJsonObject() && el.getAsJsonObject().has("message")) {
                        errorMsg = el.getAsJsonObject().get("message").getAsString();
                    }

                    // 마인크래프트 채팅창에 띄울 최종 에러
                    fail.addProperty("error", "DB 거절(" + code + "): " + errorMsg);
                    return fail;
                }

                return el.getAsJsonObject();
            }
        } catch (Exception e) {
            JsonObject fail = new JsonObject();
            fail.addProperty("ok", false);
            fail.addProperty("error", "자바 에러: " + e.getMessage());
            return fail;
        }
    }
}