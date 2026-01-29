package com.example.rankinglog;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RankingLogMod implements ClientModInitializer {

    public static final Logger LOGGER =
            LoggerFactory.getLogger("clientlog");

    private static void handleLog(String message) {
        sendToGoogleSheet(message);
    }

    @Override
    public void onInitializeClient() {



        ClientSendMessageEvents.MODIFY_CHAT.register(message -> {
            if (message.startsWith("/log ")) {
                String actual = message.substring(5);

                handleLog(actual);

                MinecraftClient.getInstance().player.sendMessage(
                        Text.literal("로그 전송됨"),
                        false
                );

                return ""; // ← 서버로 채팅 안 보냄
            }
            return message;
        });
    }

    private static void sendToGoogleSheet(String message) {
        try {
            URI uri = URI.create("https://script.google.com/macros/s/AKfycbyqAWJQp6o4n1GjjsMczlvRlus4uAXOYNW-r9IcRdWmS4FGLuB0Rn-XpZ4i1uxTU3k/exec");
            HttpURLConnection con =
                    (HttpURLConnection) uri.toURL().openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            String json = String.format("""
        {
          "player": "%s",
          "message": "%s"
        }
        """,
                    MinecraftClient.getInstance().getSession().getUsername(),
                    message
            );

            con.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            con.getInputStream().close();

        } catch (Exception e) {
            LOGGER.error("Google Sheet로 로그 전송 실패", e);
        }
    }


}
