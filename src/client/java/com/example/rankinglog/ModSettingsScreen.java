package com.example.rankinglog;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.Optional;

public class ModSettingsScreen extends Screen {

    private final Screen parent;

    public ModSettingsScreen(Screen parent) {
        super(Text.literal("MCRiderRanking 설정"));
        this.parent = parent;
    }

    private static String getModVersion(){
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("modid");
        return modContainer.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("1.6.99");
    }

    private void playUiClick() {
        if (this.client != null) {
            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        // UI 요소 감소에 따른 박스 높이 및 시작 좌표 조정
        int boxH = 220;
        int boxY = cy - boxH / 2;
        int startY = boxY + 45;
        int gap = 24;

        // 1. 자동 기록 등록 토글
        this.addDrawableChild(ButtonWidget.builder(getAutoSubmitText(), btn -> {
            playUiClick();
            ModConfig.get().autoSubmitEnabled = !ModConfig.get().autoSubmitEnabled;
            ModConfig.save();
            btn.setMessage(getAutoSubmitText());
        }).dimensions(cx - 100, startY, 200, 20).build());

        // 2. 로그 출력 토글
        this.addDrawableChild(ButtonWidget.builder(getDebugLogText(), btn -> {
            playUiClick();
            ModConfig.get().debugLogEnabled = !ModConfig.get().debugLogEnabled;
            ModConfig.save();
            btn.setMessage(getDebugLogText());
        }).dimensions(cx - 100, startY + gap, 200, 20).build());

        // 3. 배경 불투명도 조절 슬라이더
        double initialAlphaValue = ModConfig.get().backgroundAlpha / 255.0;
        this.addDrawableChild(new SliderWidget(cx - 100, startY + gap * 2, 200, 20, getBgAlphaText(), initialAlphaValue) {
            @Override
            protected void updateMessage() {
                this.setMessage(getBgAlphaText());
            }
            @Override
            protected void applyValue() {
                ModConfig.get().backgroundAlpha = (int) (this.value * 255);
                ModConfig.save();
            }
        });

        // 4. 캐시 유지 시간 설정 슬라이더 (30초 ~ 600초)
        double minTtl = 30.0;
        double maxTtl = 600.0;
        double currentTtl = ModConfig.get().cacheTtlSeconds;
        double initialTtlValue = (currentTtl - minTtl) / (maxTtl - minTtl);

        this.addDrawableChild(new SliderWidget(cx - 100, startY + gap * 3, 200, 20, getCacheTtlText(), initialTtlValue) {
            @Override
            protected void updateMessage() {
                this.setMessage(getCacheTtlText());
            }
            @Override
            protected void applyValue() {
                int seconds = (int) (minTtl + (this.value * (maxTtl - minTtl)));
                ModConfig.get().cacheTtlSeconds = seconds;
                ModConfig.save();
            }
        });

        // 5. 모든 캐시 수동 초기화 버튼
        this.addDrawableChild(ButtonWidget.builder(Text.literal("⚠ 모든 캐시 즉시 초기화"), btn -> {
            playUiClick();
            RankingScreen.ApiCache.clearCache();
            EventOptionSelectScreen.clearCache();
            EventRankingScreen.clearCache();

            if (this.client != null && this.client.player != null) {
                this.client.player.sendMessage(Text.literal("§e[System] 모든 랭킹 데이터 캐시가 초기화되었습니다."), false);
            }
        }).dimensions(cx - 100, startY + gap * 4, 200, 20).build());

        // 6. 닫기 버튼
        this.addDrawableChild(ButtonWidget.builder(Text.literal("닫기"), btn -> {
            playUiClick();
            this.close();
        }).dimensions(cx - 40, startY + gap * 5 + 5, 80, 20).build());
    }

    private Text getAutoSubmitText() {
        return Text.literal("자동 기록 등록: " + (ModConfig.get().autoSubmitEnabled ? "§aON" : "§cOFF"));
    }

    private Text getDebugLogText() {
        return Text.literal("로그 출력: " + (ModConfig.get().debugLogEnabled ? "§aON" : "§cOFF"));
    }

    private Text getBgAlphaText() {
        int percent = (int) Math.round((ModConfig.get().backgroundAlpha / 255.0) * 100);
        return Text.literal("배경 불투명도: " + percent + "%");
    }

    private Text getCacheTtlText() {
        int seconds = ModConfig.get().cacheTtlSeconds;
        String timeStr = seconds >= 60 ? (seconds / 60) + "분 " + (seconds % 60) + "초" : seconds + "초";
        return Text.literal("캐시 갱신 주기: §e" + timeStr);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int cy = this.height / 2;

        int boxW = 240;
        int boxH = 200; // 버튼 제거에 맞춰 배경 박스 높이 조정
        int boxX = cx - boxW / 2;
        int boxY = cy - boxH / 2 - 10;

        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xCC000000);
        drawRectBorder(context, boxX, boxY, boxW, boxH, 0xFF2A2A2A);

        context.drawCenteredTextWithShadow(this.textRenderer, "§6§l" + this.title.getString(), cx, boxY + 12, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, "§7V" + getModVersion(), cx, boxY + 24, 0xFFFFFF);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}