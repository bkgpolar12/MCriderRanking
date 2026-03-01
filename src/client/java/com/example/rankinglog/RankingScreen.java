// RankingScreen.java
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

    // ✅ 필터 버튼(재배치)
    private ButtonWidget tireToggleBtn;
    private ButtonWidget engineToggleBtn;
    private ButtonWidget trackSelectBtn;
    private ButtonWidget modeToggleBtn;

    // ===== 타이어 (드롭다운 단일선택) =====
    private boolean tirePanelOpen = false;
    private final List<String> tires = new ArrayList<>(); // "ALL" 포함, 나머지 이름
    private String selectedTire = "ALL";

    // ===== 엔진 (드롭다운 단일선택) =====
    private boolean enginePanelOpen = false;
    private final List<String> engines = new ArrayList<>();
    private String selectedEngine = "ALL";

    // ===== 모드 (드롭다운 체크박스) =====
    private boolean modePanelOpen = false;

    private static final List<String> FIXED_MODES = List.of(
            "팀전",
            "무한 부스터 모드",
            "톡톡이 모드",
            "갓겜 모드",
            "벽 충돌 페널티"
    );

    private final LinkedHashSet<String> selectedModes = new LinkedHashSet<>();

    // ===== 레이아웃 =====
    private static final String DEFAULT_TRACK = "[α] 빌리지 고가의 질주";

    private static final int OUTER_PAD = 12;
    private static final int HEADER_TOP = 10;

    // ✅ 버튼 컴팩트
    private static final int BTN_H = 18;

    // ✅ 요청: 타이어/엔진/모드 버튼 너비를 "지금의 70%"로 (120 -> 84)
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

    // 드롭다운 영역(클릭 판정용)
    private int tirePanelX, tirePanelY, tirePanelW, tirePanelH;
    private int enginePanelX, enginePanelY, enginePanelW, enginePanelH;
    private int modePanelX, modePanelY, modePanelW, modePanelH;

    // body tooltip hit
    private static final class BodyHit {
        final int x1, y1, x2, y2;
        final String tireName;
        BodyHit(int x1, int y1, int x2, int y2, String tireName) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.tireName = tireName;
        }
        boolean hit(int mx, int my) { return mx >= x1 && mx <= x2 && my >= y1 && my <= y2; }
    }
    private final List<BodyHit> bodyHits = new ArrayList<>();

    public static final String GAS_URL =
            "https://script.google.com/macros/s/AKfycbzdrUnaArFkn2DUIx0mxmp9peLBKQYb7YX8wEZxwMVodX4V1NUh-plQ-6jSDI3xnzEw/exec";

    public RankingScreen(String track) {
        super(Text.literal("랭킹"));
        this.track = sanitizeTrackOrDefault(track);
    }

    private static String sanitizeTrackOrDefault(String track) {
        if (track == null || track.isBlank()) return DEFAULT_TRACK;
        return track.replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // 페이지 버튼(아래 중앙)
        prevBtn = ButtonWidget.builder(Text.literal("◀"), b -> {
            playUiClick();
            if (page > 0) page--;
            updateButtons();
        }).dimensions(cx - 70, this.height - 28, 20, 20).build();

        nextBtn = ButtonWidget.builder(Text.literal("▶"), b -> {
            playUiClick();
            if ((page + 1) * rowsPerPage < filtered.size()) page++;
            updateButtons();
        }).dimensions(cx + 50, this.height - 28, 20, 20).build();

        addDrawableChild(prevBtn);
        addDrawableChild(nextBtn);

        //왼쪽 하단: 라이더 찾기
        //왼쪽 하단 라이더 찾기
        ButtonWidget riderFindBtn = ButtonWidget.builder(Text.literal("라이더 찾기"), b -> {
            playUiClick();
            if (this.client != null) {
                this.client.setScreen(new RiderFindScreen(this));
            }
        }).dimensions(OUTER_PAD + 8, this.height - 28, 90, 20).build();
        addDrawableChild(riderFindBtn);

        // ✅ 버튼 줄(위치: renderHeader에서 매프레임 갱신)
        int btnY = HEADER_TOP + 44;

        // 좌측(타이어)
        int leftX = OUTER_PAD + 8;

        // 중앙(트랙)
        int trackX = cx - (BTN_W_TRACK / 2);

        // 우측(엔진+모드 같이, 같은 y축)
        int engineX = this.width - OUTER_PAD - 8 - (BTN_W_SMALL * 2 + BTN_GAP);
        int modeX = engineX + BTN_W_SMALL + BTN_GAP;

        tireToggleBtn = ButtonWidget.builder(getTireToggleText(), b -> {
            playUiClick();
            tirePanelOpen = !tirePanelOpen;
            if (tirePanelOpen) {
                enginePanelOpen = false;
                modePanelOpen = false;
            }
            b.setMessage(getTireToggleText());
            if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
            if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());
            updateButtons();
        }).dimensions(leftX, btnY, BTN_W_SMALL, BTN_H).build();
        addDrawableChild(tireToggleBtn);

        trackSelectBtn = ButtonWidget.builder(Text.literal("트랙 선택"), b -> {
            playUiClick();
            if (this.client != null) {
                this.client.setScreen(new TrackSelectScreen(this, this.track, this::setTrackAndApplyFromCache));
            }
        }).dimensions(trackX, btnY, BTN_W_TRACK, BTN_H).build();
        addDrawableChild(trackSelectBtn);

        engineToggleBtn = ButtonWidget.builder(getEngineToggleText(), b -> {
            playUiClick();
            enginePanelOpen = !enginePanelOpen;
            if (enginePanelOpen) {
                modePanelOpen = false;
                tirePanelOpen = false;
                ensureEngineScrollForSelection();
            }
            b.setMessage(getEngineToggleText());
            if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());
            if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
            updateButtons();
        }).dimensions(engineX, btnY, BTN_W_SMALL, BTN_H).build();
        addDrawableChild(engineToggleBtn);

        modeToggleBtn = ButtonWidget.builder(getModeToggleText(), b -> {
            playUiClick();
            modePanelOpen = !modePanelOpen;
            if (modePanelOpen) {
                enginePanelOpen = false;
                tirePanelOpen = false;
            }
            b.setMessage(getModeToggleText());
            if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
            if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
            updateButtons();
        }).dimensions(modeX, btnY, BTN_W_SMALL, BTN_H).build();
        addDrawableChild(modeToggleBtn);

        ButtonWidget refreshBtn = ButtonWidget.builder(Text.literal("새로 고침"), b -> {
            playUiClick();
            loading = true;
            error = null;

            ApiCache.fetchAllAsync(true, p -> {
                loading = false;
                error = null;
                applyFromAllPayload();
            }, err -> {
                loading = false;
                error = err;
            });
        }).dimensions(this.width - 92, this.height - 28, 80, 20).build();
        addDrawableChild(refreshBtn);

        // 캐시 로드
        if (ApiCache.isAllReady()) {
            loading = false;
            error = null;
            applyFromAllPayload();
        } else {
            loading = true;
            error = null;

            ApiCache.fetchAllAsync(false, p -> {
                loading = false;
                error = null;
                applyFromAllPayload();
            }, err -> {
                loading = false;
                error = err;
            });
        }

        updateButtons();
    }

    private void setTrackAndApplyFromCache(String newTrack) {
        this.track = sanitizeTrackOrDefault(newTrack);

        page = 0;
        ranking.clear();
        filtered.clear();

        // reset filters
        tires.clear();
        selectedTire = "ALL";
        tirePanelOpen = false;

        engines.clear();
        selectedEngine = "ALL";
        enginePanelOpen = false;
        engineScroll = 0;

        selectedModes.clear();
        modePanelOpen = false;

        if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
        if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
        if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());

        if (!ApiCache.isAllReady()) {
            loading = true;
            error = null;
            updateButtons();
            return;
        }

        loading = false;
        error = null;
        applyFromAllPayload();
        updateButtons();
    }

    private Text getTireToggleText() {
        String arrow = tirePanelOpen ? "▾" : "▸";
        String show = selectedTire.equalsIgnoreCase("ALL") ? "전체" : selectedTire;
        return Text.literal("타이어 " + arrow + " : " + show);
    }

    private Text getEngineToggleText() {
        String arrow = enginePanelOpen ? "▾" : "▸";
        String show = selectedEngine.equalsIgnoreCase("ALL") ? "전체" : selectedEngine;
        return Text.literal("엔진 " + arrow + " : " + show);
    }

    private Text getModeToggleText() {
        String arrow = modePanelOpen ? "▾" : "▸";

        String show;
        if (selectedModes.isEmpty()) show = "없음";
        else if (selectedModes.size() == 1) show = selectedModes.getFirst();
        else {
            List<String> tmp = new ArrayList<>(selectedModes);
            if (tmp.size() <= 2) show = String.join(", ", tmp);
            else show = tmp.get(0) + ", " + tmp.get(1) + " +" + (tmp.size() - 2);
        }

        return Text.literal("모드 " + arrow + " : " + show);
    }

    private void updateButtons() {
        computeLayout();

        if (prevBtn != null) prevBtn.active = (page > 0);
        if (nextBtn != null) nextBtn.active = (!filtered.isEmpty() && (page + 1) * rowsPerPage < filtered.size());
    }

    private void playUiClick() {
        if (this.client == null) return;
        this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private void applyFromAllPayload() {
        ApiCache.AllPayload all = ApiCache.getAllIfReady();
        if (all == null) return;

        ApiCache.RankingPayload rp = all.rankingsByTrack.get(track);
        if (rp == null) {
            ranking.clear();
            filtered.clear();
            engines.clear();
            tires.clear();

            if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
            if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
            if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());
            updateButtons();
            return;
        }

        applyRankingPayload(rp);

        if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
        if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
        if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());

        updateButtons();
    }

    private void applyRankingPayload(ApiCache.RankingPayload payload) {
        ranking.clear();
        filtered.clear();
        engines.clear();
        tires.clear();

        ranking.addAll(payload.ranking);
        if (payload.engines != null) engines.addAll(payload.engines);

        // ✅ tire list는 entry에서 수집
        LinkedHashSet<String> tireSet = new LinkedHashSet<>();
        tireSet.add("ALL");
        for (Entry e : ranking) {
            String t = (e.tireName() == null || e.tireName().isBlank()) ? "UNKNOWN" : e.tireName().trim();
            tireSet.add(t);
        }
        tires.addAll(tireSet);

        // 엔진 list 기본값 보정
        if (engines.isEmpty()) {
            LinkedHashSet<String> es = new LinkedHashSet<>();
            es.add("ALL");
            for (Entry e : ranking) es.add(normalizeEngine(e.engineName()));
            engines.addAll(es);
        }

        engines.replaceAll(s -> s == null ? "UNKNOWN" : s.trim().toUpperCase());
        engines.removeIf(String::isBlank);

        LinkedHashSet<String> uniq = new LinkedHashSet<>(engines);
        engines.clear();
        engines.addAll(uniq);

        engines.sort((a, b) -> {
            if (a.equals("ALL")) return -1;
            if (b.equals("ALL")) return 1;
            return a.compareTo(b);
        });

        // tires 정렬: ALL 먼저, UNKNOWN 마지막
        tires.removeIf(Objects::isNull);
        tires.replaceAll(s -> s == null ? "UNKNOWN" : s.trim());
        tires.removeIf(String::isBlank);
        tires.sort((a, b) -> {
            if (a.equals("ALL")) return -1;
            if (b.equals("ALL")) return 1;
            if (a.equalsIgnoreCase("UNKNOWN") && !b.equalsIgnoreCase("UNKNOWN")) return 1;
            if (b.equalsIgnoreCase("UNKNOWN") && !a.equalsIgnoreCase("UNKNOWN")) return -1;
            return a.compareTo(b);
        });

        // 기본 선택
        selectedTire = "ALL";
        selectedEngine = "ALL";
        selectedModes.clear();

        ranking.sort(Comparator.comparingLong(Entry::timeMillis));
        applyFilter();
        filtered.sort(Comparator.comparingLong(Entry::timeMillis));
        page = 0;

        engineScroll = 0;
        ensureEngineScrollForSelection();
    }

    //필터: 타이어 + 엔진 + 모드(Exact match, 0개=없음)
    private void applyFilter() {
        filtered.clear();

        for (Entry e : ranking) {
            String eng = normalizeEngine(e.engineName());
            Set<String> entryModes = parseModeSet(e.modes());

            String tire = (e.tireName() == null || e.tireName().isBlank()) ? "UNKNOWN" : e.tireName().trim();

            boolean okTire = selectedTire.equalsIgnoreCase("ALL") || selectedTire.equalsIgnoreCase(tire);
            boolean okEngine = selectedEngine.equalsIgnoreCase("ALL") || selectedEngine.equalsIgnoreCase(eng);

            boolean okMode;
            if (selectedModes.isEmpty()) {
                okMode = entryModes.isEmpty();
            } else {
                okMode = entryModes.equals(selectedModes);
            }

            if (okTire && okEngine && okMode) filtered.add(e);
        }

        computeLayout();
        int maxPage = Math.max(0, (filtered.size() - 1) / rowsPerPage);
        if (page > maxPage) page = maxPage;
    }

    private static String normalizeEngine(String engineName) {
        if (engineName == null) return "UNKNOWN";
        String s = engineName.trim();

        if (s.startsWith("[") && s.endsWith("]") && s.length() >= 3) {
            s = s.substring(1, s.length() - 1).trim();
        }

        s = s.replace("엔진", "").replace("ENGINE", "").replace("engine", "").trim();
        if (s.isBlank()) return "UNKNOWN";
        return s.toUpperCase();
    }

    private static Set<String> parseModeSet(String modesCsv) {
        if (modesCsv == null) return Collections.emptySet();
        String s = modesCsv.trim();
        if (s.isBlank()) return Collections.emptySet();
        if (s.equals("없음")) return Collections.emptySet();

        String[] parts = s.split(",");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String p : parts) {
            String v = p.trim();
            if (v.isEmpty()) continue;
            if (v.equals("없음")) continue;
            set.add(v);
        }
        return set;
    }

    // =========================
    //레이아웃/헤더/드롭다운
    // =========================
    private void computeLayout() {
        int base = 66;
        int panelExtra = 0;

        if (tirePanelOpen) {
            int rows = Math.max(1, tires.size());
            int fullH = PANEL_PAD * 2 + rows * ROW_H;
            int panelH = Math.min(fullH, 140);
            panelExtra = Math.max(panelExtra, panelH + 10);
        }

        if (enginePanelOpen) {
            int rows = Math.max(1, engines.size());
            int fullH = PANEL_PAD * 2 + rows * ROW_H;
            int panelH = Math.min(fullH, ENGINE_PANEL_MAX_H);
            panelExtra = Math.max(panelExtra, panelH + 10);
        }

        if (modePanelOpen) {
            int panelH = PANEL_PAD * 2 + FIXED_MODES.size() * ROW_H + 10;
            panelExtra = Math.max(panelExtra, panelH);
        }

        headerH = base + panelExtra;

        int tableTop = HEADER_TOP + headerH + 10;
        int tableBottom = this.height - 46;
        int tableH = Math.max(80, tableBottom - tableTop);

        int headerRow = 18;
        int padding = 26;
        int available = Math.max(1, tableH - headerRow - padding);

        int possible = Math.max(1, available / 18);
        rowsPerPage = Math.min(PAGE_SIZE_MAX, possible);
    }

    private void renderHeader(DrawContext context) {
        computeLayout();

        int x = OUTER_PAD;
        int y = HEADER_TOP;
        int w = this.width - OUTER_PAD * 2;
        int h = headerH;

        context.fill(x, y, x + w, y + h, 0xCC000000);
        drawRectBorder(context, x, y, w, h, 0xFF2A2A2A);

        String tr = (track == null || track.isBlank()) ? DEFAULT_TRACK : track;
        context.drawCenteredTextWithShadow(this.textRenderer, "TRACK", this.width / 2, y + 10, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, tr, this.width / 2, y + 24, 0xFFDDAA);

        String tire = selectedTire.equalsIgnoreCase("ALL") ? "전체" : selectedTire;
        String eng = selectedEngine.equalsIgnoreCase("ALL") ? "전체" : selectedEngine;
        String mode = selectedModes.isEmpty() ? "없음" : String.join(", ", selectedModes);

        context.drawCenteredTextWithShadow(this.textRenderer,
                "타이어: " + tire + "   |   엔진: " + eng + "   |   모드: " + mode,
                this.width / 2, y + 38, 0xBBBBBB);

        // ✅ 버튼 배치 갱신
        int btnY = y + 48;
        int cx = this.width / 2;

        int leftX = OUTER_PAD + 8;
        int trackX = cx - (BTN_W_TRACK / 2);

        int engineX = this.width - OUTER_PAD - 8 - (BTN_W_SMALL * 2 + BTN_GAP);
        int modeX = engineX + BTN_W_SMALL + BTN_GAP;

        if (tireToggleBtn != null) tireToggleBtn.setPosition(leftX, btnY);
        if (trackSelectBtn != null) trackSelectBtn.setPosition(trackX, btnY);
        if (engineToggleBtn != null) engineToggleBtn.setPosition(engineX, btnY);
        if (modeToggleBtn != null) modeToggleBtn.setPosition(modeX, btnY);

        if (tirePanelOpen && tireToggleBtn != null) drawActiveButtonGlow(context, tireToggleBtn);
        if (enginePanelOpen && engineToggleBtn != null) drawActiveButtonGlow(context, engineToggleBtn);
        if (modePanelOpen && modeToggleBtn != null) drawActiveButtonGlow(context, modeToggleBtn);
    }

    private void drawActiveButtonGlow(DrawContext context, ButtonWidget btn) {
        int x = btn.getX();
        int y = btn.getY();
        int w = btn.getWidth();
        int h = btn.getHeight();

        context.fill(x - 2, y - 2, x + w + 2, y + h, 0x5533FF33);
        drawRectBorder(context, x - 2, y - 2, w + 4, h + 2, 0xFF66FF66);
    }

    private void ensureEngineScrollForSelection() {
        if (engines.isEmpty()) {
            engineScroll = 0;
            return;
        }
        int visible = Math.max(1, engineVisibleRows);

        int idx = -1;
        for (int i = 0; i < engines.size(); i++) {
            if (engines.get(i).equalsIgnoreCase(selectedEngine)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            engineScroll = 0;
            return;
        }

        int maxScroll = Math.max(0, engines.size() - visible);
        if (idx < engineScroll) engineScroll = idx;
        else if (idx >= engineScroll + visible) engineScroll = idx - visible + 1;

        if (engineScroll < 0) engineScroll = 0;
        if (engineScroll > maxScroll) engineScroll = maxScroll;
    }

    // ✅ 타이어 드롭다운(스크롤 필요 거의 없어서 단순)
    private void renderTireDropdown(DrawContext context, int mouseX, int mouseY) {
        if (tireToggleBtn == null) return;

        int btnX = tireToggleBtn.getX();
        int btnY = tireToggleBtn.getY();

        tirePanelX = btnX;
        tirePanelY = btnY + BTN_H + 8;
        tirePanelW = TIRE_PANEL_W;

        int rows = Math.max(1, tires.size());
        int fullH = PANEL_PAD * 2 + rows * ROW_H;
        tirePanelH = Math.min(fullH, 140);

        context.fill(tirePanelX, tirePanelY, tirePanelX + tirePanelW, tirePanelY + tirePanelH, 0xEE0B0B0B);
        drawRectBorder(context, tirePanelX, tirePanelY, tirePanelW, tirePanelH, 0xFF444444);

        int y = tirePanelY + PANEL_PAD;
        for (String t : tires) {
            String label = t.equals("ALL") ? "전체" : t;

            boolean selected = t.equalsIgnoreCase(selectedTire);
            boolean hover = mouseX >= tirePanelX && mouseX < tirePanelX + tirePanelW
                    && mouseY >= y && mouseY < y + ROW_H;

            if (hover) context.fill(tirePanelX + 1, y, tirePanelX + tirePanelW - 1, y + ROW_H, 0xFF1C1C1C);
            if (selected) context.fill(tirePanelX + 1, y, tirePanelX + tirePanelW - 1, y + ROW_H, 0xFF153015);

            context.drawTextWithShadow(this.textRenderer, label, tirePanelX + PANEL_PAD, y + (ROW_H - 8) / 2, 0xFFFFFF);
            y += ROW_H;

            if (y > tirePanelY + tirePanelH - PANEL_PAD) break;
        }
    }

    // 엔진 드롭다운
    private void renderEngineDropdown(DrawContext context, int mouseX, int mouseY) {
        if (engineToggleBtn == null) return;

        int btnX = engineToggleBtn.getX();
        int btnY = engineToggleBtn.getY();

        enginePanelX = btnX;
        enginePanelY = btnY + BTN_H + 8;
        enginePanelW = ENGINE_PANEL_W;

        int rows = Math.max(1, engines.size());
        int fullH = PANEL_PAD * 2 + rows * ROW_H;
        enginePanelH = Math.min(fullH, ENGINE_PANEL_MAX_H);

        engineVisibleRows = Math.max(1, (enginePanelH - PANEL_PAD * 2) / ROW_H);

        int maxScroll = Math.max(0, engines.size() - engineVisibleRows);
        if (engineScroll > maxScroll) engineScroll = maxScroll;
        if (engineScroll < 0) engineScroll = 0;

        context.fill(enginePanelX, enginePanelY, enginePanelX + enginePanelW, enginePanelY + enginePanelH, 0xEE0B0B0B);
        drawRectBorder(context, enginePanelX, enginePanelY, enginePanelW, enginePanelH, 0xFF444444);

        int scrollBarW = (maxScroll > 0) ? 8 : 0;
        int listW = enginePanelW - scrollBarW;

        int startIndex = engineScroll;
        int endIndex = Math.min(engines.size(), startIndex + engineVisibleRows);

        int y = enginePanelY + PANEL_PAD;
        for (int idx = startIndex; idx < endIndex; idx++) {
            String e = engines.get(idx);
            String label = e.equals("ALL") ? "전체" : e;

            boolean selected = e.equalsIgnoreCase(selectedEngine);
            boolean hover = mouseX >= enginePanelX && mouseX < enginePanelX + listW
                    && mouseY >= y && mouseY < y + ROW_H;

            if (hover) context.fill(enginePanelX + 1, y, enginePanelX + listW - 1, y + ROW_H, 0xFF1C1C1C);
            if (selected) context.fill(enginePanelX + 1, y, enginePanelX + listW - 1, y + ROW_H, 0xFF153015);

            context.drawTextWithShadow(this.textRenderer, label, enginePanelX + PANEL_PAD, y + (ROW_H - 8) / 2, 0xFFFFFF);
            y += ROW_H;
        }

        if (maxScroll > 0) {
            int barX = enginePanelX + enginePanelW - scrollBarW;
            int barY = enginePanelY + 1;
            int barH = enginePanelH - 2;

            context.fill(barX, barY, barX + scrollBarW, barY + barH, 0xFF111111);

            float ratio = engineVisibleRows / (float) engines.size();
            int thumbH = Math.max(12, (int) (barH * ratio));

            float posRatio = engineScroll / (float) maxScroll;
            int thumbY = barY + (int) ((barH - thumbH) * posRatio);

            context.fill(barX + 1, thumbY, barX + scrollBarW - 1, thumbY + thumbH, 0xFF3A3A3A);
            drawRectBorder(context, barX + 1, thumbY, scrollBarW - 2, thumbH, 0xFF777777);
        }
    }

    // 모드 드롭다운
    private void renderModeDropdown(DrawContext context, int mouseX, int mouseY) {
        if (modeToggleBtn == null) return;

        int btnX = modeToggleBtn.getX();
        int btnY = modeToggleBtn.getY();
        int btnW = modeToggleBtn.getWidth();

        modePanelW = MODE_PANEL_W;

        modePanelX = btnX + btnW - modePanelW;
        modePanelY = btnY + BTN_H + 8;
        modePanelH = PANEL_PAD * 2 + FIXED_MODES.size() * ROW_H;

        context.fill(modePanelX, modePanelY, modePanelX + modePanelW, modePanelY + modePanelH, 0xEE0B0B0B);
        drawRectBorder(context, modePanelX, modePanelY, modePanelW, modePanelH, 0xFF666666);

        int rowY = modePanelY + PANEL_PAD;
        for (String mode : FIXED_MODES) {
            boolean checked = selectedModes.contains(mode);

            boolean hoverRow = mouseX >= modePanelX && mouseX < modePanelX + modePanelW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hoverRow) context.fill(modePanelX + 1, rowY, modePanelX + modePanelW - 1, rowY + ROW_H, 0xFF1E1E1E);

            context.drawTextWithShadow(this.textRenderer, mode, modePanelX + PANEL_PAD, rowY + (ROW_H - 8) / 2, 0xFFFFFF);

            int boxX = modePanelX + modePanelW - PANEL_PAD - CHECK_SIZE;
            int boxY = rowY + (ROW_H - CHECK_SIZE) / 2;
            boolean hoverBox = mouseX >= boxX && mouseX < boxX + CHECK_SIZE && mouseY >= boxY && mouseY < boxY + CHECK_SIZE;

            drawCheckbox(context, boxX, boxY, checked, hoverBox);

            rowY += ROW_H;
        }
    }

    private static String formatDateTime(long ms) {
        if (ms <= 0) return "알 수 없음";
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return fmt.format(new java.util.Date(ms));
    }

    private void drawTooltipBox(DrawContext context, int mouseX, int mouseY, String text) {
        int pad = 6;
        int tw = this.textRenderer.getWidth(text);
        int th = 9;

        int x = mouseX + 10;
        int y = mouseY + 10;

        if (x + tw + pad * 2 > this.width - 4) x = mouseX - (tw + pad * 2) - 10;
        if (y + th + pad * 2 > this.height - 4) y = mouseY - (th + pad * 2) - 10;

        int bg = 0xEE000000;
        context.fill(x, y, x + tw + pad * 2, y + th + pad * 2, bg);
        drawRectBorder(context, x, y, tw + pad * 2, th + pad * 2, 0xFF777777);
        context.drawTextWithShadow(this.textRenderer, text, x + pad, y + pad, 0xFFFFFF);
    }

    private void drawCheckbox(DrawContext context, int x, int y, boolean checked, boolean hover) {
        int bg = hover ? 0xFF2A2A2A : 0xFF141414;
        context.fill(x, y, x + RankingScreen.CHECK_SIZE, y + RankingScreen.CHECK_SIZE, bg);
        drawRectBorder(context, x, y, RankingScreen.CHECK_SIZE, RankingScreen.CHECK_SIZE, 0xFFAAAAAA);

        if (checked) {
            int pad = 3;
            context.fill(x + pad, y + pad, x + RankingScreen.CHECK_SIZE - pad, y + RankingScreen.CHECK_SIZE - pad, 0xFF55FF55);
        }
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    // =========================
    //스크롤 처리(엔진 리스트)
    // =========================
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (enginePanelOpen) {
            boolean inside = mouseX >= enginePanelX && mouseX < enginePanelX + enginePanelW
                    && mouseY >= enginePanelY && mouseY < enginePanelY + enginePanelH;

            if (inside) {
                int maxScroll = Math.max(0, engines.size() - engineVisibleRows);

                if (verticalAmount > 0) engineScroll--;
                else if (verticalAmount < 0) engineScroll++;

                if (engineScroll < 0) engineScroll = 0;
                if (engineScroll > maxScroll) engineScroll = maxScroll;
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // =========================
    //클릭 처리 + 플레이어 이름 클릭 -> 프로필
    // =========================
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {

        // 타이어 드롭다운
        if (tirePanelOpen) {
            if (mouseX < tirePanelX || mouseX >= tirePanelX + tirePanelW || mouseY < tirePanelY || mouseY >= tirePanelY + tirePanelH) {
                tirePanelOpen = false;
                if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
                updateButtons();
                return true;
            }

            int y = tirePanelY + PANEL_PAD;
            for (String tire : tires) {
                if (mouseY >= y && mouseY < y + ROW_H) {
                    selectedTire = tire;
                    playUiClick();
                    tirePanelOpen = false;

                    page = 0;
                    applyFilter();
                    updateButtons();

                    if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
                    return true;
                }
                y += ROW_H;
                if (y > tirePanelY + tirePanelH - PANEL_PAD) break;
            }
            return true;
        }

        // 엔진 드롭다운
        if (enginePanelOpen) {
            if (mouseX < enginePanelX || mouseX >= enginePanelX + enginePanelW || mouseY < enginePanelY || mouseY >= enginePanelY + enginePanelH) {
                enginePanelOpen = false;
                if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
                updateButtons();
                return true;
            }

            int maxScroll = Math.max(0, engines.size() - engineVisibleRows);
            if (engineScroll > maxScroll) engineScroll = maxScroll;
            if (engineScroll < 0) engineScroll = 0;

            int startIndex = engineScroll;
            int endIndex = Math.min(engines.size(), startIndex + engineVisibleRows);

            int y = enginePanelY + PANEL_PAD;
            for (int idx = startIndex; idx < endIndex; idx++) {
                if (mouseY >= y && mouseY < y + ROW_H) {
                    selectedEngine = engines.get(idx);
                    playUiClick();
                    enginePanelOpen = false;

                    page = 0;
                    applyFilter();
                    updateButtons();

                    if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
                    return true;
                }
                y += ROW_H;
            }
            return true;
        }

        // 모드 드롭다운
        if (modePanelOpen) {
            if (mouseX < modePanelX || mouseX >= modePanelX + modePanelW || mouseY < modePanelY || mouseY >= modePanelY + modePanelH) {
                modePanelOpen = false;
                if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());
                updateButtons();
                return true;
            }

            int rowY = modePanelY + PANEL_PAD;
            for (String mode : FIXED_MODES) {
                int boxX = modePanelX + modePanelW - PANEL_PAD - CHECK_SIZE;
                int boxY = rowY + (ROW_H - CHECK_SIZE) / 2;

                boolean hitRow = mouseX >= modePanelX && mouseX < modePanelX + modePanelW
                        && mouseY >= rowY && mouseY < rowY + ROW_H;
                boolean hitBox = mouseX >= boxX && mouseX < boxX + CHECK_SIZE
                        && mouseY >= boxY && mouseY < boxY + CHECK_SIZE;

                if (hitRow || hitBox) {
                    if (selectedModes.contains(mode)) selectedModes.remove(mode);
                    else selectedModes.add(mode);

                    playUiClick();

                    page = 0;
                    applyFilter();
                    updateButtons();

                    if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());
                    return true;
                }
                rowY += ROW_H;
            }
            return true;
        }

        //플레이어 이름 클릭 -> 프로필 화면
        if (!loading && error == null && !filtered.isEmpty()) {
            computeLayout();

            int tableTop = HEADER_TOP + headerH + 10;
            int tableX = OUTER_PAD + 8;

            int startY = tableTop + 24;
            int rowH = 18;

            int start = page * rowsPerPage;
            int end = Math.min(start + rowsPerPage, filtered.size());

            for (int i = start; i < end; i++) {
                Entry e = filtered.get(i);
                int idxInPage = (i - start);
                int playerY = startY + idxInPage * rowH;

                int playerX = tableX + 86;
                int playerW = this.textRenderer.getWidth(e.player());
                int playerH = 9;

                boolean hitPlayer =
                        mouseX >= playerX && mouseX <= playerX + playerW &&
                                mouseY >= playerY - 2 && mouseY <= playerY - 2 + playerH + 6;

                if (hitPlayer) {
                    playUiClick();
                    if (this.client != null) {
                        this.client.setScreen(new PlayerProfileScreen(e.player(), this));
                    }
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    // =========================
    //렌더
    // =========================
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        renderHeader(context);

        if (tirePanelOpen) renderTireDropdown(context, mouseX, mouseY);
        if (enginePanelOpen) renderEngineDropdown(context, mouseX, mouseY);
        if (modePanelOpen) renderModeDropdown(context, mouseX, mouseY);

        int tableTop = HEADER_TOP + headerH + 10;
        int tableX = OUTER_PAD + 8;
        int tableW = this.width - (OUTER_PAD + 8) * 2;
        int tableBottom = this.height - 46;
        int tableH = Math.max(80, tableBottom - tableTop);

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer, "불러오는 중...", this.width / 2, tableTop + 26, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (error != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, "오류: " + error, this.width / 2, tableTop + 26, 0xFF5555);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (ranking.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, "데이터가 없습니다. 우측 하단 '새로 고침'을 눌러주세요.", this.width / 2, tableTop + 26, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (filtered.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, "해당 조건의 기록이 없습니다.", this.width / 2, tableTop + 26, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        // 테이블 박스
        context.fill(tableX, tableTop, tableX + tableW, tableTop + tableH, 0x66000000);
        drawRectBorder(context, tableX, tableTop, tableW, tableH, 0xFF222222);

        // 컬럼 헤더
        int headerRowY = tableTop + 8;
        context.drawTextWithShadow(this.textRenderer, "순위", tableX + 16, headerRowY, 0xDDDDDD);
        context.drawTextWithShadow(this.textRenderer, "플레이어", tableX + 86, headerRowY, 0xDDDDDD);
        context.drawTextWithShadow(this.textRenderer, "기록", tableX + 276, headerRowY, 0xDDDDDD);
        context.drawTextWithShadow(this.textRenderer, "카트바디", tableX + 388, headerRowY, 0xDDDDDD);
        context.drawTextWithShadow(this.textRenderer, "엔진", tableX + tableW - 66, headerRowY, 0xDDDDDD);

        int startY = tableTop + 24;
        int rowH = 18;

        int start = page * rowsPerPage;
        int end = Math.min(start + rowsPerPage, filtered.size());

        Entry hoveredEntry = null;
        boolean hoverOnPlayerName = false;

        bodyHits.clear();
        String hoveredTireTooltip = null;

        for (int i = start; i < end; i++) {
            Entry e = filtered.get(i);
            int idxInPage = (i - start);
            int y = startY + idxInPage * rowH;

            int rank = i + 1;

            if (rank == 1) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + rowH - 1, 0x44FFD700);
            else if (rank == 2) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + rowH - 1, 0x44C0C0C0);
            else if (rank == 3) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + rowH - 1, 0x44CD7F32);
            else if ((idxInPage & 1) == 1) context.fill(tableX + 1, y - 2, tableX + tableW - 1, y + rowH - 1, 0x22000000);

            String eng = normalizeEngine(e.engineName());
            String bodyLabel = TireUtil.composeBodyLabel(e.bodyName(), e.tireName());

            int rankColor =
                    (rank == 1) ? 0xFFFFE066 :
                            (rank == 2) ? 0xFFE6E6E6 :
                                    (rank == 3) ? 0xFFFFB36B :
                                            0xFFFFFFFF;

            context.drawTextWithShadow(this.textRenderer, rank + "위", tableX + 16, y, rankColor);

            int playerX = tableX + 86;
            int playerW = this.textRenderer.getWidth(e.player());
            boolean hoverPlayer =
                    mouseX >= playerX && mouseX <= playerX + playerW &&
                            mouseY >= y - 2 && mouseY <= y - 2 + 9 + 6;

            if (hoverPlayer) {
                hoveredEntry = e;
                hoverOnPlayerName = true;
            }

            int playerColor = hoverPlayer ? 0xFFFFEE88 : 0xFFFFFF;

            context.drawTextWithShadow(this.textRenderer, e.player(), playerX, y, playerColor);
            context.drawTextWithShadow(this.textRenderer, e.timeStr(), tableX + 276, y, 0xFFFFFF);

            // ✅ 바디 + 타이어 기호 + hover tooltip
            int bodyX = tableX + 388;
            int bodyW = this.textRenderer.getWidth(bodyLabel);
            BodyHit bh = new BodyHit(bodyX, y - 2, bodyX + bodyW, y + 10, e.tireName());
            bodyHits.add(bh);
            if (bh.hit(mouseX, mouseY)) {
                hoveredTireTooltip = TireUtil.tooltipName(e.tireName());
            }
            context.drawTextWithShadow(this.textRenderer, bodyLabel, bodyX, y, 0xFFFFFF);

            context.drawTextWithShadow(this.textRenderer, eng, tableX + tableW - 66, y, 0xFFFFFF);
        }

        // ✅ 툴팁 우선순위: 타이어(바디 hover) > 기존 등록/프로필 안내
        if (hoveredTireTooltip != null) {
            drawTooltipBox(context, mouseX, mouseY, hoveredTireTooltip);
        } else if (hoveredEntry != null) {
            String dt = formatDateTime(hoveredEntry.submittedAtMs());
            String tip = hoverOnPlayerName ? ("클릭: 프로필 열기 | 등록: " + dt) : ("등록: " + dt);
            drawTooltipBox(context, mouseX, mouseY, tip);
        }

        int totalPages = Math.max(1, (filtered.size() + rowsPerPage - 1) / rowsPerPage);
        String pageInfo = String.format("페이지 %d / %d", (page + 1), totalPages);
        context.drawCenteredTextWithShadow(this.textRenderer, pageInfo, this.width / 2, this.height - 26, 0xAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0x88000000);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ✅ tireName 추가
    public record Entry(String player, String timeStr, long timeMillis,
                               String engineName, String bodyName, String tireName, String modes,
                               long submittedAtMs) {}

    /* =========================================================
      ApiCache: getAll 단일 호출로 모든 데이터 캐시
       ========================================================= */
    public static final class ApiCache {
        private ApiCache() {}

        public static final class RankingPayload {
            public final List<Entry> ranking;
            public final List<String> engines;

            public RankingPayload(List<Entry> ranking, List<String> engines) {
                this.ranking = ranking;
                this.engines = engines;
            }
        }

        public static final class AllPayload {
            public final List<TrackSelectScreen.TrackEntry> tracks;
            public final Map<String, RankingPayload> rankingsByTrack;
            public final long fetchedAtMs;

            public AllPayload(List<TrackSelectScreen.TrackEntry> tracks,
                              Map<String, RankingPayload> rankingsByTrack,
                              long fetchedAtMs) {
                this.tracks = tracks;
                this.rankingsByTrack = rankingsByTrack;
                this.fetchedAtMs = fetchedAtMs;
            }
        }

        private static volatile AllPayload cachedAll = null;
        private static volatile boolean fetching = false;
        private static final List<Runnable> waiters = new ArrayList<>();

        private static final long TTL_MS = 10 * 60_000;

        public static boolean isAllReady() {
            return getAllIfReady() != null;
        }

        public static AllPayload getAllIfReady() {
            AllPayload p = cachedAll;
            if (p == null) return null;
            long now = System.currentTimeMillis();
            if (now - p.fetchedAtMs > TTL_MS) return null;
            return p;
        }

        public static void fetchAllAsync(boolean force,
                                         Consumer<AllPayload> onDone,
                                         Consumer<String> onError) {
            if (!force) {
                AllPayload fresh = getAllIfReady();
                if (fresh != null) {
                    onDone.accept(fresh);
                    return;
                }
            }

            synchronized (ApiCache.class) {
                if (fetching) {
                    waiters.add(() -> onDone.accept(getAllIfReady()));
                    return;
                }
                fetching = true;
            }

            new Thread(() -> {
                String err = null;
                AllPayload payload = null;

                try {
                    JsonObject res = Net.postJson(GAS_URL, "{\"action\":\"getAll\"}");

                    if (!res.has("ok") || !res.get("ok").getAsBoolean()) {
                        err = res.has("error") ? res.get("error").getAsString() : "unknown error";
                    } else {
                        List<TrackSelectScreen.TrackEntry> tracks = new ArrayList<>();
                        if (res.has("tracks") && res.get("tracks").isJsonArray()) {
                            JsonArray arr = res.getAsJsonArray("tracks");
                            for (int i = 0; i < arr.size(); i++) {
                                JsonObject o = arr.get(i).getAsJsonObject();
                                String tr = o.get("track").getAsString();
                                int count = o.get("count").getAsInt();
                                tracks.add(new TrackSelectScreen.TrackEntry(tr, count));
                            }
                            tracks.sort(Comparator.comparingInt(TrackSelectScreen.TrackEntry::count).reversed());
                        }

                        Map<String, RankingPayload> map = new HashMap<>();
                        if (res.has("rankings") && res.get("rankings").isJsonObject()) {
                            JsonObject robj = res.getAsJsonObject("rankings");
                            for (Map.Entry<String, JsonElement> it : robj.entrySet()) {
                                String tr = it.getKey();
                                JsonObject v = it.getValue().getAsJsonObject();

                                List<String> engines = new ArrayList<>();
                                if (v.has("engines") && v.get("engines").isJsonArray()) {
                                    JsonArray earr = v.getAsJsonArray("engines");
                                    for (int j = 0; j < earr.size(); j++) {
                                        String e = earr.get(j).getAsString();
                                        if (e != null && !e.isBlank()) engines.add(e.trim().toUpperCase());
                                    }
                                }

                                List<Entry> list = new ArrayList<>();
                                if (v.has("ranking") && v.get("ranking").isJsonArray()) {
                                    JsonArray rarr = v.getAsJsonArray("ranking");
                                    for (int j = 0; j < rarr.size(); j++) {
                                        JsonObject o = rarr.get(j).getAsJsonObject();
                                        String player = o.get("player").getAsString();
                                        String time = o.get("time").getAsString();
                                        long ms = o.get("timeMillis").getAsLong();
                                        String engineName = o.has("engineName") ? o.get("engineName").getAsString() : "UNKNOWN";
                                        String bodyName = o.has("bodyName") ? o.get("bodyName").getAsString() : "UNKNOWN";
                                        String tireName = o.has("tireName") ? o.get("tireName").getAsString() : "UNKNOWN";
                                        String modes = o.has("modes") ? o.get("modes").getAsString() : "없음";
                                        long submittedAtMs = o.has("submittedAtMs") ? o.get("submittedAtMs").getAsLong() : 0L;

                                        list.add(new Entry(player, time, ms, engineName, bodyName, tireName, modes, submittedAtMs));
                                    }
                                }

                                list.sort(Comparator.comparingLong(Entry::timeMillis));
                                map.put(tr, new RankingPayload(list, engines));
                            }
                        }

                        payload = new AllPayload(tracks, map, System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }

                final String ferr = err;
                final AllPayload fp = payload;

                MinecraftClient.getInstance().execute(() -> {
                    synchronized (ApiCache.class) {
                        fetching = false;
                        if (fp != null) cachedAll = fp;

                        for (Runnable r : waiters) r.run();
                        waiters.clear();
                    }

                    if (ferr != null) onError.accept(ferr);
                    else onDone.accept(fp);
                });
            }, "GAS-getAll-Fetch").start();
        }
    }

    public static final class Net {
        private Net() {}

        public static JsonObject postJson(String url, String jsonBody) throws Exception {
            URI uri = URI.create(url);
            HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setDoOutput(true);

            con.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));

            try (InputStreamReader reader = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}