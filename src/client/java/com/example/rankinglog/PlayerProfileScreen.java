package com.example.rankinglog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayerProfileScreen extends Screen {

    private final String playerName;
    private final Screen parent;
    private final List<RecordRow> records = new ArrayList<>();

    private boolean loading = true;
    private String error = null;
    private int page = 0;
    private int rowsPerPage = 10;
    private ButtonWidget prevBtn, nextBtn;

    private static final int OUTER_PAD = 12, HEADER_TOP = 10, ROW_H = 18;
    private static final float TABLE_SCALE = 0.86f;

    private boolean isMe;
    private boolean isEditing = false;
    private String profileDescription = "";
    private boolean achievementsExpanded = false;
    private ButtonWidget expandBtn;

    public record Achievement(String id, String full, String simple, String desc, String color) {}
    private final List<Achievement> profileAchievements = new ArrayList<>();
    private final List<Achievement> missingAchievements = new ArrayList<>();
    private String repAchieveId = "";
    private String repAchieveSimple = "";
    private String repAchieveColor = "#55FFFF";

    private ButtonWidget repToggleBtn;
    private boolean repPanelOpen = false;
    private int repScroll = 0;
    private static final int REP_VISIBLE_ROWS = 5;
    private int repPanelX, repPanelY, repPanelW, repPanelH;

    private boolean showAchievementDesc = false;
    private Achievement selectedAchievement = null;
    private ButtonWidget closeDescBtn;

    private record AchHit(Achievement ach, int x, int y, int w, int h) {
        boolean hit(double mx, double my) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }
    }
    private final List<AchHit> achHits = new ArrayList<>();
    private TextFieldWidget descField;
    private ButtonWidget editBtn, saveBtn;

    private final List<ModeHit> modeHits = new ArrayList<>();
    private final List<DateHit> dateHits = new ArrayList<>();
    private final List<TireHit> tireHits = new ArrayList<>();

    private static final class ModeHit {
        final int x1, y1, x2, y2; final String fullText;
        ModeHit(int x1, int y1, int x2, int y2, String fullText) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.fullText = fullText; }
        boolean hit(int mx, int my) { return mx >= x1 && mx <= x2 && my >= y1 && my <= y2; }
    }
    private static final class DateHit {
        final int x1, y1, x2, y2; final long submittedAtMs;
        DateHit(int x1, int y1, int x2, int y2, long submittedAtMs) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.submittedAtMs = submittedAtMs; }
        boolean hit(int mx, int my) { return mx >= x1 && mx <= x2 && my >= y1 && my <= y2; }
    }
    private static final class TireHit {
        final int x1, y1, x2, y2; final String tireName;
        TireHit(int x1, int y1, int x2, int y2, String tireName) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.tireName = tireName; }
        boolean hit(int mx, int my) { return mx >= x1 && mx <= x2 && my >= y1 && my <= y2; }
    }

    public PlayerProfileScreen(String playerName, Screen parent) {
        super(Text.literal("프로필"));
        this.playerName = (playerName == null) ? "" : playerName.trim();
        this.parent = parent;
    }

    private int parseHex(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try { if (hex.startsWith("#")) hex = hex.substring(1); return 0xFF000000 | Integer.parseInt(hex, 16); } catch (Exception e) { return fallback; }
    }

    private void playUiClick() { if (this.client != null) this.client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0f)); }
    @Override public void close() { if (this.client != null) this.client.setScreen(parent); }

    @Override
    protected void init() {
        boolean isSmall = this.width < 420; int pageBtnGap = isSmall ? 40 : 60; int y = this.height - 28; int cx = this.width / 2; int iconBtnSize = 20;
        String myName = this.client != null ? this.client.getSession().getUsername() : ""; this.isMe = this.playerName.equalsIgnoreCase(myName);

        addDrawableChild(ButtonWidget.builder(Text.literal("⏴"), b -> { playUiClick(); this.close(); }).dimensions(OUTER_PAD, y, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build());

        prevBtn = ButtonWidget.builder(Text.literal("◀"), b -> { playUiClick(); if (page > 0) page--; updatePaginationButtons(); }).dimensions(cx - pageBtnGap - 10, this.height - 28, 20, 20).build(); addDrawableChild(prevBtn);
        nextBtn = ButtonWidget.builder(Text.literal("▶"), b -> { playUiClick(); if ((page + 1) * rowsPerPage < records.size()) page++; updatePaginationButtons(); }).dimensions(cx + pageBtnGap - 10, this.height - 28, 20, 20).build(); addDrawableChild(nextBtn);

        addDrawableChild(ButtonWidget.builder(Text.literal("🔄"), b -> { playUiClick(); loadAllData(true); }).dimensions(this.width - iconBtnSize - OUTER_PAD, y, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build());

        int headerX = OUTER_PAD; int headerW = this.width - OUTER_PAD * 2; int splitX = headerX + (int)(headerW * 0.25);
        int rightPadX = splitX + 15; int rightPadY = HEADER_TOP + 15; int maxDescWidth = (headerX + headerW) - rightPadX - 15;

        descField = new TextFieldWidget(this.textRenderer, rightPadX, rightPadY + 13, maxDescWidth, 16, Text.literal("자기 소개"));
        descField.setMaxLength(60); descField.setVisible(false); addDrawableChild(descField);

        editBtn = ButtonWidget.builder(Text.literal("수정"), b -> { playUiClick(); isEditing = true; descField.setText(profileDescription); updateProfileUIVisibility(); }).dimensions(rightPadX + 75, rightPadY - 4, 36, 16).build(); addDrawableChild(editBtn);
        saveBtn = ButtonWidget.builder(Text.literal("저장"), b -> { playUiClick(); saveProfileDescription(descField.getText()); }).dimensions(rightPadX + 75, rightPadY - 4, 36, 16).build(); addDrawableChild(saveBtn);

        repToggleBtn = ButtonWidget.builder(Text.literal("대표 업적 설정"), b -> { playUiClick(); repPanelOpen = !repPanelOpen; updateProfileUIVisibility(); }).dimensions(rightPadX + 60, rightPadY + 41, 85, 16).build(); addDrawableChild(repToggleBtn);
        expandBtn = ButtonWidget.builder(Text.literal("[+]"), b -> { playUiClick(); achievementsExpanded = !achievementsExpanded; b.setMessage(Text.literal(achievementsExpanded ? "[-]" : "[+]")); updateProfileUIVisibility(); }).dimensions(rightPadX + 35, rightPadY + 41, 20, 16).build(); expandBtn.visible = false; addDrawableChild(expandBtn);
        closeDescBtn = ButtonWidget.builder(Text.literal("X"), b -> { playUiClick(); showAchievementDesc = false; updateProfileUIVisibility(); }).dimensions(this.width - OUTER_PAD - 32, HEADER_TOP + 124, 20, 20).build(); closeDescBtn.visible = false; addDrawableChild(closeDescBtn);

        updateProfileUIVisibility();
        if (loading && records.isEmpty() && profileAchievements.isEmpty()) loadAllData(false);
    }

    private void loadAllData(boolean forceRefreshCache) {
        loading = true; error = null; records.clear(); profileAchievements.clear(); missingAchievements.clear();
        repAchieveId = ""; repAchieveSimple = ""; repAchieveColor = "#55FFFF"; showAchievementDesc = false; page = 0;
        updateProfileUIVisibility();

        AtomicInteger tasksCompleted = new AtomicInteger(0);

        new Thread(() -> {
            try {
                JsonObject req = new JsonObject(); req.addProperty("p_player", this.playerName);
                // ===== [수정] Supabase RPC 호출로 변경 =====
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_profile", req.toString());

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    profileDescription = obj.has("description") ? obj.get("description").getAsString() : "";
                    repAchieveId = obj.has("repId") ? obj.get("repId").getAsString() : "";
                    repAchieveSimple = obj.has("repSimple") ? obj.get("repSimple").getAsString() : "";
                    repAchieveColor = obj.has("repColor") ? obj.get("repColor").getAsString() : "#55FFFF";

                    if (obj.has("achievements")) {
                        for (JsonElement elem : obj.getAsJsonArray("achievements")) {
                            JsonObject a = elem.getAsJsonObject();
                            profileAchievements.add(new Achievement(a.get("id").getAsString(), a.get("full").getAsString(), a.get("simple").getAsString(), a.has("desc") ? a.get("desc").getAsString() : "설명이 없습니다.", a.has("color") ? a.get("color").getAsString() : "#55FFFF"));
                        }
                    }
                    if (obj.has("missing")) {
                        for (JsonElement elem : obj.getAsJsonArray("missing")) {
                            JsonObject a = elem.getAsJsonObject();
                            missingAchievements.add(new Achievement(a.get("id").getAsString(), a.get("full").getAsString(), a.get("simple").getAsString(), a.has("desc") ? a.get("desc").getAsString() : "설명이 없습니다.", a.has("color") ? a.get("color").getAsString() : "#55FFFF"));
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); } finally { checkCompletion(tasksCompleted); }
        }).start();

        Runnable onCacheDone = () -> { loadRecordsFromCache(); checkCompletion(tasksCompleted); };
        if (forceRefreshCache || !RankingScreen.ApiCache.isAllReady()) { RankingScreen.ApiCache.fetchAllAsync(true, p -> onCacheDone.run(), err -> { error = err; checkCompletion(tasksCompleted); }); } else { onCacheDone.run(); }
    }

    private void checkCompletion(AtomicInteger tasksCompleted) {
        if (tasksCompleted.incrementAndGet() >= 2) {
            if (this.client != null) { this.client.execute(() -> { loading = false; updatePaginationButtons(); updateProfileUIVisibility(); }); }
        }
    }

    private void updatePaginationButtons() {
        int headerH = achievementsExpanded ? this.height - HEADER_TOP - 46 : 110; int tableTop = HEADER_TOP + headerH + 10;
        int tableBottom = this.height - 46; int tableH = Math.max(80, tableBottom - tableTop);
        rowsPerPage = Math.min(14, Math.max(1, Math.max(1, tableH - 28) / ROW_H));
        int maxPage = Math.max(0, (records.size() - 1) / rowsPerPage);
        if (page > maxPage) page = maxPage; if (page < 0) page = 0;

        if (prevBtn != null) { prevBtn.active = (page > 0); prevBtn.visible = !repPanelOpen && !achievementsExpanded && !showAchievementDesc; }
        if (nextBtn != null) { nextBtn.active = (!records.isEmpty() && (page + 1) * rowsPerPage < records.size()); nextBtn.visible = !repPanelOpen && !achievementsExpanded && !showAchievementDesc; }
    }

    private void updateProfileUIVisibility() {
        if (editBtn != null) editBtn.visible = isMe && !isEditing && !loading && !repPanelOpen;
        if (saveBtn != null) saveBtn.visible = isMe && isEditing && !loading && !repPanelOpen;
        if (descField != null) descField.setVisible(isEditing && !repPanelOpen);
        if (repToggleBtn != null) repToggleBtn.visible = isMe && !loading && !profileAchievements.isEmpty();
        if (expandBtn != null) expandBtn.visible = !loading && (isMe || profileAchievements.size() > 5) && !repPanelOpen;
        if (closeDescBtn != null) closeDescBtn.visible = showAchievementDesc && !achievementsExpanded && !repPanelOpen;
        updatePaginationButtons();
    }

    private void saveRepAchieve(String targetId) {
        loading = true; repPanelOpen = false; updateProfileUIVisibility();
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject(); req.addProperty("p_player", this.playerName); req.addProperty("p_rep_id", targetId);
                // ===== [수정] Supabase RPC 호출로 변경 =====
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "update_rep_achieve", req.toString());

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    repAchieveId = targetId;
                    if (targetId.isEmpty()) { repAchieveSimple = ""; repAchieveColor = "#55FFFF"; }
                    else { for (Achievement a : profileAchievements) { if (a.id().equals(targetId)) { repAchieveSimple = a.simple(); repAchieveColor = a.color(); break; } } }
                }
            } catch (Exception e) { e.printStackTrace(); } finally { if (this.client != null) { this.client.execute(() -> { loading = false; updateProfileUIVisibility(); }); } }
        }).start();
    }

    private void saveProfileDescription(String newDesc) {
        loading = true; isEditing = false; updateProfileUIVisibility();
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject(); req.addProperty("p_player", this.playerName); req.addProperty("p_desc", newDesc);
                // ===== [수정] Supabase RPC 호출로 변경 =====
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "update_profile", req.toString());

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) { profileDescription = newDesc; }
            } catch (Exception e) { e.printStackTrace(); } finally { if (this.client != null) { this.client.execute(() -> { loading = false; updateProfileUIVisibility(); }); } }
        }).start();
    }

    private void loadRecordsFromCache() {
        records.clear(); RankingScreen.ApiCache.AllPayload all = RankingScreen.ApiCache.getAllIfReady(); if (all == null) return;
        for (var entry : all.rankingsByTrack.entrySet()) {
            String track = entry.getKey();
            for (RankingScreen.Entry e : entry.getValue().ranking) {
                if (e.player() == null) continue;
                if (e.player().equalsIgnoreCase(playerName)) {
                    records.add(new RecordRow(e.submittedAtMs(), track, safeText(e.timeStr(), "??:??.???"), safeText(e.bodyName(), "UNKNOWN"), safeText(e.tireName(), "UNKNOWN"), safeText(e.engineName(), "UNKNOWN"), safeText(e.modes(), "없음")));
                }
            }
        }
        records.sort(Comparator.comparingLong(RecordRow::submittedAtMs).reversed()); page = 0;
    }

    private static String safeText(String s, String def) { if (s == null) return def; String t = s.trim(); return t.isBlank() ? def : t; }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (repPanelOpen && mouseX >= repPanelX && mouseX < repPanelX + repPanelW && mouseY >= repPanelY && mouseY < repPanelY + repPanelH) {
            int maxScroll = Math.max(0, profileAchievements.size() - REP_VISIBLE_ROWS);
            if (verticalAmount > 0) repScroll--; else if (verticalAmount < 0) repScroll++;
            if (repScroll < 0) repScroll = 0; if (repScroll > maxScroll) repScroll = maxScroll;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (repPanelOpen) {
            if (mouseX < repPanelX || mouseX >= repPanelX + repPanelW || mouseY < repPanelY || mouseY >= repPanelY + repPanelH) { repPanelOpen = false; updateProfileUIVisibility(); return true; }
            int startIndex = repScroll; int endIndex = Math.min(profileAchievements.size(), startIndex + REP_VISIBLE_ROWS); int y = repPanelY + 6;
            for (int i = startIndex; i < endIndex; i++) {
                if (mouseY >= y && mouseY < y + ROW_H) {
                    Achievement ach = profileAchievements.get(i); String targetId = ach.id().equals(repAchieveId) ? "" : ach.id(); playUiClick(); saveRepAchieve(targetId); return true;
                }
                y += ROW_H;
            }
            return true;
        }
        if (!loading && !repPanelOpen) {
            for (AchHit hit : achHits) {
                if (hit.hit(mouseX, mouseY)) { playUiClick(); selectedAchievement = hit.ach(); showAchievementDesc = true; achievementsExpanded = false; if (expandBtn != null) expandBtn.setMessage(Text.literal("[+]")); updateProfileUIVisibility(); return true; }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override protected void applyBlur() { }
    @Override public void blur() { }
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int headerX = OUTER_PAD; int headerY = HEADER_TOP; int headerW = this.width - OUTER_PAD * 2; int headerH = achievementsExpanded ? this.height - HEADER_TOP - 46 : 110;

        context.fill(headerX, headerY, headerX + headerW, headerY + headerH, 0xCC000000); drawRectBorder(context, headerX, headerY, headerW, headerH, 0xFF2A2A2A);
        int splitX = headerX + (int)(headerW * 0.25); context.fill(splitX, headerY, splitX + 1, headerY + headerH, 0xFF2A2A2A);
        int leftBoxCenterX = headerX + (splitX - headerX) / 2;

        context.getMatrices().push(); context.getMatrices().scale(2.0f, 2.0f, 1.0f); context.drawCenteredTextWithShadow(this.textRenderer, "👤", (int)(leftBoxCenterX / 2.0f), (int)((headerY + 15) / 2.0f), 0xFFFFFF); context.getMatrices().pop();

        if (repAchieveSimple.isEmpty()) { context.drawCenteredTextWithShadow(this.textRenderer, playerName, leftBoxCenterX, headerY + 55, 0xFFDDAA); }
        else {
            int repColorInt = parseHex(repAchieveColor, 0x55FFFF); String repText = " [" + repAchieveSimple + "]";
            int totalW = this.textRenderer.getWidth(playerName) + this.textRenderer.getWidth(repText); int startX = leftBoxCenterX - totalW / 2;
            context.drawTextWithShadow(this.textRenderer, playerName, startX, headerY + 55, 0xFFDDAA);
            context.drawTextWithShadow(this.textRenderer, repText, startX + this.textRenderer.getWidth(playerName), headerY + 55, repColorInt);
        }

        String sub = (loading) ? "불러오는 중..." : (error != null ? "오류 발생" : "총 기록: " + records.size());
        context.drawCenteredTextWithShadow(this.textRenderer, sub, leftBoxCenterX, headerY + 75, 0xBBBBBB);

        int rightPadX = splitX + 15; int rightPadY = headerY + 15;
        context.drawTextWithShadow(this.textRenderer, "§e[ 자기 소개 ]", rightPadX, rightPadY, 0xFFFFFF);
        achHits.clear();

        // 툴팁 상태 저장을 위한 변수
        int hoveredDateIndex = -1;
        int hoveredTireIndex = -1;
        String hoveredModeText = null;

        if (!repPanelOpen) {
            if (loading) { context.drawTextWithShadow(this.textRenderer, "불러오는 중...", rightPadX, rightPadY + 18, 0xAAAAAA); }
            else if (!isEditing) { String displayDesc = profileDescription.trim().isEmpty() ? "아직 작성된 소개글이 없습니다." : profileDescription; context.drawTextWithShadow(this.textRenderer, displayDesc, rightPadX, rightPadY + 18, 0xAAAAAA); }

            context.drawTextWithShadow(this.textRenderer, "§b[ 업적 ]", rightPadX, rightPadY + 45, 0xFFFFFF);

            if (loading) { context.drawTextWithShadow(this.textRenderer, "불러오는 중...", rightPadX, rightPadY + 60, 0xAAAAAA); }
            else {
                int currentX = rightPadX; int currentY = rightPadY + 60; int maxRight = headerX + headerW - 10; int achCount = 0;
                if (profileAchievements.isEmpty()) { context.drawTextWithShadow(this.textRenderer, "달성한 업적이 없습니다.", rightPadX, rightPadY + 60, 0xAAAAAA); currentY += 12; }
                else {
                    for (int i = 0; i < profileAchievements.size(); i++) {
                        if (!achievementsExpanded && achCount >= 5) { context.drawTextWithShadow(this.textRenderer, "§b+", currentX, currentY, 0xFFFFFF); break; }
                        if (currentY + 10 > headerY + headerH) break;
                        Achievement ach = profileAchievements.get(i); String text = "[" + ach.full() + "]"; int w = this.textRenderer.getWidth(text);
                        if (currentX + w > maxRight) { currentX = rightPadX; currentY += 12; }
                        boolean hover = mouseX >= currentX && mouseX <= currentX + w && mouseY >= currentY - 1 && mouseY <= currentY + 9;
                        int customColor = parseHex(ach.color(), 0x55FFFF); int drawColor = hover ? 0xFFFFFF : customColor;
                        context.drawTextWithShadow(this.textRenderer, text, currentX, currentY, drawColor); achHits.add(new AchHit(ach, currentX, currentY - 1, w, 10));
                        currentX += w + 4; achCount++;
                    }
                }
                if (achievementsExpanded && isMe && !missingAchievements.isEmpty()) {
                    currentY += 18; currentX = rightPadX;
                    if (currentY + 10 <= headerY + headerH) {
                        context.drawTextWithShadow(this.textRenderer, "§c[ 미달성 업적 ]", currentX, currentY, 0xFFFFFF); currentY += 14;
                        for (Achievement ach : missingAchievements) {
                            if (currentY + 10 > headerY + headerH) break;
                            String text = "[" + ach.full() + "]"; int w = this.textRenderer.getWidth(text);
                            if (currentX + w > maxRight) { currentX = rightPadX; currentY += 12; }
                            boolean hover = mouseX >= currentX && mouseX <= currentX + w && mouseY >= currentY - 1 && mouseY <= currentY + 9;
                            int customColor = parseHex(ach.color(), 0x55FFFF); int darkerColor = (customColor & 0xFEFEFE) >> 1 | 0xFF000000; int drawColor = hover ? 0xBBBBBB : darkerColor;
                            context.drawTextWithShadow(this.textRenderer, text, currentX, currentY, drawColor); achHits.add(new AchHit(ach, currentX, currentY - 1, w, 10));
                            currentX += w + 4;
                        }
                    }
                }
            }
        }

        if (!achievementsExpanded && !repPanelOpen) {
            int tableX = OUTER_PAD + 8; int tableY = headerY + headerH + 10; int tableW = this.width - (OUTER_PAD + 8) * 2; int tableBottom = this.height - 46; int tableH = Math.max(120, tableBottom - tableY);
            if (showAchievementDesc && selectedAchievement != null) {
                context.fill(tableX, tableY, tableX + tableW, tableY + tableH, 0xDD000000);
                int achColor = parseHex(selectedAchievement.color(), 0x55FFFF); drawRectBorder(context, tableX, tableY, tableW, tableH, achColor);
                int titleW = this.textRenderer.getWidth("[ " + selectedAchievement.full() + " ]");
                context.drawTextWithShadow(this.textRenderer, "[ " + selectedAchievement.full() + " ]", (this.width - titleW) / 2, tableY + 15, achColor);
                List<OrderedText> descLines = this.textRenderer.wrapLines(Text.literal(selectedAchievement.desc()), tableW - 30);
                int dy = tableY + 40; for (OrderedText line : descLines) { context.drawTextWithShadow(this.textRenderer, line, tableX + 15, dy, 0xDDDDDD); dy += 12; }
            } else {
                context.fill(tableX, tableY, tableX + tableW, tableY + tableH, 0x66000000); drawRectBorder(context, tableX, tableY, tableW, tableH, 0xFF222222);
                if (!loading && error == null && !records.isEmpty()) {
                    modeHits.clear(); dateHits.clear(); tireHits.clear();
                    int smx = (int) (mouseX / TABLE_SCALE); int smy = (int) (mouseY / TABLE_SCALE);
                    context.getMatrices().push(); context.getMatrices().scale(TABLE_SCALE, TABLE_SCALE, 1.0f);
                    int sx = (int) (tableX / TABLE_SCALE); int sy = (int) (tableY / TABLE_SCALE); int sW = (int) (tableW / TABLE_SCALE); int headerRowY = sy + 8;
                    int colDate = sx + 10; int colTrack = sx + 105; int colTime = sx + (int)(sW * 0.52f); int colBody = sx + (int)(sW * 0.66f); int colEngine = sx + (int)(sW * 0.83f); int colMode = sx + (int)(sW * 0.92f);

                    context.drawTextWithShadow(textRenderer, "날짜", colDate, headerRowY, 0xDDDDDD); context.drawTextWithShadow(textRenderer, "트랙", colTrack, headerRowY, 0xDDDDDD); context.drawTextWithShadow(textRenderer, "기록", colTime, headerRowY, 0xDDDDDD); context.drawTextWithShadow(textRenderer, "카트바디", colBody, headerRowY, 0xDDDDDD); context.drawTextWithShadow(textRenderer, "엔진", colEngine, headerRowY, 0xDDDDDD); context.drawTextWithShadow(textRenderer, "모드", colMode, headerRowY, 0xDDDDDD);
                    int startY = sy + 24; int start = page * rowsPerPage; int end = Math.min(start + rowsPerPage, records.size());

                    for (int i = start; i < end; i++) {
                        RecordRow r = records.get(i); int idx = i - start; int ry = startY + idx * ROW_H;
                        if ((idx & 1) == 1) context.fill((int)(tableX / TABLE_SCALE) + 1, ry - 2, (int)((tableX + tableW) / TABLE_SCALE) - 1, ry + ROW_H - 1, 0x22000000);
                        String date = formatDateYY(r.submittedAtMs()); String eng = normalizeEngine(r.engineName()); String modeShort = formatModePlusCount(r.modes());
                        int dateW = textRenderer.getWidth(date); DateHit dh = new DateHit(colDate, ry - 2, colDate + dateW, ry + 10, r.submittedAtMs()); dateHits.add(dh);
                        int modeW = textRenderer.getWidth(modeShort); ModeHit mh = new ModeHit(colMode, ry - 2, colMode + modeW, ry + 10, r.modes()); modeHits.add(mh);
                        String bodyLabel = TireUtil.composeBodyLabel(r.bodyName(), r.tireName()); int bodyW = textRenderer.getWidth(bodyLabel); TireHit th = new TireHit(colBody, ry - 2, colBody + bodyW, ry + 10, r.tireName()); tireHits.add(th);

                        if (dh.hit(smx, smy)) hoveredDateIndex = i;
                        if (th.hit(smx, smy)) hoveredTireIndex = i;
                        if (mh.hit(smx, smy)) hoveredModeText = "모드: " + r.modes();

                        context.drawTextWithShadow(textRenderer, date, colDate, ry, (dh.hit(smx, smy) ? 0xFFFFEE88 : 0xFFFFFF));
                        context.drawTextWithShadow(textRenderer, r.track(), colTrack, ry, 0xFFFFFF); context.drawTextWithShadow(textRenderer, r.timeStr(), colTime, ry, 0xFFFFFF); context.drawTextWithShadow(textRenderer, bodyLabel, colBody, ry, 0xFFFFFF); context.drawTextWithShadow(textRenderer, eng, colEngine, ry, 0xFFFFFF); context.drawTextWithShadow(textRenderer, modeShort, colMode, ry, 0xFFFFFF);
                    }
                    context.getMatrices().pop();
                } else {
                    if (loading) context.drawCenteredTextWithShadow(this.textRenderer, "불러오는 중...", this.width / 2, tableY + 30, 0xFFFFFF);
                    else if (error != null) context.drawCenteredTextWithShadow(this.textRenderer, "오류 발생", this.width / 2, tableY + 30, 0xFF5555);
                }
            }
        }

        if (!repPanelOpen && !achievementsExpanded && !showAchievementDesc) {
            int totalPages = Math.max(1, (records.size() + rowsPerPage - 1) / rowsPerPage);
            context.drawCenteredTextWithShadow(this.textRenderer, String.format("페이지 %d / %d", (page + 1), totalPages), this.width / 2, this.height - 26, 0xAAAAAA);
        }

        super.render(context, mouseX, mouseY, delta);
        if (repPanelOpen) renderRepDropdown(context, mouseX, mouseY);

        // ★ 최상단 툴팁 레이어
        if (hoveredDateIndex >= 0) drawTooltipBox(context, mouseX, mouseY, "등록: " + formatDateTimeFull(records.get(hoveredDateIndex).submittedAtMs()));
        else if (hoveredTireIndex >= 0) drawTooltipBox(context, mouseX, mouseY, TireUtil.tooltipName(records.get(hoveredTireIndex).tireName()));
        else if (hoveredModeText != null) drawTooltipBox(context, mouseX, mouseY, hoveredModeText);
    }

    private void renderRepDropdown(DrawContext context, int mouseX, int mouseY) {
        if (repToggleBtn == null) return;
        repPanelX = repToggleBtn.getX(); repPanelY = repToggleBtn.getY() + repToggleBtn.getHeight() + 2; repPanelW = 160;
        int rows = Math.min(profileAchievements.size(), REP_VISIBLE_ROWS); if (rows == 0) rows = 1; repPanelH = 12 + rows * ROW_H;
        context.fill(repPanelX, repPanelY, repPanelX + repPanelW, repPanelY + repPanelH, 0xEE0B0B0B); drawRectBorder(context, repPanelX, repPanelY, repPanelW, repPanelH, 0xFF666666);
        int startIndex = repScroll; int endIndex = Math.min(profileAchievements.size(), startIndex + REP_VISIBLE_ROWS);
        int rowY = repPanelY + 6;
        for (int i = startIndex; i < endIndex; i++) {
            Achievement ach = profileAchievements.get(i); boolean checked = ach.id().equals(repAchieveId);
            boolean hoverRow = mouseX >= repPanelX && mouseX < repPanelX + repPanelW && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hoverRow) context.fill(repPanelX + 1, rowY, repPanelX + repPanelW - 1, rowY + ROW_H, 0xFF1E1E1E);
            String text = ach.full(); if (!text.startsWith("[")) text = "[" + text + "]";
            int customColor = parseHex(ach.color(), 0xFFFFFF); int drawColor = hoverRow ? 0xFFFFFF : customColor;
            context.drawTextWithShadow(this.textRenderer, text, repPanelX + 6, rowY + (ROW_H - 8) / 2, drawColor);
            int CHECK_SIZE = 10; int boxX = repPanelX + repPanelW - 6 - CHECK_SIZE; int boxY = rowY + (ROW_H - CHECK_SIZE) / 2;
            context.fill(boxX, boxY, boxX + CHECK_SIZE, boxY + CHECK_SIZE, hoverRow ? 0xFF2A2A2A : 0xFF141414); drawRectBorder(context, boxX, boxY, CHECK_SIZE, CHECK_SIZE, 0xFFAAAAAA);
            if (checked) context.fill(boxX + 2, boxY + 2, boxX + CHECK_SIZE - 2, boxY + CHECK_SIZE - 2, 0xFF55FF55);
            rowY += ROW_H;
        }
        int maxScroll = Math.max(0, profileAchievements.size() - REP_VISIBLE_ROWS);
        if (maxScroll > 0) {
            int scrollW = 4; int barX = repPanelX + repPanelW - scrollW - 1; int barY = repPanelY + 1; int barH = repPanelH - 2;
            context.fill(barX, barY, barX + scrollW, barY + barH, 0xFF222222);
            float ratio = (float) REP_VISIBLE_ROWS / profileAchievements.size(); int thumbH = Math.max(10, (int)(barH * ratio));
            float posRatio = (float) repScroll / maxScroll; int thumbY = barY + (int)((barH - thumbH) * posRatio);
            context.fill(barX, thumbY, barX + scrollW, thumbY + thumbH, 0xFF888888);
        }
    }

    private static String formatDateYY(long ms) { if (ms <= 0) return "알 수 없음"; return new SimpleDateFormat("yy-MM-dd").format(new Date(ms)); }
    private static String formatDateTimeFull(long ms) { if (ms <= 0) return "알 수 없음"; return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ms)); }
    private static String normalizeEngine(String engineName) { if (engineName == null) return "UNKNOWN"; String s = engineName.trim(); if (s.startsWith("[") && s.endsWith("]") && s.length() >= 3) s = s.substring(1, s.length() - 1).trim(); s = s.replace("엔진", "").replace("ENGINE", "").replace("engine", "").trim(); if (s.isBlank()) return "UNKNOWN"; return s.toUpperCase(); }
    private static String formatModePlusCount(String modes) { if (modes == null) return "없음"; String s = modes.trim(); if (s.isBlank() || s.equals("없음")) return "없음"; String[] parts = s.split(","); int count = 0; for (String p : parts) { String v = p.trim(); if (!v.isEmpty() && !v.equals("없음")) count++; } return (count <= 0) ? "없음" : ("+" + count); }

    private void drawTooltipBox(DrawContext context, int mouseX, int mouseY, String text) {
        int pad = 6; int tw = this.textRenderer.getWidth(text); int th = 9; int x = mouseX + 10; int y = mouseY + 10;
        if (x + tw + pad * 2 > this.width - 4) x = mouseX - (tw + pad * 2) - 10; if (y + th + pad * 2 > this.height - 4) y = mouseY - (th + pad * 2) - 10;
        context.fill(x, y, x + tw + pad * 2, y + th + pad * 2, 0xEE000000); drawRectBorder(context, x, y, tw + pad * 2, th + pad * 2, 0xFF777777); context.drawTextWithShadow(this.textRenderer, text, x + pad, y + pad, 0xFFFFFF);
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) { context.fill(x, y, x + w, y + 1, color); context.fill(x, y + h - 1, x + w, y + h, color); context.fill(x, y, x + 1, y + h, color); context.fill(x + w - 1, y, x + w, y + h, color); }

    @Override public boolean shouldPause() { return false; }
    private record RecordRow(long submittedAtMs, String track, String timeStr, String bodyName, String tireName, String engineName, String modes) {}
}