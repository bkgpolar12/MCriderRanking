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

    private boolean isCacheValid() {
        if (cachedRanking == null || cachedEventId == null || !cachedEventId.equals(eventInfo.eventID())) return false;
        return System.currentTimeMillis() - cachedAt <= ModConfig.get().getCacheTtlMs();
    }

    public static void clearCache() {
        cachedRanking = null;
        cachedAt = 0;
        cachedEventId = null;
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
    private ButtonWidget backBtn;
    private ButtonWidget searchBtn;

    private boolean checkingJoin = true;
    private boolean alreadyJoined = false;
    private String joinedEventID = null;

    // ===== 상세 정보창 관련 변수 =====
    private RankingEntry selectedDetailEntry = null;
    private String selectedProfileDesc = "불러오는 중...";
    private ButtonWidget detailCloseBtn;
    private ButtonWidget detailProfileBtn;

    public record RankingEntry(String player, String repTitle, String repColor, String time, String engineName, String bodyName, String modes, String tireName, long submittedAtMs) {}

    public EventRankingScreen(Screen parent, EventOptionSelectScreen.EventEntry eventInfo) {
        super(Text.literal("이벤트 랭킹"));
        this.parent = parent;
        this.eventInfo = eventInfo;
    }

    private int parseHex(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            if (hex.startsWith("#")) hex = hex.substring(1);
            return 0xFF000000 | Integer.parseInt(hex, 16);
        } catch (Exception e) { return fallback; }
    }

    private void playUiClick() {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private void fetchProfileDesc(String playerName) {
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_player", playerName);
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_profile", req.toString());
                if (obj.has("ok") && obj.get("ok").getAsBoolean() && obj.has("description")) {
                    String desc = obj.get("description").getAsString();
                    selectedProfileDesc = desc.trim().isEmpty() ? "작성된 소개글이 없습니다." : desc;
                } else {
                    selectedProfileDesc = "작성된 소개글이 없습니다.";
                }
            } catch (Exception ex) {
                selectedProfileDesc = "정보를 불러오지 못했습니다.";
            }
        }).start();
    }

    private void updateDetailButtons() {
        boolean show = (selectedDetailEntry != null);
        if (detailCloseBtn != null) detailCloseBtn.visible = show;
        if (detailProfileBtn != null) detailProfileBtn.visible = show;

        if (prevBtn != null) prevBtn.visible = !show;
        if (nextBtn != null) nextBtn.visible = !show;
        if (refreshBtn != null) refreshBtn.visible = !show;
        if (joinBtn != null) joinBtn.visible = !show;
        if (backBtn != null) backBtn.visible = !show;
        if (searchBtn != null) searchBtn.visible = !show;
    }

    private void renderDetailBox(DrawContext context) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int detailW = 340;
        int detailH = 170;
        int detailX = cx - detailW / 2;
        int detailY = cy - detailH / 2 + 10;

        context.fill(detailX, detailY, detailX + detailW, detailY + detailH, 0xEE0B0B0B);
        drawRectBorder(context, detailX, detailY, detailW, detailH, 0xFF444444);

        String repText = "";
        if (selectedDetailEntry.repTitle() != null && !selectedDetailEntry.repTitle().isEmpty()) {
            repText = " [" + selectedDetailEntry.repTitle() + "]";
        }

        context.drawTextWithShadow(textRenderer, "👤 " + selectedDetailEntry.player(), detailX + 15, detailY + 15, 0xFFDDAA);
        if (!repText.isEmpty()) {
            int rColor = parseHex(selectedDetailEntry.repColor(), 0x55FFFF);
            context.drawTextWithShadow(textRenderer, repText, detailX + 15 + textRenderer.getWidth("👤 " + selectedDetailEntry.player()), detailY + 15, rColor);
        }

        context.drawTextWithShadow(textRenderer, "§7" + selectedProfileDesc, detailX + 15, detailY + 30, 0xAAAAAA);

        int infoX = detailX + 140;
        int infoY = detailY + 45;
        int lineH = 16;

        context.drawTextWithShadow(textRenderer, "§8트랙: §f" + eventInfo.track(), infoX, infoY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "§8기록: §e" + selectedDetailEntry.time(), infoX, infoY + lineH, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "§8카트: §f" + TireUtil.composeBodyLabel(selectedDetailEntry.bodyName(), selectedDetailEntry.tireName()), infoX, infoY + lineH * 2, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "§8엔진: §f" + (selectedDetailEntry.engineName() == null ? "UNKNOWN" : selectedDetailEntry.engineName().toUpperCase()), infoX, infoY + lineH * 3, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "§8모드: §f" + (selectedDetailEntry.modes() == null || selectedDetailEntry.modes().isEmpty() ? "없음" : selectedDetailEntry.modes()), infoX, infoY + lineH * 4, 0xFFFFFF);

        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(selectedDetailEntry.submittedAtMs()));
        context.drawTextWithShadow(textRenderer, "§8등록: §7" + dateStr, infoX, infoY + lineH * 5, 0xAAAAAA);
    }

    @Override
    protected void init() {
        super.init();
        if (isCacheValid()) {
            ranking.clear(); ranking.addAll(cachedRanking); loading = false;
        } else {
            loading = true; fetchRanking();
        }
        checkPlayerJoined();
        rebuildUI();
    }

    private void rebuildUI() {
        clearChildren();
        int cx = this.width / 2;
        int bottomY = this.height - 28;
        boolean isSmall = this.width < 420;
        int pageGap = isSmall ? 40 : 60;
        int iconBtnSize = 20;

        searchBtn = addDrawableChild(ButtonWidget.builder(Text.literal("🔍"), b -> { playUiClick(); if (this.client != null) this.client.setScreen(new RiderFindScreen(this)); })
                .dimensions(OUTER_PAD, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("라이더 찾기"))).build());

        prevBtn = addDrawableChild(ButtonWidget.builder(Text.literal("◀"), b -> { playUiClick(); if (page > 0) page--; }).dimensions(cx - pageGap - 10, bottomY, 20, 20).build());
        nextBtn = addDrawableChild(ButtonWidget.builder(Text.literal("▶"), b -> { playUiClick(); if ((page + 1) * PAGE_SIZE < ranking.size()) page++; }).dimensions(cx + pageGap - 10, bottomY, 20, 20).build());

        backBtn = addDrawableChild(ButtonWidget.builder(Text.literal("⏴"), b -> { playUiClick(); if (this.client != null) this.client.setScreen(parent); })
                .dimensions(this.width - (iconBtnSize * 2) - OUTER_PAD - 5, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build());

        refreshBtn = addDrawableChild(ButtonWidget.builder(Text.literal("🔄"), b -> {
                    playUiClick(); loading = true; error = null; ranking.clear(); cachedRanking = null; cachedEventId = null; fetchRanking(); checkPlayerJoined(); })
                .dimensions(this.width - iconBtnSize - OUTER_PAD, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build());

        int joinBtnW = isSmall ? 50 : 60;
        joinBtn = addDrawableChild(ButtonWidget.builder(Text.literal("확인 중..."), b -> openJoinConfirm()).dimensions(this.width - OUTER_PAD - joinBtnW - 5, 25, joinBtnW, 20).build());

        int detailW = 340;
        int detailH = 170;
        int detailX = cx - detailW / 2;
        int detailY = (this.height / 2) - detailH / 2 + 10;

        detailCloseBtn = addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> {
            playUiClick(); selectedDetailEntry = null; updateDetailButtons();
        }).dimensions(detailX + detailW - 24, detailY + 4, 20, 20).build());

        detailProfileBtn = addDrawableChild(ButtonWidget.builder(Text.literal("프로필 가기"), b -> {
            playUiClick();
            if (this.client != null && selectedDetailEntry != null) {
                this.client.setScreen(new PlayerProfileScreen(selectedDetailEntry.player(), this));
            }
        }).dimensions(detailX + 15, detailY + 50, 80, 20).build());

        detailCloseBtn.visible = false;
        detailProfileBtn.visible = false;

        updateJoinButtonState();
        updateDetailButtons();
    }

    private void updateJoinButtonState() {
        if (joinBtn == null) return;
        if (checkingJoin) {
            joinBtn.setMessage(Text.literal("로딩중"));
            joinBtn.active = false;
        } else if (alreadyJoined) {
            String currentID = eventInfo.eventID();
            boolean isIncluded = false;
            if (currentID != null && joinedEventID != null) {
                for (String id : joinedEventID.split(",")) {
                    if (id.trim().equals(currentID)) {
                        isIncluded = true;
                        break;
                    }
                }
            }
            joinBtn.setMessage(Text.literal(isIncluded ? "참여 완료" : "참여 불가"));
            joinBtn.active = false;
        } else {
            joinBtn.setMessage(Text.literal("참여"));
            joinBtn.active = true;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!loading && error == null && !ranking.isEmpty() && selectedDetailEntry == null) {
            int tableTop = 70;
            int tableX = OUTER_PAD + 8;
            int tableW = this.width - (OUTER_PAD + 8) * 2;
            int startY = tableTop + 24;

            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, ranking.size());

            for (int i = start; i < end; i++) {
                int y = startY + (i - start) * ROW_H;
                if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= y - 2 && mouseY <= y + ROW_H - 2) {
                    playUiClick();
                    selectedDetailEntry = ranking.get(i);
                    selectedProfileDesc = "불러오는 중...";
                    updateDetailButtons();
                    fetchProfileDesc(selectedDetailEntry.player());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void checkPlayerJoined() {
        checkingJoin = true; updateJoinButtonState();
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_player", MinecraftClient.getInstance().getSession().getUsername());
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "check_event_join", req.toString());
                if (obj.has("joined")) {
                    alreadyJoined = obj.get("joined").getAsBoolean();
                    joinedEventID = (alreadyJoined && obj.has("eventID")) ? obj.get("eventID").getAsString() : null;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                checkingJoin = false;
                if (this.client != null) this.client.execute(this::updateJoinButtonState);
            }
        }).start();
    }

    private void openJoinConfirm() {
        if (this.client == null) return;
        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) joinEvent();
            this.client.setScreen(this);
        }, Text.literal("주의!"), Text.literal("정말로 이 이벤트를 참여하시겠습니까?\n이벤트가 종료되기 전 까지 이벤트를 변경할 수 없습니다."), Text.literal("참여합니다"), Text.literal("다시 생각해볼게요")));
    }

    private void joinEvent() {
        checkingJoin = true; updateJoinButtonState();
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_player", MinecraftClient.getInstance().getSession().getUsername());
                req.addProperty("p_event_id", eventInfo.eventID());
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "join_event", req.toString());
                if (obj.get("ok").getAsBoolean()) {
                    alreadyJoined = true;
                    joinedEventID = eventInfo.eventID();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                checkingJoin = false;
                if (this.client != null) this.client.execute(this::updateJoinButtonState);
            }
        }).start();
    }

    private void fetchRanking() {
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_event_id", eventInfo.eventID());
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_event_ranking", req.toString());

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    JsonArray arr = obj.getAsJsonArray("ranking");
                    ranking.clear();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        ranking.add(new RankingEntry(
                                o.get("player").getAsString(),
                                o.has("repTitle") ? o.get("repTitle").getAsString() : "",
                                o.has("repColor") ? o.get("repColor").getAsString() : "#55FFFF",
                                o.get("time").getAsString(),
                                o.get("engineName").getAsString(),
                                o.get("bodyName").getAsString(),
                                o.get("modes").getAsString(),
                                o.get("tireName").getAsString(),
                                o.has("submittedAtMs") ? o.get("submittedAtMs").getAsLong() : 0L
                        ));
                    }
                    cachedRanking = new ArrayList<>(ranking);
                    cachedAt = System.currentTimeMillis();
                    cachedEventId = eventInfo.eventID();
                }
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                loading = false;
            }
        }).start();
    }

    private void drawRectBorder(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x, y, x + w, y + 1, color);
        c.fill(x, y + h - 1, x + w, y + h, color);
        c.fill(x, y, x + 1, y + h, color);
        c.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int cx = this.width / 2;

        context.fill(OUTER_PAD, 10, this.width - OUTER_PAD, 60, 0xCC000000);
        drawRectBorder(context, OUTER_PAD, 10, this.width - OUTER_PAD * 2, 50, 0xFF2A2A2A);

        context.drawCenteredTextWithShadow(textRenderer, "TRACK: " + eventInfo.track(), cx, 18, 0xAAAAAA);
        context.drawCenteredTextWithShadow(textRenderer, "§6§l" + eventInfo.name(), cx, 30, 0xFFDDAA);
        context.drawCenteredTextWithShadow(textRenderer, "§7기간: " + eventInfo.startDate() + " ~ " + eventInfo.endDate(), cx, 44, 0xBBBBBB);

        if (selectedDetailEntry != null) {
            renderDetailBox(context);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        int tableTop = 70;
        int tableW = this.width - (OUTER_PAD + 8) * 2;
        int tableX = OUTER_PAD + 8;
        int tableH = Math.max(80, this.height - 46 - tableTop);

        context.fill(tableX, tableTop, tableX + tableW, tableTop + tableH, 0x66000000);
        drawRectBorder(context, tableX, tableTop, tableW, tableH, 0xFF222222);

        if (loading) {
            context.drawCenteredTextWithShadow(textRenderer, "데이터를 불러오는 중...", cx, tableTop + 40, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (error != null) {
            context.drawCenteredTextWithShadow(textRenderer, "오류: " + error, cx, tableTop + 40, 0xFF5555);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (ranking.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, "등록된 기록이 없습니다.", cx, tableTop + 40, 0xAAAAAA);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        int rX = tableX + (int)(tableW * 0.02);
        int pX = tableX + (int)(tableW * 0.15);
        int tmX = tableX + (int)(tableW * 0.40);
        int bX = tableX + (int)(tableW * 0.60);
        int eX = tableX + (int)(tableW * 0.85);

        context.drawTextWithShadow(textRenderer, "순위", rX, tableTop + 8, 0xDDDDDD);
        context.drawTextWithShadow(textRenderer, "플레이어", pX, tableTop + 8, 0xDDDDDD);
        if (tableW > 250) context.drawTextWithShadow(textRenderer, "기록", tmX, tableTop + 8, 0xDDDDDD);
        if (tableW > 320) context.drawTextWithShadow(textRenderer, "카트바디", bX, tableTop + 8, 0xDDDDDD);
        if (tableW > 380) context.drawTextWithShadow(textRenderer, "엔진", eX, tableTop + 8, 0xDDDDDD);

        String myName = MinecraftClient.getInstance().getSession().getUsername();

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, ranking.size());

        for (int i = start; i < end; i++) {
            RankingEntry r = ranking.get(i);
            int y = tableTop + 24 + (i - start) * ROW_H;
            int rank = i + 1;

            if (r.player().equalsIgnoreCase(myName)) {
                context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + 17, 0x6644AA44);
            } else if (rank == 1) {
                context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + 17, 0x44FFD700);
            } else if (rank == 2) {
                context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + 17, 0x44C0C0C0);
            } else if (rank == 3) {
                context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + 17, 0x44CD7F32);
            } else if (((i - start) & 1) == 1) {
                context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + 17, 0x22000000);
            }

            // 마우스 오버 효과 (행 전체)
            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= y - 2 && mouseY <= y + ROW_H - 2) {
                context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + ROW_H - 1, 0x33FFFFFF);
            }

            int rankColor = (rank == 1) ? 0xFFFFE066 : (rank == 2) ? 0xFFE6E6E6 : (rank == 3) ? 0xFFFFB36B : 0xFFFFFF;
            context.drawTextWithShadow(textRenderer, rank + "위", rX, y, rankColor);

            String rt = "";
            if (r.repTitle() != null && !r.repTitle().isEmpty()) {
                rt = " [" + r.repTitle() + "]";
            }

            if (rt.isEmpty()) {
                context.drawTextWithShadow(textRenderer, r.player(), pX, y, 0xFFFFFF);
            } else {
                context.drawTextWithShadow(textRenderer, r.player(), pX, y, 0xFFFFFF);
                context.drawTextWithShadow(textRenderer, rt, pX + textRenderer.getWidth(r.player()), y, parseHex(r.repColor(), 0x55FFFF));
            }

            boolean hs = false;
            if (tableW > 250) context.drawTextWithShadow(textRenderer, r.time(), tmX, y, 0xFFFFFF); else hs = true;
            if (tableW > 320) context.drawTextWithShadow(textRenderer, TireUtil.composeBodyLabel(r.bodyName(), r.tireName()), bX, y, 0xFFFFFF); else hs = true;
            if (tableW > 380) context.drawTextWithShadow(textRenderer, r.engineName().toUpperCase(), eX, y, 0xFFFFFF); else hs = true;

            if (hs) context.drawTextWithShadow(textRenderer, "+", tableX + tableW - 16, y, 0xAAAAAA);
        }

        int totalPages = Math.max(1, (ranking.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        context.drawCenteredTextWithShadow(textRenderer, String.format("페이지 %d / %d", (page + 1), totalPages), cx, this.height - 26, 0xAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}