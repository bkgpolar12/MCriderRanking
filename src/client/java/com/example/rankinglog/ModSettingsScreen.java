package com.example.rankinglog;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

public class ModSettingsScreen extends Screen {

    private final Screen parent;

    private ButtonWidget autoSubmitBtn;
    private ButtonWidget debugLogBtn;

    public ModSettingsScreen(Screen parent) {
        super(Text.literal("MCRiderRanking 설정"));
        this.parent = parent;
    }

    private static String getModVersion(){
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("modid");
        return modContainer.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("1.6.99");
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        // 자동 등록 토글 버튼
        autoSubmitBtn = ButtonWidget.builder(getAutoSubmitText(), btn -> {
            ModConfig cfg = ModConfig.get();
            cfg.autoSubmitEnabled = !cfg.autoSubmitEnabled;
            ModConfig.save();
            btn.setMessage(getAutoSubmitText());
        }).dimensions(cx - 100, cy - 25, 200, 20).build();
        this.addDrawableChild(autoSubmitBtn);

        //로그 출력 토글 버튼
        debugLogBtn = ButtonWidget.builder(getDebugLogText(), btn -> {
            ModConfig cfg = ModConfig.get();
            cfg.debugLogEnabled = !cfg.debugLogEnabled;
            ModConfig.save();
            btn.setMessage(getDebugLogText());

            // 켰을 때 한 번 확인 메시지(이것도 설정값에 따름이 자연스러우니 직접 출력)
            if (this.client != null && this.client.player != null && cfg.debugLogEnabled) {
                this.client.player.sendMessage(Text.literal("§a[Debug] 로그 출력 ON"), false);
            }
        }).dimensions(cx - 100, cy - 0, 200, 20).build();
        this.addDrawableChild(debugLogBtn);

        // 닫기 버튼
        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("닫기"), btn -> this.close())
                        .dimensions(cx - 40, cy + 30, 80, 20)
                        .build()
        );
    }

    private Text getAutoSubmitText() {
        boolean on = ModConfig.get().autoSubmitEnabled;
        return Text.literal("자동 기록 등록: " + (on ? "§aON" : "§cOFF"));
    }

    private Text getDebugLogText() {
        boolean on = ModConfig.get().debugLogEnabled;
        return Text.literal("로그 출력: " + (on ? "§aON" : "§cOFF"));
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(
                this.textRenderer,
                this.title,
                this.width / 2,
                20,
                0xFFFFFF
        );

        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("버전 : " + getModVersion()),
                this.width / 2,
                40,
                0xAAAAAA
        );

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
