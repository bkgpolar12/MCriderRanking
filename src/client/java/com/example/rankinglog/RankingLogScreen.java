package com.example.rankinglog;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class RankingLogScreen extends Screen {

    private TextFieldWidget textField;

    public RankingLogScreen() {
        super(Text.literal("Log 입력"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        textField = new TextFieldWidget(
                this.textRenderer,
                centerX - 100,
                centerY - 20,
                200,
                20,
                Text.literal("내용 입력")
        );

        this.addDrawableChild(textField);
        this.setInitialFocus(textField);

        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("전송"), button -> {
                    String msg = textField.getText();
                    sendToGoogleSheet(msg);
                    this.close();
                }).dimensions(centerX - 40, centerY + 10, 80, 20).build()
        );
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static void sendToGoogleSheet(String message) {
        try {
            URI uri = URI.create("https://script.google.com/macros/s/AKfycbxu3Jv0t0D5UNpCOLvu9IrqipqBZNC4B2IPw4rtNiJz7SsY9ZyAWwhqE7-dUZNX-yg/exec");
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
            e.printStackTrace();
        }
    }
}
