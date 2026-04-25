package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

public class RankingScreen extends Screen {

    private static final int PAGE_SIZE_MAX = 10;
    private String track;
    private final List<Entry> ranking = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();
    private int page = 0;
    private boolean loading = true;
    private String error = null;

    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;
    private ButtonWidget tireToggleBtn;
    private ButtonWidget engineToggleBtn;
    private ButtonWidget trackSelectBtn;
    private ButtonWidget modeToggleBtn;

    private boolean tirePanelOpen = false;
    private final List<String> tires = new ArrayList<>();
    private String selectedTire = "ALL";

    private boolean enginePanelOpen = false;
    private final List<String> engines = new ArrayList<>();
    private String selectedEngine = "ALL";

    private boolean modePanelOpen = false;
    private static final List<String> FIXED_MODES = List.of("팀전", "무한 부스터 모드", "톡톡이 모드", "갓겜 모드", "벽 충돌 페널티");
    private final LinkedHashSet<String> selectedModes = new LinkedHashSet<>();

    private static final int OUTER_PAD = 12;
    private static final int HEADER_TOP = 10;
    private static final int BTN_H = 18;
    private static final int BTN_W_SMALL = 84;
    private static final int BTN_W_TRACK = 110;
    private static final int BTN_GAP = 6;
    private static final int PANEL_PAD = 6;
    private static final int ROW_H = 16;
    private static final int CHECK_SIZE = 12;
    private static final int ENGINE_PANEL_W = 150;
    private static final int TIRE_PANEL_W = 150;
    private static final int MODE_PANEL_W = 200;
    private static final int ENGINE_PANEL_MAX_H = 140;

    private int engineScroll = 0;
    private int engineVisibleRows = 5;
    private int headerH = 70;
    private int rowsPerPage = 6;

    private int tirePanelX, tirePanelY, tirePanelW, tirePanelH;
    private int enginePanelX, enginePanelY, enginePanelW, enginePanelH;
    private int modePanelX, modePanelY, modePanelW, modePanelH;

    // ===== 상세 정보창 관련 변수 =====
    private Entry selectedDetailEntry = null;
    private String selectedProfileDesc = "불러오는 중...";
    private ButtonWidget detailCloseBtn;
    private ButtonWidget detailProfileBtn;

    public static final String SUPABASE_URL = "https://wmlcwmfabuziancpxdoq.supabase.co/rest/v1/";
    public static final String SUPABASE_RPC_URL = SUPABASE_URL + "rpc/";
    public static final String SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndtbGN3bWZhYnV6aWFuY3B4ZG9xIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzU4ODEzMzQsImV4cCI6MjA5MTQ1NzMzNH0.0ZZJDv7qMRZzC7QdO2SYWApQ0ezSa-cx1M0aOawKe8M";

    public RankingScreen(String track) {
        super(Text.literal("랭킹"));
        this.track = sanitizeTrackOrDefault(track);
    }

    private static String sanitizeTrackOrDefault(String track) {
        if (track == null || track.isBlank()) {
            String def = ModConfig.get().defaultTrack;
            return (def == null || def.isBlank()) ? "[α] 빌리지 고가의 질주" : def;
        }
        return track.replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    private int parseHex(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            if (hex.startsWith("#")) hex = hex.substring(1);
            return 0xFF000000 | Integer.parseInt(hex, 16);
        } catch (Exception e) { return fallback; }
    }

    private void fetchProfileDesc(String playerName) {
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_player", playerName);
                JsonObject obj = RankingScreen.Net.postJson(SUPABASE_RPC_URL + "get_profile", req.toString());
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
        if (tireToggleBtn != null) tireToggleBtn.visible = !show;
        if (engineToggleBtn != null) engineToggleBtn.visible = !show;
        if (trackSelectBtn != null) trackSelectBtn.visible = !show;
        if (modeToggleBtn != null) modeToggleBtn.visible = !show;
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

        String t = (track == null || track.isBlank()) ? ModConfig.get().defaultTrack : track;
        context.drawTextWithShadow(textRenderer, "§8트랙: §f" + t, infoX, infoY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "§8기록: §e" + selectedDetailEntry.timeStr(), infoX, infoY + lineH, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "§8카트: §f" + TireUtil.composeBodyLabel(selectedDetailEntry.bodyName(), selectedDetailEntry.tireName()), infoX, infoY + lineH * 2, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "§8엔진: §f" + normalizeEngine(selectedDetailEntry.engineName()), infoX, infoY + lineH * 3, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "§8모드: §f" + (selectedDetailEntry.modes() == null || selectedDetailEntry.modes().isEmpty() ? "없음" : selectedDetailEntry.modes()), infoX, infoY + lineH * 4, 0xFFFFFF);

        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(selectedDetailEntry.submittedAtMs()));
        context.drawTextWithShadow(textRenderer, "§8등록: §7" + dateStr, infoX, infoY + lineH * 5, 0xAAAAAA);
    }

    @Override
    protected void init() {
        int iconBtnSize = 20;
        int cx = this.width / 2;
        boolean isSmall = this.width < 420;
        int pageBtnGap = isSmall ? 40 : 60;

        prevBtn = ButtonWidget.builder(Text.literal("◀"), b -> {
            playUiClick(); if (page > 0) page--; updateButtons();
        }).dimensions(cx - pageBtnGap - 10, this.height - 28, 20, 20).build();

        nextBtn = ButtonWidget.builder(Text.literal("▶"), b -> {
            playUiClick(); if ((page + 1) * rowsPerPage < filtered.size()) page++; updateButtons();
        }).dimensions(cx + pageBtnGap - 10, this.height - 28, 20, 20).build();

        addDrawableChild(prevBtn);
        addDrawableChild(nextBtn);

        addDrawableChild(ButtonWidget.builder(Text.literal("🔍"), b -> {
            playUiClick(); if (this.client != null) this.client.setScreen(new RiderFindScreen(this));
        }).dimensions(OUTER_PAD, this.height - 28, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("라이더 찾기"))).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("⏴"), b -> {
            playUiClick(); if (this.client != null) this.client.setScreen(new MainMenuScreen());
        }).dimensions(this.width - (iconBtnSize * 2) - OUTER_PAD - 5, this.height - 28, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("🔄"), b -> {
            playUiClick(); loading = true;
            ApiCache.fetchAllAsync(true, p -> { loading = false; applyFromAllPayload(); }, err -> { loading = false; error = err; });
        }).dimensions(this.width - iconBtnSize - OUTER_PAD, this.height - 28, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build());

        int btnY = HEADER_TOP + 48;
        tireToggleBtn = ButtonWidget.builder(getTireToggleText(), b -> {
            playUiClick(); tirePanelOpen = !tirePanelOpen;
            if (tirePanelOpen) { enginePanelOpen = false; modePanelOpen = false; }
            b.setMessage(getTireToggleText());
            if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
            if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());
            updateButtons();
        }).dimensions(0, btnY, BTN_W_SMALL, BTN_H).build();

        trackSelectBtn = ButtonWidget.builder(Text.literal("트랙 선택"), b -> {
            playUiClick(); if (this.client != null) this.client.setScreen(new TrackSelectScreen(this, this.track, this::setTrackAndApplyFromCache));
        }).dimensions(0, btnY, BTN_W_TRACK, BTN_H).build();

        engineToggleBtn = ButtonWidget.builder(getEngineToggleText(), b -> {
            playUiClick(); enginePanelOpen = !enginePanelOpen;
            if (enginePanelOpen) { modePanelOpen = false; tirePanelOpen = false; ensureEngineScrollForSelection(); }
            b.setMessage(getEngineToggleText());
            if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());
            if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
            updateButtons();
        }).dimensions(0, btnY, BTN_W_SMALL, BTN_H).build();

        modeToggleBtn = ButtonWidget.builder(getModeToggleText(), b -> {
            playUiClick(); modePanelOpen = !modePanelOpen;
            if (modePanelOpen) { enginePanelOpen = false; tirePanelOpen = false; }
            b.setMessage(getModeToggleText());
            if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
            if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
            updateButtons();
        }).dimensions(0, btnY, BTN_W_SMALL, BTN_H).build();

        addDrawableChild(tireToggleBtn);
        addDrawableChild(trackSelectBtn);
        addDrawableChild(engineToggleBtn);
        addDrawableChild(modeToggleBtn);

        // 상세창 관련 버튼 추가
        int detailW = 340;
        int detailH = 170;
        int detailX = cx - detailW / 2;
        int detailY = (this.height / 2) - detailH / 2 + 10;

        detailCloseBtn = ButtonWidget.builder(Text.literal("X"), b -> {
            playUiClick(); selectedDetailEntry = null; updateDetailButtons();
        }).dimensions(detailX + detailW - 24, detailY + 4, 20, 20).build();

        detailProfileBtn = ButtonWidget.builder(Text.literal("프로필 가기"), b -> {
            playUiClick();
            if (this.client != null && selectedDetailEntry != null) {
                this.client.setScreen(new PlayerProfileScreen(selectedDetailEntry.player(), this));
            }
        }).dimensions(detailX + 15, detailY + 50, 80, 20).build();

        addDrawableChild(detailCloseBtn);
        addDrawableChild(detailProfileBtn);
        detailCloseBtn.visible = false;
        detailProfileBtn.visible = false;

        if (ApiCache.isAllReady()) {
            loading = false; error = null; applyFromAllPayload();
        } else {
            loading = true; error = null;
            ApiCache.fetchAllAsync(false, p -> { loading = false; error = null; applyFromAllPayload(); }, err -> { loading = false; error = err; });
        }
        updateButtons();
        updateDetailButtons();
    }

    private void setTrackAndApplyFromCache(String newTrack) {
        this.track = sanitizeTrackOrDefault(newTrack);
        page = 0; ranking.clear(); filtered.clear(); tires.clear();
        selectedTire = "ALL"; tirePanelOpen = false; engines.clear();
        selectedEngine = "ALL"; enginePanelOpen = false; engineScroll = 0;
        selectedModes.clear(); modePanelOpen = false;

        if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
        if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
        if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());

        if (!ApiCache.isAllReady()) { loading = true; error = null; updateButtons(); return; }
        loading = false; error = null; applyFromAllPayload(); updateButtons();
    }

    private Text getTireToggleText() { return Text.literal("타이어 " + (tirePanelOpen ? "▾" : "▸") + " : " + (selectedTire.equalsIgnoreCase("ALL") ? "전체" : selectedTire)); }
    private Text getEngineToggleText() { return Text.literal("엔진 " + (enginePanelOpen ? "▾" : "▸") + " : " + (selectedEngine.equalsIgnoreCase("ALL") ? "전체" : selectedEngine)); }
    private Text getModeToggleText() {
        String show = selectedModes.isEmpty() ? "없음" : selectedModes.size() == 1 ? selectedModes.iterator().next() : new ArrayList<>(selectedModes).get(0) + " +" + (selectedModes.size() - 1);
        return Text.literal("모드 " + (modePanelOpen ? "▾" : "▸") + " : " + show);
    }

    private void updateButtons() {
        computeLayout();
        if (prevBtn != null) prevBtn.active = (page > 0);
        if (nextBtn != null) nextBtn.active = (!filtered.isEmpty() && (page + 1) * rowsPerPage < filtered.size());
    }

    private void playUiClick() { if (this.client != null) this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f)); }

    private void applyFromAllPayload() {
        ApiCache.AllPayload all = ApiCache.getAllIfReady();
        if (all == null) return;
        ApiCache.RankingPayload rp = all.rankingsByTrack.get(track);
        if (rp == null) { ranking.clear(); filtered.clear(); engines.clear(); tires.clear(); updateButtons(); return; }
        applyRankingPayload(rp); updateButtons();
    }

    private void applyRankingPayload(ApiCache.RankingPayload payload) {
        ranking.clear(); filtered.clear(); engines.clear(); tires.clear();
        ranking.addAll(payload.ranking);

        // ===== 타이어 세팅 =====
        LinkedHashSet<String> tireSet = new LinkedHashSet<>(); tireSet.add("ALL");
        for (Entry e : ranking) tireSet.add((e.tireName() == null || e.tireName().isBlank()) ? "UNKNOWN" : e.tireName().trim());
        tires.addAll(tireSet);

        tires.removeIf(Objects::isNull);
        tires.replaceAll(s -> s == null ? "UNKNOWN" : s.trim());
        tires.removeIf(String::isBlank);
        tires.sort((a, b) -> a.equals("ALL") ? -1 : b.equals("ALL") ? 1 : a.equalsIgnoreCase("UNKNOWN") ? 1 : b.equalsIgnoreCase("UNKNOWN") ? -1 : a.compareTo(b));

        // ===== 엔진 세팅 (빈도수 기반 정렬) =====
        Map<String, Integer> engineCounts = new HashMap<>();
        for (Entry e : ranking) {
            String eng = normalizeEngine(e.engineName());
            engineCounts.put(eng, engineCounts.getOrDefault(eng, 0) + 1);
        }

        engines.clear();
        engines.add("ALL"); // 항상 최상단에 '전체' 배치

        List<String> sortedEngines = new ArrayList<>(engineCounts.keySet());
        sortedEngines.sort((a, b) -> {
            int countDiff = Integer.compare(engineCounts.get(b), engineCounts.get(a)); // 가장 많이 쓰인 순 (내림차순)
            if (countDiff != 0) return countDiff;
            return a.compareTo(b); // 개수가 같으면 알파벳 순
        });
        engines.addAll(sortedEngines);

        // ===== 초기화 및 필터 적용 =====
        selectedTire = "ALL"; selectedEngine = "ALL"; selectedModes.clear();
        ranking.sort(Comparator.comparingLong(Entry::timeMillis));
        applyFilter(); filtered.sort(Comparator.comparingLong(Entry::timeMillis));
        page = 0; engineScroll = 0; ensureEngineScrollForSelection();
    }

    private void applyFilter() {
        filtered.clear();
        for (Entry e : ranking) {
            String eng = normalizeEngine(e.engineName());
            Set<String> entryModes = parseModeSet(e.modes());
            String tire = (e.tireName() == null || e.tireName().isBlank()) ? "UNKNOWN" : e.tireName().trim();
            boolean okTire = selectedTire.equalsIgnoreCase("ALL") || selectedTire.equalsIgnoreCase(tire);
            boolean okEngine = selectedEngine.equalsIgnoreCase("ALL") || selectedEngine.equalsIgnoreCase(eng);
            boolean okMode = selectedModes.isEmpty() ? entryModes.isEmpty() : entryModes.equals(selectedModes);
            if (okTire && okEngine && okMode) filtered.add(e);
        }
        computeLayout();
        int maxPage = Math.max(0, (filtered.size() - 1) / rowsPerPage);
        if (page > maxPage) page = maxPage;
    }

    private static String normalizeEngine(String engineName) {
        if (engineName == null) return "UNKNOWN";
        String s = engineName.trim();
        if (s.startsWith("[") && s.endsWith("]") && s.length() >= 3) s = s.substring(1, s.length() - 1).trim();
        s = s.replace("엔진", "").replace("ENGINE", "").replace("engine", "").trim();
        return s.isBlank() ? "UNKNOWN" : s.toUpperCase();
    }

    private static Set<String> parseModeSet(String modesCsv) {
        if (modesCsv == null) return Collections.emptySet();
        String s = modesCsv.trim();
        if (s.isBlank() || s.equals("없음")) return Collections.emptySet();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String p : s.split(",")) { String v = p.trim(); if (!v.isEmpty() && !v.equals("없음")) set.add(v); }
        return set;
    }

    private void computeLayout() {
        int base = 66; int panelExtra = 0;
        if (tirePanelOpen) panelExtra = Math.max(panelExtra, Math.min(PANEL_PAD * 2 + Math.max(1, tires.size()) * ROW_H, 140) + 10);
        if (enginePanelOpen) panelExtra = Math.max(panelExtra, Math.min(PANEL_PAD * 2 + Math.max(1, engines.size()) * ROW_H, ENGINE_PANEL_MAX_H) + 10);
        if (modePanelOpen) panelExtra = Math.max(panelExtra, PANEL_PAD * 2 + FIXED_MODES.size() * ROW_H + 10);
        headerH = base + panelExtra;
        int tableTop = HEADER_TOP + headerH + 10;
        rowsPerPage = Math.min(PAGE_SIZE_MAX, Math.max(1, (Math.max(80, this.height - 46 - tableTop) - 18 - 26) / 18));
    }

    private void renderHeader(DrawContext context) {
        computeLayout();
        int x = OUTER_PAD, y = HEADER_TOP, w = this.width - OUTER_PAD * 2, h = headerH;
        context.fill(x, y, x + w, y + h, 0xCC000000);
        drawRectBorder(context, x, y, w, h, 0xFF2A2A2A);

        String tr = (track == null || track.isBlank()) ? ModConfig.get().defaultTrack : track;
        context.drawCenteredTextWithShadow(this.textRenderer, "TRACK", this.width / 2, y + 10, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, tr, this.width / 2, y + 24, 0xFFDDAA);

        String tire = selectedTire.equalsIgnoreCase("ALL") ? "전체" : selectedTire;
        String eng = selectedEngine.equalsIgnoreCase("ALL") ? "전체" : selectedEngine;
        String mode = selectedModes.isEmpty() ? "없음" : String.join(", ", selectedModes);
        context.drawCenteredTextWithShadow(this.textRenderer, "타이어: " + tire + "   |   엔진: " + eng + "   |   모드: " + mode, this.width / 2, y + 38, 0xBBBBBB);

        int btnY = y + 48;
        int startX = this.width / 2 - ((BTN_W_SMALL * 3) + BTN_W_TRACK + ((this.width < 400 ? 4 : BTN_GAP) * 3)) / 2;
        int gap = this.width < 400 ? 4 : BTN_GAP;

        if (tireToggleBtn != null) tireToggleBtn.setPosition(startX, btnY);
        if (trackSelectBtn != null) trackSelectBtn.setPosition(startX + BTN_W_SMALL + gap, btnY);
        if (engineToggleBtn != null) engineToggleBtn.setPosition(startX + BTN_W_SMALL + BTN_W_TRACK + gap * 2, btnY);
        if (modeToggleBtn != null) modeToggleBtn.setPosition(startX + BTN_W_SMALL * 2 + BTN_W_TRACK + gap * 3, btnY);

        if (tirePanelOpen && tireToggleBtn != null) drawActiveButtonGlow(context, tireToggleBtn);
        if (enginePanelOpen && engineToggleBtn != null) drawActiveButtonGlow(context, engineToggleBtn);
        if (modePanelOpen && modeToggleBtn != null) drawActiveButtonGlow(context, modeToggleBtn);
    }

    private void drawActiveButtonGlow(DrawContext context, ButtonWidget btn) {
        context.fill(btn.getX() - 2, btn.getY() - 2, btn.getX() + btn.getWidth() + 2, btn.getY() + btn.getHeight(), 0x5533FF33);
        drawRectBorder(context, btn.getX() - 2, btn.getY() - 2, btn.getWidth() + 4, btn.getHeight() + 2, 0xFF66FF66);
    }

    private void ensureEngineScrollForSelection() {
        if (engines.isEmpty()) { engineScroll = 0; return; }
        int idx = engines.indexOf(selectedEngine);
        if (idx < 0) { engineScroll = 0; return; }
        int maxScroll = Math.max(0, engines.size() - Math.max(1, engineVisibleRows));
        if (idx < engineScroll) engineScroll = idx;
        else if (idx >= engineScroll + Math.max(1, engineVisibleRows)) engineScroll = idx - Math.max(1, engineVisibleRows) + 1;
        engineScroll = Math.clamp(engineScroll, 0, maxScroll);
    }

    private void renderTireDropdown(DrawContext context, int mouseX, int mouseY) {
        if (tireToggleBtn == null) return;
        tirePanelX = tireToggleBtn.getX(); tirePanelY = tireToggleBtn.getY() + BTN_H + 8; tirePanelW = TIRE_PANEL_W;
        tirePanelH = Math.min(PANEL_PAD * 2 + Math.max(1, tires.size()) * ROW_H, 140);
        context.fill(tirePanelX, tirePanelY, tirePanelX + tirePanelW, tirePanelY + tirePanelH, 0xEE0B0B0B);
        drawRectBorder(context, tirePanelX, tirePanelY, tirePanelW, tirePanelH, 0xFF444444);
        int y = tirePanelY + PANEL_PAD;
        for (String t : tires) {
            boolean hover = mouseX >= tirePanelX && mouseX < tirePanelX + tirePanelW && mouseY >= y && mouseY < y + ROW_H;
            if (hover) context.fill(tirePanelX + 1, y, tirePanelX + tirePanelW - 1, y + ROW_H, 0xFF1C1C1C);
            if (t.equalsIgnoreCase(selectedTire)) context.fill(tirePanelX + 1, y, tirePanelX + tirePanelW - 1, y + ROW_H, 0xFF153015);
            context.drawTextWithShadow(this.textRenderer, t.equals("ALL") ? "전체" : t, tirePanelX + PANEL_PAD, y + (ROW_H - 8) / 2, 0xFFFFFF);
            y += ROW_H; if (y > tirePanelY + tirePanelH - PANEL_PAD) break;
        }
    }

    private void renderEngineDropdown(DrawContext context, int mouseX, int mouseY) {
        if (engineToggleBtn == null) return;
        enginePanelX = engineToggleBtn.getX(); enginePanelY = engineToggleBtn.getY() + BTN_H + 8; enginePanelW = ENGINE_PANEL_W;
        enginePanelH = Math.min(PANEL_PAD * 2 + Math.max(1, engines.size()) * ROW_H, ENGINE_PANEL_MAX_H);
        engineVisibleRows = Math.max(1, (enginePanelH - PANEL_PAD * 2) / ROW_H);
        int maxScroll = Math.max(0, engines.size() - engineVisibleRows);
        engineScroll = Math.clamp(engineScroll, 0, maxScroll);
        context.fill(enginePanelX, enginePanelY, enginePanelX + enginePanelW, enginePanelY + enginePanelH, 0xEE0B0B0B);
        drawRectBorder(context, enginePanelX, enginePanelY, enginePanelW, enginePanelH, 0xFF444444);
        int scrollBarW = (maxScroll > 0) ? 8 : 0;
        int listW = enginePanelW - scrollBarW;
        int y = enginePanelY + PANEL_PAD;
        for (int idx = engineScroll; idx < Math.min(engines.size(), engineScroll + engineVisibleRows); idx++) {
            boolean hover = mouseX >= enginePanelX && mouseX < enginePanelX + listW && mouseY >= y && mouseY < y + ROW_H;
            if (hover) context.fill(enginePanelX + 1, y, enginePanelX + listW - 1, y + ROW_H, 0xFF1C1C1C);
            if (engines.get(idx).equalsIgnoreCase(selectedEngine)) context.fill(enginePanelX + 1, y, enginePanelX + listW - 1, y + ROW_H, 0xFF153015);
            context.drawTextWithShadow(this.textRenderer, engines.get(idx).equals("ALL") ? "전체" : engines.get(idx), enginePanelX + PANEL_PAD, y + (ROW_H - 8) / 2, 0xFFFFFF);
            y += ROW_H;
        }
        if (maxScroll > 0) {
            int barX = enginePanelX + enginePanelW - scrollBarW;
            context.fill(barX, enginePanelY + 1, barX + scrollBarW, enginePanelY + enginePanelH - 1, 0xFF111111);
            int thumbH = Math.max(12, (int) ((enginePanelH - 2) * (engineVisibleRows / (float) engines.size())));
            int thumbY = enginePanelY + 1 + (int) (((enginePanelH - 2) - thumbH) * (engineScroll / (float) maxScroll));
            context.fill(barX + 1, thumbY, barX + scrollBarW - 1, thumbY + thumbH, 0xFF3A3A3A);
            drawRectBorder(context, barX + 1, thumbY, scrollBarW - 2, thumbH, 0xFF777777);
        }
    }

    private void renderModeDropdown(DrawContext context, int mouseX, int mouseY) {
        if (modeToggleBtn == null) return;
        modePanelW = MODE_PANEL_W;
        modePanelX = modeToggleBtn.getX() + modeToggleBtn.getWidth() - modePanelW;
        modePanelY = modeToggleBtn.getY() + BTN_H + 8;
        modePanelH = PANEL_PAD * 2 + FIXED_MODES.size() * ROW_H;
        context.fill(modePanelX, modePanelY, modePanelX + modePanelW, modePanelY + modePanelH, 0xEE0B0B0B);
        drawRectBorder(context, modePanelX, modePanelY, modePanelW, modePanelH, 0xFF666666);
        int rowY = modePanelY + PANEL_PAD;
        for (String mode : FIXED_MODES) {
            boolean checked = selectedModes.contains(mode);
            boolean hoverRow = mouseX >= modePanelX && mouseX < modePanelX + modePanelW && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hoverRow) context.fill(modePanelX + 1, rowY, modePanelX + modePanelW - 1, rowY + ROW_H, 0xFF1E1E1E);
            context.drawTextWithShadow(this.textRenderer, mode, modePanelX + PANEL_PAD, rowY + (ROW_H - 8) / 2, 0xFFFFFF);
            int boxX = modePanelX + modePanelW - PANEL_PAD - CHECK_SIZE;
            int boxY = rowY + (ROW_H - CHECK_SIZE) / 2;
            boolean hoverBox = mouseX >= boxX && mouseX < boxX + CHECK_SIZE && mouseY >= boxY && mouseY < boxY + CHECK_SIZE;
            int bg = (hoverRow || hoverBox) ? 0xFF2A2A2A : 0xFF141414;
            context.fill(boxX, boxY, boxX + CHECK_SIZE, boxY + CHECK_SIZE, bg);
            drawRectBorder(context, boxX, boxY, CHECK_SIZE, CHECK_SIZE, 0xFFAAAAAA);
            if (checked) context.fill(boxX + 3, boxY + 3, boxX + CHECK_SIZE - 3, boxY + CHECK_SIZE - 3, 0xFF55FF55);
            rowY += ROW_H;
        }
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (enginePanelOpen && mouseX >= enginePanelX && mouseX < enginePanelX + enginePanelW && mouseY >= enginePanelY && mouseY < enginePanelY + enginePanelH) {
            int maxScroll = Math.max(0, engines.size() - engineVisibleRows);
            if (verticalAmount > 0) engineScroll--; else if (verticalAmount < 0) engineScroll++;
            engineScroll = Math.clamp(engineScroll, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tirePanelOpen) {
            if (mouseX < tirePanelX || mouseX >= tirePanelX + tirePanelW || mouseY < tirePanelY || mouseY >= tirePanelY + tirePanelH) {
                tirePanelOpen = false; if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText()); updateButtons(); return true;
            }
            int y = tirePanelY + PANEL_PAD;
            for (String tire : tires) {
                if (mouseY >= y && mouseY < y + ROW_H) {
                    selectedTire = tire; playUiClick(); tirePanelOpen = false; page = 0; applyFilter(); updateButtons();
                    if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText()); return true;
                }
                y += ROW_H; if (y > tirePanelY + tirePanelH - PANEL_PAD) break;
            }
            return true;
        }
        if (enginePanelOpen) {
            if (mouseX < enginePanelX || mouseX >= enginePanelX + enginePanelW || mouseY < enginePanelY || mouseY >= enginePanelY + enginePanelH) {
                enginePanelOpen = false; if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText()); updateButtons(); return true;
            }
            int y = enginePanelY + PANEL_PAD;
            for (int idx = engineScroll; idx < Math.min(engines.size(), engineScroll + engineVisibleRows); idx++) {
                if (mouseY >= y && mouseY < y + ROW_H) {
                    selectedEngine = engines.get(idx); playUiClick(); enginePanelOpen = false; page = 0; applyFilter(); updateButtons();
                    if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText()); return true;
                }
                y += ROW_H;
            }
            return true;
        }
        if (modePanelOpen) {
            if (mouseX < modePanelX || mouseX >= modePanelX + modePanelW || mouseY < modePanelY || mouseY >= modePanelY + modePanelH) {
                modePanelOpen = false; if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText()); updateButtons(); return true;
            }
            int rowY = modePanelY + PANEL_PAD;
            for (String mode : FIXED_MODES) {
                if (mouseX >= modePanelX && mouseX < modePanelX + modePanelW && mouseY >= rowY && mouseY < rowY + ROW_H) {
                    if (selectedModes.contains(mode)) selectedModes.remove(mode); else selectedModes.add(mode);
                    playUiClick(); page = 0; applyFilter(); updateButtons();
                    if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText()); return true;
                }
                rowY += ROW_H;
            }
            return true;
        }

        // 행 전체 클릭을 통한 상세창 열기
        if (!loading && error == null && !filtered.isEmpty() && selectedDetailEntry == null) {
            computeLayout();
            int tableTop = HEADER_TOP + headerH + 10;
            int tableX = OUTER_PAD + 8;
            int tableW = this.width - (OUTER_PAD + 8) * 2;
            int startY = tableTop + 24;
            int rowH = 18;

            int start = page * rowsPerPage;
            int end = Math.min(start + rowsPerPage, filtered.size());

            for (int i = start; i < end; i++) {
                Entry e = filtered.get(i);
                int y = startY + (i - start) * rowH;

                if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= y - 2 && mouseY <= y + rowH - 2) {
                    playUiClick();
                    selectedDetailEntry = e;
                    selectedProfileDesc = "불러오는 중...";
                    updateDetailButtons();
                    fetchProfileDesc(e.player());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        renderHeader(context);

        if (tirePanelOpen) renderTireDropdown(context, mouseX, mouseY);
        if (enginePanelOpen) renderEngineDropdown(context, mouseX, mouseY);
        if (modePanelOpen) renderModeDropdown(context, mouseX, mouseY);

        // 상세창이 활성화되면 랭킹 테이블 대신 상세창을 그립니다.
        if (selectedDetailEntry != null) {
            renderDetailBox(context);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        int tableTop = HEADER_TOP + headerH + 10;
        int tableX = OUTER_PAD + 8;
        int tableW = this.width - (OUTER_PAD + 8) * 2;
        int tableBottom = this.height - 46;
        int tableH = Math.max(80, tableBottom - tableTop);

        int colRankX   = tableX + (int)(tableW * 0.02);
        int colPlayerX = tableX + (int)(tableW * 0.15);
        int colTimeX   = tableX + (int)(tableW * 0.40);
        int colBodyX   = tableX + (int)(tableW * 0.60);
        int colEngineX = tableX + (int)(tableW * 0.85);

        boolean showTime   = tableW > 250;
        boolean showBody   = tableW > 320;
        boolean showEngine = tableW > 380;

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer, "불러오는 중...", this.width / 2, tableTop + 26, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta); return;
        }
        if (error != null) {
            String displayError = error.toLowerCase().contains("http") ? "이 버전은 서비스 종료 되었습니다. 최신 버전을 이용해 주세요." : error;
            context.drawCenteredTextWithShadow(this.textRenderer, "오류: " + displayError, this.width / 2, tableTop + 26, 0xFF5555);
            super.render(context, mouseX, mouseY, delta); return;
        }

        context.fill(tableX, tableTop, tableX + tableW, tableTop + tableH, 0x66000000);
        drawRectBorder(context, tableX, tableTop, tableW, tableH, 0xFF222222);

        int headerRowY = tableTop + 8;
        context.drawTextWithShadow(this.textRenderer, "순위", colRankX, headerRowY, 0xDDDDDD);
        context.drawTextWithShadow(this.textRenderer, "플레이어", colPlayerX, headerRowY, 0xDDDDDD);
        if (showTime) context.drawTextWithShadow(this.textRenderer, "기록", colTimeX, headerRowY, 0xDDDDDD);
        if (showBody) context.drawTextWithShadow(this.textRenderer, "카트바디", colBodyX, headerRowY, 0xDDDDDD);
        if (showEngine) context.drawTextWithShadow(this.textRenderer, "엔진", colEngineX, headerRowY, 0xDDDDDD);

        int startY = tableTop + 24;
        int rowH = 18;
        int start = page * rowsPerPage;
        int end = Math.min(start + rowsPerPage, filtered.size());

        String myName = MinecraftClient.getInstance().getSession().getUsername();

        for (int i = start; i < end; i++) {
            Entry e = filtered.get(i);
            int y = startY + (i - start) * rowH;
            int rank = i + 1;
            boolean isMe = e.player().equalsIgnoreCase(myName);

            if (isMe) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + rowH - 1, 0x6644AA44);
            else if (rank == 1) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + rowH - 1, 0x44FFD700);
            else if (rank == 2) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + rowH - 1, 0x44C0C0C0);
            else if (rank == 3) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + rowH - 1, 0x44CD7F32);
            else if (((i - start) & 1) == 1) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + rowH - 1, 0x22000000);

            // 마우스 오버 효과 (행 전체)
            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= y - 2 && mouseY <= y + rowH - 2) {
                context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + rowH - 1, 0x33FFFFFF);
            }

            int rankColor = (rank == 1) ? 0xFFFFE066 : (rank == 2) ? 0xFFE6E6E6 : (rank == 3) ? 0xFFFFB36B : 0xFFFFFFFF;
            context.drawTextWithShadow(this.textRenderer, rank + "위", colRankX, y, rankColor);

            String displayPlayer = e.player();
            String repText = "";
            if (e.repTitle() != null && !e.repTitle().isEmpty()) {
                repText = " [" + e.repTitle() + "]";
                displayPlayer += repText;
            }

            if (repText.isEmpty()) {
                context.drawTextWithShadow(this.textRenderer, e.player(), colPlayerX, y, 0xFFFFFF);
            } else {
                context.drawTextWithShadow(this.textRenderer, e.player(), colPlayerX, y, 0xFFFFFF);
                context.drawTextWithShadow(this.textRenderer, repText, colPlayerX + this.textRenderer.getWidth(e.player()), y, parseHex(e.repColor(), 0x55FFFF));
            }

            boolean hiddenSomething = false;
            if (showTime) context.drawTextWithShadow(this.textRenderer, e.timeStr(), colTimeX, y, 0xFFFFFF); else hiddenSomething = true;
            if (showBody) context.drawTextWithShadow(this.textRenderer, TireUtil.composeBodyLabel(e.bodyName(), e.tireName()), colBodyX, y, 0xFFFFFF); else hiddenSomething = true;
            if (showEngine) context.drawTextWithShadow(this.textRenderer, normalizeEngine(e.engineName()), colEngineX, y, 0xFFFFFF); else hiddenSomething = true;
            if (hiddenSomething) context.drawTextWithShadow(this.textRenderer, "+", tableX + tableW - 20, y, 0xAAAAAA);
        }

        int totalPages = Math.max(1, (filtered.size() + rowsPerPage - 1) / rowsPerPage);
        context.drawCenteredTextWithShadow(this.textRenderer, String.format("페이지 %d / %d", (page + 1), totalPages), this.width / 2, this.height - 26, 0xAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    @Override public boolean shouldPause() { return false; }
    public record Entry(String player, String repTitle, String repColor, String timeStr, long timeMillis, String engineName, String bodyName, String tireName, String modes, long submittedAtMs) {}

    private static String safeString(JsonObject o, String key, String def) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def; }
    private static long safeLong(JsonObject o, String key, long def) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : def; }
    private static int safeInt(JsonObject o, String key, int def) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def; }

    public static final class ApiCache {
        private ApiCache() {}
        public static final class RankingPayload { public final List<Entry> ranking; public final List<String> engines; public RankingPayload(List<Entry> ranking, List<String> engines) { this.ranking = ranking; this.engines = engines; } }
        public static final class AllPayload { public final List<TrackSelectScreen.TrackEntry> tracks; public final Map<String, RankingPayload> rankingsByTrack; public final long fetchedAtMs; public AllPayload(List<TrackSelectScreen.TrackEntry> tracks, Map<String, RankingPayload> rankingsByTrack, long fetchedAtMs) { this.tracks = tracks; this.rankingsByTrack = rankingsByTrack; this.fetchedAtMs = fetchedAtMs; } }

        private static volatile AllPayload cachedAll = null;
        private static volatile boolean fetching = false;
        private static final List<Runnable> waiters = new ArrayList<>();

        public static boolean isAllReady() { return getAllIfReady() != null; }
        public static AllPayload getAllIfReady() { AllPayload p = cachedAll; if (p == null) return null; if (System.currentTimeMillis() - p.fetchedAtMs > ModConfig.get().getCacheTtlMs()) return null; return p; }
        public static void clearCache() { cachedAll = null; }

        public static void fetchAllAsync(boolean force, Consumer<AllPayload> onDone, Consumer<String> onError) {
            if (!force) { AllPayload fresh = getAllIfReady(); if (fresh != null) { onDone.accept(fresh); return; } }
            synchronized (ApiCache.class) { if (fetching) { waiters.add(() -> onDone.accept(getAllIfReady())); return; } fetching = true; }

            new Thread(() -> {
                String err = null; AllPayload payload = null;
                try {
                    JsonObject res = Net.postJson(SUPABASE_RPC_URL + "get_all_rankings", "{}");
                    if (!res.has("ok") || !res.get("ok").getAsBoolean()) { err = safeString(res, "error", "unknown error"); }
                    else {
                        List<TrackSelectScreen.TrackEntry> tracks = new ArrayList<>();
                        if (res.has("tracks") && res.get("tracks").isJsonArray()) {
                            JsonArray arr = res.getAsJsonArray("tracks");
                            for (int i = 0; i < arr.size(); i++) {
                                JsonObject o = arr.get(i).getAsJsonObject();
                                tracks.add(new TrackSelectScreen.TrackEntry(safeString(o, "track", "Unknown"), safeInt(o, "count", 0)));
                            }
                            tracks.sort(Comparator.comparingInt(TrackSelectScreen.TrackEntry::count).reversed());
                        }

                        Map<String, RankingPayload> map = new HashMap<>();
                        if (res.has("rankings") && res.get("rankings").isJsonObject()) {
                            JsonObject robj = res.getAsJsonObject("rankings");
                            for (Map.Entry<String, JsonElement> it : robj.entrySet()) {
                                JsonObject v = it.getValue().getAsJsonObject();
                                List<String> engines = new ArrayList<>();
                                if (v.has("engines") && v.get("engines").isJsonArray()) {
                                    JsonArray earr = v.getAsJsonArray("engines");
                                    for (int j = 0; j < earr.size(); j++) { if (!earr.get(j).isJsonNull()) { String e = earr.get(j).getAsString(); if (e != null && !e.isBlank()) engines.add(e.trim().toUpperCase()); } }
                                }
                                List<Entry> list = new ArrayList<>();
                                if (v.has("ranking") && v.get("ranking").isJsonArray()) {
                                    JsonArray rarr = v.getAsJsonArray("ranking");
                                    for (int j = 0; j < rarr.size(); j++) {
                                        JsonObject o = rarr.get(j).getAsJsonObject();
                                        list.add(new Entry(safeString(o, "player", "Unknown"), safeString(o, "repTitle", ""), safeString(o, "repColor", "#55FFFF"), safeString(o, "time", "00:00.000"), safeLong(o, "timeMillis", 0L), safeString(o, "engineName", "UNKNOWN"), safeString(o, "bodyName", "UNKNOWN"), safeString(o, "tireName", "UNKNOWN"), safeString(o, "modes", "없음"), safeLong(o, "submittedAtMs", 0L)));
                                    }
                                }
                                list.sort(Comparator.comparingLong(Entry::timeMillis));
                                map.put(it.getKey(), new RankingPayload(list, engines));
                            }
                        }
                        payload = new AllPayload(tracks, map, System.currentTimeMillis());
                    }
                } catch (Exception e) { err = e.getMessage(); }

                final String ferr = err; final AllPayload fp = payload;
                MinecraftClient.getInstance().execute(() -> {
                    synchronized (ApiCache.class) { fetching = false; if (fp != null) cachedAll = fp; for (Runnable r : waiters) r.run(); waiters.clear(); }
                    if (ferr != null) onError.accept(ferr); else onDone.accept(fp);
                });
            }, "Supabase-getAll-Fetch").start();
        }
    }

    public static final class Net {
        private Net() {}
        public static JsonObject postJson(String url, String jsonBody) throws Exception {
            URI uri = URI.create(url);
            HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();
            con.setRequestMethod("POST"); con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("apikey", SUPABASE_KEY); con.setRequestProperty("Authorization", "Bearer " + SUPABASE_KEY);
            con.setDoOutput(true); con.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
            try (InputStreamReader reader = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        }
    }
}