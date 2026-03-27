package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    private static class Notice {
        String title;
        String desc;
        List<OrderedText> wrappedDesc = new ArrayList<>();
        int totalContentHeight = 0;
    }

    private int page = 0;
    private double scrollAmount = 0;

    // 가변 좌표 및 크기 변수
    private int boxX, boxY, boxW, boxH;
    private int pageButtonY, funcButtonY, startX;
    private int currentBtnSize;
    private float currentTitleScale;
    private float currentIconScale;

    public MainMenuScreen() {
        super(Text.literal("맠라랭 시스템"));
    }

    private static String getModVersion() {
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("modid");
        return modContainer.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    @Override
    protected void init() {
        int cx = width / 2;

        // 1. 화면 크기에 따른 유동적 스케일 설정
        // 너비가 400보다 작으면 버튼과 폰트 크기를 줄임
        boolean isSmallScreen = width < 400;
        currentBtnSize = isSmallScreen ? 30 : 45;
        currentTitleScale = isSmallScreen ? 1.5f : 2.0f;
        currentIconScale = isSmallScreen ? 1.5f : 2.5f;
        int gap = isSmallScreen ? 8 : 15;

        // 2. 박스 크기 결정
        boxW = Math.min(360, width - 40);
        boxH = Math.min(140, height / 3); // 높이가 너무 낮을 경우 대응
        boxX = cx - boxW / 2;

        // 3. 전체 UI 수직 배치 계산
        int totalUiHeight = (int) (40 + boxH + 30 + currentBtnSize + 20);
        int uiTop = Math.max(10, (height - totalUiHeight) / 2);

        boxY = uiTop + 40;
        pageButtonY = boxY + boxH + 6;
        funcButtonY = pageButtonY + 30;

        // --- 공지사항 페이지 컨트롤 ---
        addDrawableChild(ButtonWidget.builder(Text.literal("◀"), b -> {
            if (page > 0) { page--; scrollAmount = 0; }
        }).dimensions(cx - 55, pageButtonY, 20, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("▶"), b -> {
            if (page < cachedNotices.size() - 1) { page++; scrollAmount = 0; }
        }).dimensions(cx + 35, pageButtonY, 20, 20).build());

        // --- 기능 버튼 배치 ---
        int totalButtonsWidth = (currentBtnSize * 3) + (gap * 2);
        startX = cx - (totalButtonsWidth / 2);

        addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
            String track = TrackNameUtil.readTrackNameFromLobbyBox();
            String finalTrack = (track == null || track.isBlank()) ? "[α] 빌리지 고가의 질주" : track.trim();
            Objects.requireNonNull(client).setScreen(new RankingScreen(finalTrack));
        }).dimensions(startX, funcButtonY, currentBtnSize, currentBtnSize).tooltip(Tooltip.of(Text.literal("랭킹 보기"))).build());

        addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
            Objects.requireNonNull(client).setScreen(new EventOptionSelectScreen(this));
        }).dimensions(startX + currentBtnSize + gap, funcButtonY, currentBtnSize, currentBtnSize).tooltip(Tooltip.of(Text.literal("이벤트 랭킹 보기"))).build());

        addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
            Objects.requireNonNull(client).setScreen(new ModSettingsScreen(this));
        }).dimensions(startX + (currentBtnSize + gap) * 2, funcButtonY, currentBtnSize, currentBtnSize).tooltip(Tooltip.of(Text.literal("설정"))).build());

        // --- 새로고침 버튼 (우측 하단 고정, 크기 조절)
        addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
            loaded = false;
            fetchNotices();
        }).dimensions(width - currentBtnSize - 10, height - currentBtnSize - 10, currentBtnSize, currentBtnSize).tooltip(Tooltip.of(Text.literal("새로고침"))).build());

        if (isCacheValid()) {
            loaded = true;
            loading = false;
        } else {
            if (!loading) fetchNotices();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = width / 2;
        int uiTop = boxY - 40;

        // 메인 제목
        context.getMatrices().push();
        context.getMatrices().scale(currentTitleScale, currentTitleScale, 1.0f);
        context.drawCenteredTextWithShadow(textRenderer, "§6§lMCRiderRanking", (int)(cx / currentTitleScale), (int)((uiTop + 10) / currentTitleScale), 0xFFFFFF);
        context.getMatrices().pop();

        context.drawCenteredTextWithShadow(textRenderer, "§7V" + getModVersion(), cx, uiTop + 30, 0xFFFFFF);

        // 공지 박스 배경
        context.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + boxH + 1, 0xFF555555);
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xDD000000);

        if (loading) {
            context.drawCenteredTextWithShadow(textRenderer, "공지 불러오는 중...", cx, boxY + boxH / 2 - 4, 0xAAAAAA);
        } else if (!cachedNotices.isEmpty()) {
            Notice n = cachedNotices.get(page);
            context.enableScissor(boxX, boxY, boxX + boxW, boxY + boxH);

            int currentY = (int) (boxY + 10 - scrollAmount);
            context.drawTextWithShadow(textRenderer, "§e§l" + n.title, boxX + 10, currentY, 0xFFFFFF);
            currentY += 15;
            context.fill(boxX + 10, currentY, boxX + boxW - 10, currentY + 1, 0x33FFFFFF);
            currentY += 10;

            for (OrderedText line : n.wrappedDesc) {
                context.drawTextWithShadow(textRenderer, line, boxX + 10, currentY, 0xFFFFFF);
                currentY += 12;
            }
            context.disableScissor();

            context.drawCenteredTextWithShadow(textRenderer, (page + 1) + " / " + cachedNotices.size(), cx, pageButtonY + 6, 0xAAAAAA);
        }

        // 아이콘 렌더링 (현재 크기에 맞춰 간격 재계산)
        int gap = width < 400 ? 8 : 15;
        renderBigIcon(context, "🏆", startX, funcButtonY, currentBtnSize);
        renderBigIcon(context, "📅", startX + currentBtnSize + gap, funcButtonY, currentBtnSize);
        renderBigIcon(context, "⚙", startX + (currentBtnSize + gap) * 2, funcButtonY, currentBtnSize);
        renderBigIcon(context, "🔄", width - currentBtnSize - 10, height - currentBtnSize - 10, currentBtnSize);
    }

    private void renderBigIcon(DrawContext context, String icon, int x, int y, int size) {
        context.getMatrices().push();
        float centerX = x + (size / 2.0f);
        float centerY = y + (size / 2.0f);
        float fontHeightHalf = (textRenderer.fontHeight * currentIconScale) / 2.0f;
        // 보정값을 scale에 맞춰 조절 (1.0f * scale)
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
                URI uri = URI.create(RankingScreen.GAS_URL);
                HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.getOutputStream().write("{\"action\":\"getNotice\"}".getBytes(StandardCharsets.UTF_8));

                InputStream is = con.getInputStream();
                String res = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(res).getAsJsonObject();

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

                    MinecraftClient.getInstance().execute(() -> {
                        page = 0;
                        scrollAmount = 0;
                        loaded = true;
                        loading = false;
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                loading = false;
            }
        }).start();
    }
}