package com.example.rankinglog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class RankingNet {
    private RankingNet() {}

    public static JsonObject postJson(String url, String jsonBody) throws Exception {
        URI uri = URI.create(url);
        HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();

        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setDoOutput(true);

        con.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));

        try (InputStreamReader reader = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    public static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
