package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class MainMenuScreen extends Screen {

    private static long cachedAt = 0;
    private static final long TTL_MS = 5 * 60_000;

    private static boolean isCacheValid() {
        if (cachedNotices.isEmpty()) return false;
        return System.currentTimeMillis() - cachedAt <= TTL_MS;
    }

    private static boolean loaded = false;
    private static boolean loading = false;
    private static final List<Notice> cachedNotices = new ArrayList<>();

    private static int activeEventCount = -1;
    private static long eventCountCachedAt = 0;
    private static final long EVENT_TTL_MS = 60_000;

    private static class Notice {
        String title; String desc;
        List<OrderedText> wrappedDesc = new ArrayList<>();
        int totalContentHeight = 0;
    }

    private int page = 0;
    private double scrollAmount = 0;
    private int boxX, boxY, boxW, boxH, pageButtonY, funcButtonY, startX, currentBtnSize;
    private float currentTitleScale, currentIconScale;

    public MainMenuScreen() { super(Text.literal("맠라랭 시스템")); }

    private static String getModVersion() {
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("modid");
        return modContainer.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    @Override
    protected void init() {
        int cx = width / 2;
        String playerName = this.client != null ? this.client.getSession().getUsername() : "Player";
        int profileBtnWidth = 24 + this.textRenderer.getWidth(playerName);

        addDrawableChild(ButtonWidget.builder(Text.literal("👤 " + playerName), b -> {
            Objects.requireNonNull(client).setScreen(new PlayerProfileScreen(playerName, this));
        }).dimensions(10, 10, profileBtnWidth, 20).tooltip(Tooltip.of(Text.literal("내 프로필 보기"))).build());

        boolean isSmallScreen = width < 400;
        currentBtnSize = isSmallScreen ? 30 : 45; currentTitleScale = isSmallScreen ? 1.5f : 2.0f;
        currentIconScale = isSmallScreen ? 1.5f : 2.5f; int gap = isSmallScreen ? 8 : 15;

        boxW = Math.min(360, width - 40); boxH = Math.min(140, height / 3); boxX = cx - boxW / 2;
        int totalUiHeight = (int) (40 + boxH + 30 + currentBtnSize + 20);
        int uiTop = Math.max(10, (height - totalUiHeight) / 2);

        boxY = uiTop + 40; pageButtonY = boxY + boxH + 6; funcButtonY = pageButtonY + 30;

        addDrawableChild(ButtonWidget.builder(Text.literal("◀"), b -> { if (page > 0) { page--; scrollAmount = 0; } }).dimensions(cx - 55, pageButtonY, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("▶"), b -> { if (page < cachedNotices.size() - 1) { page++; scrollAmount = 0; } }).dimensions(cx + 35, pageButtonY, 20, 20).build());

        int totalButtonsWidth = (currentBtnSize * 3) + (gap * 2); startX = cx - (totalButtonsWidth / 2);

        addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
            String track = TrackNameUtil.readTrackNameFromLobbyBox();
            String finalTrack = (track == null || track.isBlank()) ? ModConfig.get().defaultTrack : track.trim();
            Objects.requireNonNull(client).setScreen(new RankingScreen(finalTrack));
        }).dimensions(startX, funcButtonY, currentBtnSize, currentBtnSize).tooltip(Tooltip.of(Text.literal("랭킹 보기"))).build());

        addDrawableChild(ButtonWidget.builder(Text.empty(), b -> { Objects.requireNonNull(client).setScreen(new EventOptionSelectScreen(this)); })
                .dimensions(startX + currentBtnSize + gap, funcButtonY, currentBtnSize, currentBtnSize).tooltip(Tooltip.of(Text.literal("이벤트 랭킹 보기"))).build());

        addDrawableChild(ButtonWidget.builder(Text.empty(), b -> { Objects.requireNonNull(client).setScreen(new ModSettingsScreen(this)); })
                .dimensions(startX + (currentBtnSize + gap) * 2, funcButtonY, currentBtnSize, currentBtnSize).tooltip(Tooltip.of(Text.literal("설정"))).build());

        addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
            loaded = false;
            activeEventCount = -1;
            fetchNotices();
            fetchEventCount();
        }).dimensions(width - currentBtnSize - 10, height - currentBtnSize - 10, currentBtnSize, currentBtnSize).tooltip(Tooltip.of(Text.literal("새로고침"))).build());

        if (isCacheValid()) { loaded = true; loading = false; } else { if (!loading) fetchNotices(); }

        if (activeEventCount == -1 || System.currentTimeMillis() - eventCountCachedAt > EVENT_TTL_MS) {
            fetchEventCount();
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        int cx = width / 2; int uiTop = boxY - 40;

        context.getMatrices().push(); context.getMatrices().scale(currentTitleScale, currentTitleScale, 1.0f);
        context.drawCenteredTextWithShadow(textRenderer, "§6§lMCRiderRanking", (int)(cx / currentTitleScale), (int)((uiTop + 10) / currentTitleScale), 0xFFFFFF);
        context.getMatrices().pop();
        context.drawCenteredTextWithShadow(textRenderer, "§7V" + getModVersion(), cx, uiTop + 30, 0xFFFFFF);

        context.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + boxH + 1, 0xFF555555);
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xDD000000);

        if (loading) {
            context.drawCenteredTextWithShadow(textRenderer, "공지 불러오는 중...", cx, boxY + boxH / 2 - 4, 0xAAAAAA);
        } else if (!cachedNotices.isEmpty()) {
            Notice n = cachedNotices.get(page); context.enableScissor(boxX, boxY, boxX + boxW, boxY + boxH);
            int currentY = (int) (boxY + 10 - scrollAmount);
            context.drawTextWithShadow(textRenderer, "§e§l" + n.title, boxX + 10, currentY, 0xFFFFFF); currentY += 15;
            context.fill(boxX + 10, currentY, boxX + boxW - 10, currentY + 1, 0x33FFFFFF); currentY += 10;
            for (OrderedText line : n.wrappedDesc) { context.drawTextWithShadow(textRenderer, line, boxX + 10, currentY, 0xFFFFFF); currentY += 12; }
            context.disableScissor();
            context.drawCenteredTextWithShadow(textRenderer, (page + 1) + " / " + cachedNotices.size(), cx, pageButtonY + 6, 0xAAAAAA);
        }

        int gap = width < 400 ? 8 : 15;
        renderBigIcon(context, "🏆", startX, funcButtonY, currentBtnSize);
        renderBigIcon(context, "📅", startX + currentBtnSize + gap, funcButtonY, currentBtnSize);
        renderBigIcon(context, "⚙", startX + (currentBtnSize + gap) * 2, funcButtonY, currentBtnSize);
        renderBigIcon(context, "🔄", width - currentBtnSize - 10, height - currentBtnSize - 10, currentBtnSize);

        // ===== [수정] 얇고 깔끔한 흰색 테두리 배지 렌더링 =====
        if (activeEventCount > 0) {
            String countText = String.valueOf(activeEventCount);
            int textW = textRenderer.getWidth(countText);

            // 배지 크기 설정
            int badgePadding = 4;
            int badgeSize = Math.max(14, textW + badgePadding * 2);

            // 이벤트 버튼의 우측 상단 좌표 계산
            int eventBtnRight = startX + currentBtnSize + gap + currentBtnSize;
            int eventBtnTop = funcButtonY;

            // 배지 위치 조정
            int bx1 = eventBtnRight - (badgeSize / 2) - 2;
            int by1 = eventBtnTop - (badgeSize / 2) + 2;
            int bx2 = bx1 + badgeSize;
            int by2 = by1 + badgeSize;

            // 1. 빨간색 배경
            context.fill(bx1, by1, bx2, by2, 0xFFFF0000);

            // 2. 얇은 흰색 테두리 (검은색 테두리 제거로 두께 최소화)
            context.drawBorder(bx1, by1, badgeSize, badgeSize, 0xFFFFFFFF);

            // 3. 중앙 숫자 렌더링
            int textX = bx1 + (badgeSize - textW) / 2 + 1;
            int textY = by1 + (badgeSize - 8) / 2 + 1;
            context.drawText(textRenderer, countText, textX, textY, 0xFFFFFFFF, false);
        }
    }

    private void renderBigIcon(DrawContext context, String icon, int x, int y, int size) {
        context.getMatrices().push();
        float centerX = x + (size / 2.0f); float centerY = y + (size / 2.0f); float fontHeightHalf = (textRenderer.fontHeight * currentIconScale) / 2.0f;
        context.getMatrices().translate(centerX, centerY - fontHeightHalf + (currentIconScale * 0.4f), 0);
        context.getMatrices().scale(currentIconScale, currentIconScale, 1.0f);
        context.drawCenteredTextWithShadow(textRenderer, icon, 0, 0, 0xFFFFFF);
        context.getMatrices().pop();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= boxX && mouseX <= boxX + boxW && mouseY >= boxY && mouseY <= boxY + boxH) {
            if (!cachedNotices.isEmpty()) {
                Notice n = cachedNotices.get(page);
                int maxScroll = Math.max(0, n.totalContentHeight - boxH);
                scrollAmount = Math.max(0, Math.min(maxScroll, scrollAmount - verticalAmount * 12));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void fetchNotices() {
        loading = true;
        new Thread(() -> {
            try {
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_notices", "{}");

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    cachedNotices.clear();
                    JsonArray arr = obj.getAsJsonArray("notices");
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject n = arr.get(i).getAsJsonObject();
                        Notice notice = new Notice();
                        notice.title = n.get("title").getAsString();
                        notice.desc = n.get("desc").getAsString();
                        notice.wrappedDesc = textRenderer.wrapLines(Text.literal(notice.desc), boxW - 20);
                        notice.totalContentHeight = 15 + 10 + (notice.wrappedDesc.size() * 12) + 20;
                        cachedNotices.add(notice);
                    }
                    cachedAt = System.currentTimeMillis();
                    MinecraftClient.getInstance().execute(() -> { page = 0; scrollAmount = 0; loaded = true; loading = false; });
                }
            } catch (Exception e) { e.printStackTrace(); loading = false; }
        }).start();
    }

    private void fetchEventCount() {
        new Thread(() -> {
            try {
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_event_list", "{}");

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    JsonArray arr = obj.getAsJsonArray("events");
                    MinecraftClient client = MinecraftClient.getInstance();
                    String playerName = (client.player != null) ? client.player.getName().getString() : "";

                    boolean isDev = Objects.equals("BKGpolar1", playerName);
                    int count = 0;

                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        String visibleValue = o.has("visible") ? o.get("visible").getAsString().trim() : "";
                        boolean shouldShow = true;

                        if ("false-dev".equalsIgnoreCase(visibleValue)) { if (isDev) shouldShow = false; }
                        else if ("true-dev".equalsIgnoreCase(visibleValue)) { if (!isDev) shouldShow = false; }

                        if (shouldShow) count++;
                    }
                    activeEventCount = count;
                    eventCountCachedAt = System.currentTimeMillis();
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }
}