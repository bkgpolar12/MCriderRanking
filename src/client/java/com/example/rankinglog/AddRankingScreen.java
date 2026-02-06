package com.example.rankinglog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class AddRankingScreen extends Screen {

    private TextFieldWidget playerField;
    private TextFieldWidget trackField;
    private TextFieldWidget timeField;

    private final String player;
    private final String track;
    private final String time;

    private long lastSubmitTime = 0;

    // 타임아웃(필요하면 값 조절)
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

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

        playerField = new TextFieldWidget(
                textRenderer, cx - 100, cy - 50, 200, 20, Text.literal("플레이어")
        );
        playerField.setText(player);

        trackField = new TextFieldWidget(
                textRenderer, cx - 100, cy - 20, 200, 20, Text.literal("트랙")
        );
        trackField.setText(track);

        timeField = new TextFieldWidget(
                textRenderer, cx - 100, cy + 10, 200, 20, Text.literal("기록")
        );
        timeField.setText(time);

        addDrawableChild(playerField);
        addDrawableChild(trackField);
        addDrawableChild(timeField);

        addDrawableChild(
                ButtonWidget.builder(Text.literal("전송"), btn -> {

                    long now = System.currentTimeMillis();
                    if (now - lastSubmitTime < 5000) return; // 5초 쿨타임

                    String player = playerField.getText();
                    String track = trackField.getText();
                    String timeStr = timeField.getText();

                    long newTimeMillis = parseTimeToMillis(timeStr);
                    if (newTimeMillis < 0) {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            client.player.sendMessage(
                                    Text.literal("등록 실패 : 기록 형식이 올바르지 않습니다"),
                                    false
                            );
                        }
                        return;
                    }

                    btn.active = false;
                    lastSubmitTime = now;

                    // 네트워크 작업은 반드시 백그라운드 스레드에서
                    new Thread(() -> {
                        try {
                            // 예시: 엔진 이름(너 프로젝트에서 실제 값으로 교체 가능)
                            String engineName = "[X]";
                            String bodyName = "테스트";
                            int engine = 0;

                            // 1) best 기록 확인 (선택)
                            Long best = checkBestRecord(player, track, engineName);

                            if (best != null && newTimeMillis >= best) {
                                MinecraftClient.getInstance().execute(() -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    if (client.player != null) {
                                        client.player.sendMessage(
                                                Text.literal("등록 실패 : 새 기록이 기존 기록과 같거나 더 느립니다"),
                                                false
                                        );
                                    }
                                    btn.active = true;
                                });
                                return;
                            }

                            // 2) 등록
                            JsonObject submitRes = submitRecord(player, track, timeStr, newTimeMillis, engineName, bodyName);

                            boolean ok = submitRes.has("ok") && submitRes.get("ok").getAsBoolean();
                            if (!ok) {
                                String err = submitRes.has("error") ? submitRes.get("error").getAsString() : "unknown";
                                MinecraftClient.getInstance().execute(() -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    if (client.player != null) {

                                        client.player.sendMessage(

                                                Text.literal("등록 실패 : " + err),
                                                false
                                        );
                                    }
                                    btn.active = true;
                                });
                                return;
                            }

                            // 성공: 화면 닫기(메인 스레드에서)
                            MinecraftClient.getInstance().execute(this::close);

                        } catch (Exception e) {
                            e.printStackTrace();
                            MinecraftClient.getInstance().execute(() -> {
                                MinecraftClient client = MinecraftClient.getInstance();
                                if (client.player != null) {
                                    client.player.sendMessage(
                                            Text.literal("등록 실패 : 네트워크 오류"),
                                            false
                                    );
                                }
                                btn.active = true;
                            });
                            return;
                        }

                        // 쿨타임 끝나면 버튼 다시 활성화(메인 스레드)
                        MinecraftClient.getInstance().execute(() -> btn.active = true);

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
     * 타임아웃 + 스트림 안전 종료 + errorStream 처리 포함
     */
    private static JsonObject postToGas(String jsonBody) throws Exception {
        return postToGasInternal(jsonBody, 0);
    }

    private static JsonObject postToGasInternal(String jsonBody, int depth) throws Exception {
        // 무한 리다이렉트 방지
        if (depth > 3) {
            JsonObject fail = new JsonObject();
            fail.addProperty("ok", false);
            fail.addProperty("error", "too many redirects");
            return fail;
        }

        URI uri = URI.create(RankingScreen.GAS_URL);
        HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();

        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("User-Agent", "MinecraftRankingMod/1.0");
        con.setUseCaches(false);
        con.setDoOutput(true);

        con.setConnectTimeout(CONNECT_TIMEOUT_MS);
        con.setReadTimeout(READ_TIMEOUT_MS);

        // 중요: 리다이렉트는 우리가 직접 처리
        con.setInstanceFollowRedirects(false);

        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        con.setFixedLengthStreamingMode(body.length);

        try (OutputStream os = con.getOutputStream()) {
            os.write(body);
            os.flush();
        }

        int code = con.getResponseCode();

        // 302/301 등 리다이렉트면 Location 따라가기
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String location = con.getHeaderField("Location");
            con.disconnect();

            if (location == null || location.isBlank()) {
                JsonObject fail = new JsonObject();
                fail.addProperty("ok", false);
                fail.addProperty("error", "redirect without location");
                fail.addProperty("httpCode", code);
                return fail;
            }

            // Apps Script는 보통 script.googleusercontent.com 으로 보내줌
            // 여기서는 실행은 이미 되었고, 응답(JSON)만 받으면 되니까 GET으로 받아도 안전
            return fetchRedirectAsGet(location, depth + 1);
        }

        // 일반 응답 읽기 (성공/실패 모두)
        String response = readAnyBody(con, code);
        con.disconnect();

        return parseJsonOrFallback(response, code);
    }

    private static JsonObject fetchRedirectAsGet(String url, int depth) throws Exception {
        if (depth > 3) {
            JsonObject fail = new JsonObject();
            fail.addProperty("ok", false);
            fail.addProperty("error", "too many redirects");
            return fail;
        }

        URI uri = URI.create(url);
        HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();

        con.setRequestMethod("GET");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("User-Agent", "MinecraftRankingMod/1.0");

        con.setConnectTimeout(CONNECT_TIMEOUT_MS);
        con.setReadTimeout(READ_TIMEOUT_MS);

        con.setInstanceFollowRedirects(false);

        int code = con.getResponseCode();

        // 리다이렉트가 또 오면 한 번 더 따라가기
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String location = con.getHeaderField("Location");
            con.disconnect();

            if (location == null || location.isBlank()) {
                JsonObject fail = new JsonObject();
                fail.addProperty("ok", false);
                fail.addProperty("error", "redirect without location");
                fail.addProperty("httpCode", code);
                return fail;
            }

            return fetchRedirectAsGet(location, depth + 1);
        }

        String response = readAnyBody(con, code);
        con.disconnect();

        return parseJsonOrFallback(response, code);
    }

    private static String readAnyBody(HttpURLConnection con, int code) {
        try {
            InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
            if (is == null) return "";
            try (InputStream in = is) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static JsonObject parseJsonOrFallback(String response, int httpCode) {
        String trimmed = (response == null) ? "" : response.trim();

        // 바디가 비어있으면: 2xx면 ok=true 처리
        if (trimmed.isEmpty()) {
            JsonObject fb = new JsonObject();
            fb.addProperty("ok", httpCode >= 200 && httpCode < 300);
            fb.addProperty("error", httpCode >= 200 && httpCode < 300 ? "" : ("http " + httpCode));
            fb.addProperty("httpCode", httpCode);
            fb.addProperty("raw", "");
            return fb;
        }

        try {
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            if (!obj.has("ok")) obj.addProperty("ok", httpCode >= 200 && httpCode < 300);
            if (!obj.has("httpCode")) obj.addProperty("httpCode", httpCode);
            return obj;
        } catch (Exception e) {
            JsonObject fb = new JsonObject();
            fb.addProperty("ok", httpCode >= 200 && httpCode < 300);
            fb.addProperty("error", httpCode >= 200 && httpCode < 300 ? "" : "bad json response");
            fb.addProperty("httpCode", httpCode);
            fb.addProperty("raw", trimmed);
            return fb;
        }
    }




    public static Long checkBestRecord(String player, String track, String engineName) {
        try {
            // 너 코드에서 여기 JSON이 깨져있던 부분(따옴표/줄바꿈) 고침
            String json = String.format("""
            {
              "action": "check",
              "player": "%s",
              "track": "%s",
              "engine": %d,
              "engineName": "%s"
            }
            """, player, track, 0, engineName);

            JsonObject res = postToGas(json);

            if (!res.has("ok") || !res.get("ok").getAsBoolean()) return null;
            if (!res.has("bestTime") || res.get("bestTime").isJsonNull()) return null;

            return res.get("bestTime").getAsLong();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static JsonObject submitRecord(
            String player,
            String track,
            String timeStr,
            long timeMillis,
            String engineName,
            String bodyName
    ) {
        String json = String.format("""
{
  "action": "submit",
  "player": "%s",
  "track": "%s",
  "time": "%s",
  "timeMillis": %d,
  "engine": %d,
  "engineName": "%s",
  "bodyName": "%s"
}
""",
                player, track, timeStr, timeMillis, 0,
                engineName, bodyName
        );


        try {
            // 정상적으로 JSON 응답을 받으면 그대로 반환
            return postToGas(json);

        } catch (Exception e) {
            // 여기서 핵심: 등록은 되었을 수 있으니 "확정 실패"로 단정하지 않기
            e.printStackTrace();

            JsonObject maybe = new JsonObject();
            maybe.addProperty("ok", true);               // 임시 성공 처리
            maybe.addProperty("suspected", true);        // 응답 처리 실패 표시
            maybe.addProperty("note", "response read failed, but request may have succeeded");
            maybe.addProperty("message", e.toString());
            return maybe;
        }
    }


}
