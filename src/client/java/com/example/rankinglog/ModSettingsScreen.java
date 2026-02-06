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
        }).dimensions(cx - 100, cy - 10, 200, 20).build();

        this.addDrawableChild(autoSubmitBtn);

        // 닫기 버튼
        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("닫기"), btn -> this.close())
                        .dimensions(cx - 40, cy + 20, 80, 20)
                        .build()
        );
    }

    private Text getAutoSubmitText() {
        boolean on = ModConfig.get().autoSubmitEnabled;
        return Text.literal("자동 기록 등록: " + (on ? "§aON" : "§cOFF"));
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 배경 그리기 (1.21.x는 DrawContext 기반)
        this.renderBackground(context, mouseX, mouseY, delta);

        // 제목
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                this.title,
                this.width / 2,
                20,
                0xFFFFFF
        );

        // 설명
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("버전 : " + getModVersion()),
                this.width / 2,
                40,
                0xAAAAAA
        );

        // 버튼/위젯 렌더
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
