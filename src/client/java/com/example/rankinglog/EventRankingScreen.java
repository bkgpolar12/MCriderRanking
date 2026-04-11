package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class EventRankingScreen extends Screen {

    private static List<RankingEntry> cachedRanking = null;
    private static long cachedAt = 0;
    private static String cachedEventId = null;
    private static final long TTL_MS = 60_000;

    private boolean isCacheValid() {
        if (cachedRanking == null || cachedEventId == null || !cachedEventId.equals(eventInfo.eventID())) return false;
        return System.currentTimeMillis() - cachedAt <= TTL_MS;
    }

    private static final int PAGE_SIZE = 10;
    private static final int OUTER_PAD = 12;
    private static final int ROW_H = 18;

    private final Screen parent;
    private final EventOptionSelectScreen.EventEntry eventInfo;
    private final List<RankingEntry> ranking = new ArrayList<>();
    private int page = 0;
    private boolean loading = true;
    private String error = null;

    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;
    private ButtonWidget refreshBtn;
    private ButtonWidget joinBtn;
    private boolean checkingJoin = true;
    private boolean alreadyJoined = false;
    private String joinedEventID = null;

    public record RankingEntry(String player, String repTitle, String repColor, String time, String engineName, String bodyName, String modes, String tireName, long submittedAtMs) {}

    private static final class BodyHit {
        final int x1, y1, x2, y2; final String tireName;
        BodyHit(int x1, int y1, int x2, int y2, String tireName) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.tireName = tireName; }
        boolean hit(int mx, int my) { return mx >= x1 && mx <= x2 && my >= y1 && my <= y2; }
    }
    private final List<BodyHit> bodyHits = new ArrayList<>();

    public EventRankingScreen(Screen parent, EventOptionSelectScreen.EventEntry eventInfo) {
        super(Text.literal("이벤트 랭킹"));
        this.parent = parent; this.eventInfo = eventInfo;
    }

    private int parseHex(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try { if (hex.startsWith("#")) hex = hex.substring(1); return 0xFF000000 | Integer.parseInt(hex, 16); } catch (Exception e) { return fallback; }
    }

    @Override
    protected void init() {
        super.init();
        if (isCacheValid()) { ranking.clear(); ranking.addAll(cachedRanking); loading = false; } else { loading = true; fetchRanking(); }
        checkPlayerJoined();
        rebuildUI();
    }

    private void rebuildUI() {
        clearChildren();
        int cx = this.width / 2; int bottomY = this.height - 28;
        boolean isSmall = this.width < 420;
        int pageGap = isSmall ? 40 : 60;
        int iconBtnSize = 20;

        addDrawableChild(ButtonWidget.builder(Text.literal("🔍"), b -> { playUiClick(); if (this.client != null) this.client.setScreen(new RiderFindScreen(this)); })
                .dimensions(OUTER_PAD, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("라이더 찾기"))).build());

        prevBtn = addDrawableChild(ButtonWidget.builder(Text.literal("◀"), b -> { playUiClick(); if (page > 0) page--; }).dimensions(cx - pageGap - 10, bottomY, 20, 20).build());
        nextBtn = addDrawableChild(ButtonWidget.builder(Text.literal("▶"), b -> { playUiClick(); if ((page + 1) * PAGE_SIZE < ranking.size()) page++; }).dimensions(cx + pageGap - 10, bottomY, 20, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("⏴"), b -> { playUiClick(); if (this.client != null) this.client.setScreen(parent); })
                .dimensions(this.width - (iconBtnSize * 2) - OUTER_PAD - 5, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build());

        refreshBtn = addDrawableChild(ButtonWidget.builder(Text.literal("🔄"), b -> {
                    playUiClick(); loading = true; error = null; ranking.clear(); cachedRanking = null; cachedEventId = null; fetchRanking(); checkPlayerJoined(); })
                .dimensions(this.width - iconBtnSize - OUTER_PAD, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build());

        int joinBtnW = isSmall ? 50 : 60;
        joinBtn = addDrawableChild(ButtonWidget.builder(Text.literal("확인 중..."), b -> openJoinConfirm()).dimensions(this.width - OUTER_PAD - joinBtnW - 5, 25, joinBtnW, 20).build());
        updateJoinButtonState();
    }

    private void updateJoinButtonState() {
        if (joinBtn == null) return;
        if (checkingJoin) { joinBtn.setMessage(Text.literal("로딩중")); joinBtn.active = false; }
        else if (alreadyJoined) {
            String currentID = eventInfo.eventID(); boolean isIncluded = false;
            if (currentID != null && joinedEventID != null) {
                for (String id : joinedEventID.split(",")) { if (id.trim().equals(currentID)) { isIncluded = true; break; } }
            }
            joinBtn.setMessage(Text.literal(isIncluded ? "참여 완료" : "참여 불가")); joinBtn.active = false;
        } else { joinBtn.setMessage(Text.literal("참여")); joinBtn.active = true; }
    }

    private void playUiClick() { if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f)); }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!loading && error == null && !ranking.isEmpty()) {
            int startY = 10 + 50 + 10 + 24; int start = page * PAGE_SIZE; int end = Math.min(start + PAGE_SIZE, ranking.size());
            for (int i = start; i < end; i++) {
                RankingEntry e = ranking.get(i); int y = startY + (i - start) * ROW_H;
                int playerX = OUTER_PAD + 8 + (int)((this.width - (OUTER_PAD + 8) * 2) * 0.18);
                String displayPlayer = e.player(); if (e.repTitle() != null && !e.repTitle().isEmpty()) displayPlayer += " [" + e.repTitle() + "]";
                int playerW = this.textRenderer.getWidth(displayPlayer);
                if (mouseX >= playerX && mouseX <= playerX + playerW && mouseY >= y - 2 && mouseY <= y + 13) {
                    playUiClick(); if (this.client != null) this.client.setScreen(new PlayerProfileScreen(e.player(), this)); return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void checkPlayerJoined() {
        checkingJoin = true; updateJoinButtonState();
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject(); req.addProperty("p_player", MinecraftClient.getInstance().getSession().getUsername());
                // ===== [수정] Supabase RPC 호출로 변경 =====
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "check_event_join", req.toString());
                if (obj.has("joined")) {
                    alreadyJoined = obj.get("joined").getAsBoolean();
                    joinedEventID = (alreadyJoined && obj.has("eventID")) ? obj.get("eventID").getAsString() : null;
                }
            } catch (Exception e) { e.printStackTrace(); } finally { checkingJoin = false; if (this.client != null) this.client.execute(this::updateJoinButtonState); }
        }).start();
    }

    private void openJoinConfirm() {
        if (this.client == null) return;
        this.client.setScreen(new ConfirmScreen(confirmed -> { if (confirmed) joinEvent(); client.setScreen(this); },
                Text.literal("주의!"), Text.literal("정말로 이 이벤트를 참여하시겠습니까?\n이벤트가 종료되기 전 까지 이벤트를 변경할 수 없습니다."), Text.literal("참여합니다"), Text.literal("다시 생각해볼게요")));
    }

    private void joinEvent() {
        checkingJoin = true; updateJoinButtonState();
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_player", MinecraftClient.getInstance().getSession().getUsername());
                req.addProperty("p_event_id", eventInfo.eventID());
                // ===== [수정] Supabase RPC 호출로 변경 =====
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "join_event", req.toString());
                if (obj.get("ok").getAsBoolean()) { alreadyJoined = true; joinedEventID = eventInfo.eventID(); }
            } catch (Exception e) { e.printStackTrace(); } finally { checkingJoin = false; if (this.client != null) this.client.execute(this::updateJoinButtonState); }
        }).start();
    }

    private void fetchRanking() {
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject(); req.addProperty("p_event_id", eventInfo.eventID());
                // ===== [수정] Supabase RPC 호출로 변경 =====
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_event_ranking", req.toString());
                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    JsonArray arr = obj.getAsJsonArray("ranking"); ranking.clear();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        ranking.add(new RankingEntry(
                                o.get("player").getAsString(), o.has("repTitle") ? o.get("repTitle").getAsString() : "",
                                o.has("repColor") ? o.get("repColor").getAsString() : "#55FFFF", o.get("time").getAsString(),
                                o.get("engineName").getAsString(), o.get("bodyName").getAsString(), o.get("modes").getAsString(),
                                o.get("tireName").getAsString(), o.has("submittedAtMs") ? o.get("submittedAtMs").getAsLong() : 0L
                        ));
                    }
                    cachedRanking = new ArrayList<>(ranking); cachedAt = System.currentTimeMillis(); cachedEventId = eventInfo.eventID();
                }
            } catch (Exception e) { error = e.getMessage(); } finally { loading = false; }
        }).start();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int cx = this.width / 2; int headerY = 10; int headerH = 50;

        context.fill(OUTER_PAD, headerY, this.width - OUTER_PAD, headerY + headerH, 0xCC000000);
        drawRectBorder(context, OUTER_PAD, headerY, this.width - OUTER_PAD * 2, headerH, 0xFF2A2A2A);
        context.drawCenteredTextWithShadow(textRenderer, "TRACK: " + eventInfo.track(), cx, headerY + 8, 0xAAAAAA);
        context.drawCenteredTextWithShadow(textRenderer, "§6§l" + eventInfo.name(), cx, headerY + 20, 0xFFDDAA);
        context.drawCenteredTextWithShadow(textRenderer, "§7기간: " + eventInfo.startDate() + " ~ " + eventInfo.endDate(), cx, headerY + 34, 0xBBBBBB);

        int tableTop = headerY + headerH + 10; int tableBottom = this.height - 46; int tableH = Math.max(80, tableBottom - tableTop);
        int tableX = OUTER_PAD + 8; int tableW = this.width - (OUTER_PAD + 8) * 2;

        int rankX = tableX + (int)(tableW * 0.02); int playerXx = tableX + (int)(tableW * 0.15);
        int timeX = tableX + (int)(tableW * 0.40); int bodyXx = tableX + (int)(tableW * 0.60); int engineX = tableX + (int)(tableW * 0.85);

        boolean showTime = tableW > 250; boolean showBody = tableW > 320; boolean showEngine = tableW > 380;

        context.fill(tableX, tableTop, tableX + tableW, tableTop + tableH, 0x66000000);
        drawRectBorder(context, tableX, tableTop, tableW, tableH, 0xFF222222);

        int headRowY = tableTop + 8;
        context.drawTextWithShadow(textRenderer, "순위", rankX, headRowY, 0xDDDDDD);
        context.drawTextWithShadow(textRenderer, "플레이어", playerXx, headRowY, 0xDDDDDD);
        if (showTime) context.drawTextWithShadow(textRenderer, "기록", timeX, headRowY, 0xDDDDDD);
        if (showBody) context.drawTextWithShadow(textRenderer, "카트바디", bodyXx, headRowY, 0xDDDDDD);
        if (showEngine) context.drawTextWithShadow(textRenderer, "엔진", engineX, headRowY, 0xDDDDDD);

        if (loading) context.drawCenteredTextWithShadow(textRenderer, "데이터를 불러오는 중...", cx, tableTop + 40, 0xFFFFFF);
        else if (error != null) context.drawCenteredTextWithShadow(textRenderer, "오류: " + error, cx, tableTop + 40, 0xFF5555);
        else if (ranking.isEmpty()) context.drawCenteredTextWithShadow(textRenderer, "등록된 기록이 없습니다.", cx, tableTop + 40, 0xAAAAAA);
        else {
            int startY = tableTop + 24; int start = page * PAGE_SIZE; int end = Math.min(start + PAGE_SIZE, ranking.size());
            RankingEntry hoveredEntry = null; String hoveredTireTooltip = null; bodyHits.clear();

            for (int i = start; i < end; i++) {
                RankingEntry r = ranking.get(i); int idx = i - start; int y = startY + idx * ROW_H; int rank = i + 1;

                if (rank == 1) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + ROW_H - 2, 0x44FFD700);
                else if (rank == 2) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + ROW_H - 2, 0x44C0C0C0);
                else if (rank == 3) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + ROW_H - 2, 0x44CD7F32);
                else if ((idx & 1) == 1) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + ROW_H - 2, 0x22000000);

                int rankColor = (rank == 1) ? 0xFFFFE066 : (rank == 2) ? 0xFFE6E6E6 : (rank == 3) ? 0xFFFFB36B : 0xFFFFFFFF;
                context.drawTextWithShadow(textRenderer, rank + "위", rankX, y, rankColor);

                String displayPlayer = r.player(); String repText = "";
                if (r.repTitle() != null && !r.repTitle().isEmpty()) { repText = " [" + r.repTitle() + "]"; displayPlayer += repText; }
                int pW = textRenderer.getWidth(displayPlayer); boolean hoverPlayer = mouseX >= playerXx && mouseX <= playerXx + pW && mouseY >= y - 2 && mouseY <= y + 13;

                if (repText.isEmpty()) { context.drawTextWithShadow(textRenderer, r.player(), playerXx, y, hoverPlayer ? 0xFFFFEE88 : 0xFFFFFF); }
                else {
                    int pColor = hoverPlayer ? 0xFFFFEE88 : 0xFFFFFF; int rColor = hoverPlayer ? 0xFFFFEE88 : parseHex(r.repColor(), 0x55FFFF);
                    context.drawTextWithShadow(textRenderer, r.player(), playerXx, y, pColor);
                    context.drawTextWithShadow(textRenderer, repText, playerXx + textRenderer.getWidth(r.player()), y, rColor);
                }
                if (hoverPlayer) hoveredEntry = r;

                boolean hiddenSomething = false;
                if (showTime) context.drawTextWithShadow(textRenderer, r.time, timeX, y, 0xFFFFFF); else hiddenSomething = true;
                if (showBody) {
                    String bodyLabel = TireUtil.composeBodyLabel(r.bodyName, r.tireName);
                    int bodyW = textRenderer.getWidth(bodyLabel);
                    BodyHit bh = new BodyHit(bodyXx, y - 2, bodyXx + bodyW, y + 10, r.tireName); bodyHits.add(bh);
                    if (bh.hit(mouseX, mouseY)) hoveredTireTooltip = TireUtil.tooltipName(r.tireName);
                    context.drawTextWithShadow(textRenderer, bodyLabel, bodyXx, y, 0xFFFFFF);
                } else hiddenSomething = true;
                if (showEngine) context.drawTextWithShadow(textRenderer, r.engineName.toUpperCase(), engineX, y, 0xFFFFFF); else hiddenSomething = true;
                if (hiddenSomething) context.drawTextWithShadow(textRenderer, "+", tableX + tableW - 16, y, 0xAAAAAA);
            }
            if (hoveredTireTooltip != null) drawTooltipBox(context, mouseX, mouseY, hoveredTireTooltip);
            else if (hoveredEntry != null) drawTooltipBox(context, mouseX, mouseY, "클릭: 프로필 보기 | 등록: " + formatDateTime(hoveredEntry.submittedAtMs));
        }

        int totalPages = Math.max(1, (ranking.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        context.drawCenteredTextWithShadow(textRenderer, String.format("페이지 %d / %d", (page + 1), totalPages), cx, this.height - 26, 0xAAAAAA);
        if (prevBtn != null) prevBtn.active = page > 0;
        if (nextBtn != null) nextBtn.active = (page + 1) * PAGE_SIZE < ranking.size();

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) { context.fill(0, 0, this.width, this.height, 0xAA000000); }

    private void drawTooltipBox(DrawContext context, int mouseX, int mouseY, String text) {
        int tw = this.textRenderer.getWidth(text); int pad = 4; int x = mouseX + 10; int y = mouseY + 10;
        context.fill(x, y, x + tw + pad * 2, y + 11 + pad, 0xEE000000);
        drawRectBorder(context, x, y, tw + pad * 2, 11 + pad, 0xFF777777);
        context.drawTextWithShadow(this.textRenderer, text, x + pad, y + pad, 0xFFFFFF);
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color); context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color); context.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static String formatDateTime(long ms) {
        if (ms <= 0) return "정보 없음"; return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(ms));
    }
}