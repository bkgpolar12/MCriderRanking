// RiderFindScreen.java
package com.example.rankinglog;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.*;

public class RiderFindScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget input;
    private final List<String> allPlayers = new ArrayList<>();
    private final List<String> filtered = new ArrayList<>();
    private final Map<String, String> playerRepMap = new HashMap<>();

    private int page = 0, rowsPerPage = 10;
    private boolean loading = false;
    private String error = null;
    private ButtonWidget backBtn, prevBtn, nextBtn, refreshBtn;
    private static final int OUTER_PAD = 12;

    public RiderFindScreen(Screen parent) { super(Text.literal("라이더 찾기")); this.parent = parent; }

    @Override public void close() { if (this.client != null) this.client.setScreen(parent); }

    @Override
    protected void init() {
        boolean isSmall = this.width < 420; int cx = this.width / 2; int bottomY = this.height - 28;
        int iconBtnSize = 20; int pageBtnGap = isSmall ? 40 : 60;

        backBtn = ButtonWidget.builder(Text.literal("⏴"), b -> { playUiClick(); this.close(); })
                .dimensions(OUTER_PAD, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build(); addDrawableChild(backBtn);

        prevBtn = ButtonWidget.builder(Text.literal("◀"), b -> { if (page > 0) page--; updateButtons(); }).dimensions(cx - pageBtnGap - 10, bottomY, 20, 20).build(); addDrawableChild(prevBtn);
        nextBtn = ButtonWidget.builder(Text.literal("▶"), b -> { if ((page + 1) * rowsPerPage < filtered.size()) page++; updateButtons(); }).dimensions(cx + pageBtnGap - 10, bottomY, 20, 20).build(); addDrawableChild(nextBtn);

        refreshBtn = ButtonWidget.builder(Text.literal("🔄"), b -> {
            loading = true; error = null; updateButtons();
            RankingScreen.ApiCache.fetchAllAsync(true, p -> { loading = false; error = null; rebuildPlayerList(); page = 0; applyFilter(); updateButtons(); }, err -> { loading = false; error = err; updateButtons(); });
        }).dimensions(this.width - iconBtnSize - OUTER_PAD, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build(); addDrawableChild(refreshBtn);

        input = new TextFieldWidget(this.textRenderer, cx - 150, 42, 300, 20, Text.literal(""));
        input.setMaxLength(32); input.setChangedListener(s -> { page = 0; applyFilter(); updateButtons(); });
        addSelectableChild(input); setInitialFocus(input);

        rebuildPlayerList(); page = 0; applyFilter();
    }

    private void playUiClick() { if (this.client != null && this.client.getSoundManager() != null) this.client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0F)); }
    private void computeRowsPerPage() { rowsPerPage = Math.min(14, Math.max(1, Math.max(1, this.height - 46 - 80 - 18 - 18) / 18)); }

    private void rebuildPlayerList() {
        allPlayers.clear(); playerRepMap.clear();
        RankingScreen.ApiCache.AllPayload all = RankingScreen.ApiCache.getAllIfReady(); if (all == null) return;
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (var it : all.rankingsByTrack.entrySet()) {
            for (RankingScreen.Entry e : it.getValue().ranking) {
                String name = (e.player() == null) ? "" : e.player().trim();
                if (!name.isBlank()) { uniq.add(name); if (e.repTitle() != null && !e.repTitle().isEmpty()) playerRepMap.put(name, e.repTitle()); }
            }
        }
        allPlayers.addAll(uniq); allPlayers.sort(String.CASE_INSENSITIVE_ORDER);
    }

    private void applyFilter() {
        computeRowsPerPage(); filtered.clear(); String q = (input == null) ? "" : input.getText().trim().toLowerCase(Locale.ROOT);
        for (String p : allPlayers) { if (q.isEmpty() || p.toLowerCase(Locale.ROOT).contains(q)) filtered.add(p); }
        int maxPage = Math.max(0, (filtered.size() - 1) / rowsPerPage); if (page > maxPage) page = maxPage; if (page < 0) page = 0;
    }

    private void updateButtons() { computeRowsPerPage(); if (prevBtn != null) prevBtn.active = (!loading && error == null && page > 0); if (nextBtn != null) nextBtn.active = (!loading && error == null && (page + 1) * rowsPerPage < filtered.size()); }

    @Override protected void applyBlur() { }
    @Override public void blur() { }
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }
    @Override public void resize(net.minecraft.client.MinecraftClient client, int width, int height) { super.resize(client, width, height); applyFilter(); updateButtons(); }
    @Override public boolean charTyped(char chr, int modifiers) { if (input != null && input.charTyped(chr, modifiers)) return true; return super.charTyped(chr, modifiers); }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) { if (input != null && input.keyPressed(keyCode, scanCode, modifiers)) return true; return super.keyPressed(keyCode, scanCode, modifiers); }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (input != null && input.mouseClicked(mouseX, mouseY, button)) return true;
        if (!loading && error == null && !filtered.isEmpty()) {
            int tableX = OUTER_PAD + 8; int tableY = 80; int tableW = this.width - (OUTER_PAD + 8) * 2; int tableH = this.height - 46 - tableY;
            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableY && mouseY <= tableY + tableH) {
                int start = page * rowsPerPage; int end = Math.min(start + rowsPerPage, filtered.size()); int y = tableY + 24; int rowH = 18;
                for (int i = start; i < end; i++) {
                    String name = filtered.get(i); String repTitle = playerRepMap.getOrDefault(name, ""); String displayName = name;
                    if (!repTitle.isEmpty()) displayName += " §b[" + repTitle + "]§r";
                    int nameX = tableX + 16; int nameY = y; int nameW = this.textRenderer.getWidth(displayName);
                    if (mouseX >= nameX && mouseX <= nameX + nameW && mouseY >= nameY - 2 && mouseY <= nameY + 10) {
                        if (this.client != null) this.client.setScreen(new PlayerProfileScreen(name, this)); return true;
                    }
                    y += rowH;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, "라이더 찾기", width / 2, 18, 0xFFFFFF); context.drawCenteredTextWithShadow(textRenderer, "닉네임을 입력하세요", width / 2, 30, 0xBBBBBB);
        if (input != null) input.render(context, mouseX, mouseY, delta);

        int tableX = OUTER_PAD + 8; int tableY = 80; int tableW = this.width - (OUTER_PAD + 8) * 2; int tableH = this.height - 46 - tableY;
        context.fill(tableX, tableY, tableX + tableW, tableY + tableH, 0x66000000); drawRectBorder(context, tableX, tableY, tableW, tableH, 0xFF222222);

        if (loading) { context.drawCenteredTextWithShadow(textRenderer, "불러오는 중...", width / 2, tableY + 26, 0xFFFFFF); super.render(context, mouseX, mouseY, delta); return; }
        if (error != null) { String displayError = error.toLowerCase().contains("http") ? "이 버전은 서비스 종료 되었습니다. 최신 버전을 이용해 주세요." : error; context.drawCenteredTextWithShadow(this.textRenderer, "오류: " + displayError, this.width / 2, tableY + 26, 0xFF5555); super.render(context, mouseX, mouseY, delta); return; }

        context.drawTextWithShadow(textRenderer, "닉네임 (클릭하면 프로필)", tableX + 16, tableY + 8, 0xDDDDDD);
        int start = page * rowsPerPage; int end = Math.min(start + rowsPerPage, filtered.size()); int y = tableY + 24; int rowH = 18;

        for (int i = start; i < end; i++) {
            String name = filtered.get(i); String repTitle = playerRepMap.getOrDefault(name, ""); String displayName = name;
            if (!repTitle.isEmpty()) displayName += " §b[" + repTitle + "]§r";
            boolean hover = mouseX >= tableX + 16 && mouseX <= tableX + 16 + textRenderer.getWidth(displayName) && mouseY >= y - 2 && mouseY <= y + 10;
            context.drawTextWithShadow(textRenderer, displayName, tableX + 16, y, hover ? 0xFFFFEE88 : 0xFFFFFF); y += rowH;
        }

        int totalPages = Math.max(1, (filtered.size() + rowsPerPage - 1) / rowsPerPage);
        context.drawCenteredTextWithShadow(textRenderer, String.format("페이지 %d / %d", (page + 1), totalPages), width / 2, height - 26, 0xAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) { context.fill(x, y, x + w, y + 1, color); context.fill(x, y + h - 1, x + w, y + h, color); context.fill(x, y, x + 1, y + h, color); context.fill(x + w - 1, y, x + w, y + h, color); }
    @Override public boolean shouldPause() { return false; }
}
