package com.example.rankinglog;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.*;

public class PlayerProfileScreen extends Screen {

    private final String playerName;
    private final Screen parent;

    private final List<RecordRow> records = new ArrayList<>();

    private boolean loading = true;
    private String error = null;

    private int page = 0;
    private int rowsPerPage = 10;

    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;

    private static final int OUTER_PAD = 12;
    private static final int HEADER_TOP = 10;

    private static final int BTN_H = 20;
    private static final int ROW_H = 18;

    private static final float TABLE_SCALE = 0.86f;

    private final List<ModeHit> modeHits = new ArrayList<>();
    private final List<DateHit> dateHits = new ArrayList<>();
    private final List<TireHit> tireHits = new ArrayList<>();

    private static final class ModeHit {
        final int x1, y1, x2, y2;
        final String fullText;
        ModeHit(int x1, int y1, int x2, int y2, String fullText) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.fullText = fullText;
        }
        boolean hit(int mx, int my) { return mx >= x1 && mx <= x2 && my >= y1 && my <= y2; }
    }

    private static final class DateHit {
        final int x1, y1, x2, y2;
        final long submittedAtMs;
        DateHit(int x1, int y1, int x2, int y2, long submittedAtMs) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.submittedAtMs = submittedAtMs;
        }
        boolean hit(int mx, int my) { return mx >= x1 && mx <= x2 && my >= y1 && my <= y2; }
    }

    private static final class TireHit {
        final int x1, y1, x2, y2;
        final String tireName;
        TireHit(int x1, int y1, int x2, int y2, String tireName) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.tireName = tireName;
        }
        boolean hit(int mx, int my) { return mx >= x1 && mx <= x2 && my >= y1 && my <= y2; }
    }

    public PlayerProfileScreen(String playerName, Screen parent) {
        super(Text.literal("프로필"));
        this.playerName = (playerName == null) ? "" : playerName.trim();
        this.parent = parent;
    }


    // 1. ESC 키 및 화면 닫기 시 parent로 돌아가도록 설정
    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    protected void init() {
        boolean isSmall = this.width < 420;
        int pageBtnGap = isSmall ? 40 : 60;  // 중앙 버튼과의 간격 조정
        int y = this.height - 28;
        int cx = this.width / 2;
        int iconBtnSize = 20;
        int gap = 5;

        // 1. 뒤로 가기 버튼 (왼쪽 하단 끝)
        // 기호: ⏴, 툴팁: 뒤로 가기, 크기: 20x20
        addDrawableChild(ButtonWidget.builder(Text.literal("⏴"), b -> {
                    this.close(); // 아래 정의된 close()를 호출하여 parent로 이동
                })
                .dimensions(OUTER_PAD, y, iconBtnSize, 20)
                .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기")))
                .build());

        // 2. 페이지 이전 버튼 (중앙 좌측)
        prevBtn = ButtonWidget.builder(Text.literal("◀"), b -> {
                    if (page > 0) page--;
                    updateButtons();
                })
                .dimensions(cx - pageBtnGap - 10, this.height - 28, 20, 20)
                .build();
        addDrawableChild(prevBtn);

        // 3. 페이지 다음 버튼 (중앙 우측)
        nextBtn = ButtonWidget.builder(Text.literal("▶"), b -> {
                    if ((page + 1) * rowsPerPage < records.size()) page++;
                    updateButtons();
                })
                .dimensions(cx + pageBtnGap - 10, this.height - 28, 20, 20)
                .build();
        addDrawableChild(nextBtn);

        // 4. 새로 고침 버튼 (오른쪽 하단 끝)
        // 기호: 🔄, 툴팁: 새로 고침, 크기: 20x20
        addDrawableChild(ButtonWidget.builder(Text.literal("🔄"), b -> {
                    loading = true;
                    error = null;
                    records.clear();
                    page = 0;
                    updateButtons();

                    RankingScreen.ApiCache.fetchAllAsync(true, p -> {
                        loading = false;
                        error = null;
                        loadRecordsFromCache();
                        updateButtons();
                    }, err -> {
                        loading = false;
                        error = err;
                        updateButtons();
                    });
                })
                .dimensions(this.width - iconBtnSize - OUTER_PAD, y, iconBtnSize, 20)
                .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침")))
                .build());

        // 5. 초기 데이터 로드 로직
        if (RankingScreen.ApiCache.isAllReady()) {
            loading = false;
            error = null;
            loadRecordsFromCache();
        } else {
            loading = true;
            error = null;
        }

        // 6. 버튼 활성화 상태 업데이트
        updateButtons();
    }


    private void updateButtons() {
        int headerH = 66;
        int tableTop = HEADER_TOP + headerH + 10;
        int tableBottom = this.height - 46;
        int tableH = Math.max(80, tableBottom - tableTop);

        int available = Math.max(1, tableH - 28);
        int possible = Math.max(1, available / ROW_H);
        rowsPerPage = Math.min(14, possible);

        int maxPage = Math.max(0, (records.size() - 1) / rowsPerPage);
        if (page > maxPage) page = maxPage;
        if (page < 0) page = 0;

        if (prevBtn != null) prevBtn.active = (page > 0);
        if (nextBtn != null) nextBtn.active = (!records.isEmpty() && (page + 1) * rowsPerPage < records.size());
    }

    private void loadRecordsFromCache() {
        records.clear();

        RankingScreen.ApiCache.AllPayload all = RankingScreen.ApiCache.getAllIfReady();
        if (all == null) return;

        for (var entry : all.rankingsByTrack.entrySet()) {
            String track = entry.getKey();
            List<RankingScreen.Entry> list = entry.getValue().ranking;

            for (RankingScreen.Entry e : list) {
                if (e.player() == null) continue;

                if (e.player().equalsIgnoreCase(playerName)) {
                    records.add(new RecordRow(
                            e.submittedAtMs(),
                            track,
                            safeText(e.timeStr(), "??:??.???"),
                            safeText(e.bodyName(), "UNKNOWN"),
                            safeText(e.tireName(), "UNKNOWN"),
                            safeText(e.engineName(), "UNKNOWN"),
                            safeText(e.modes(), "없음")
                    ));
                }
            }
        }

        records.sort(Comparator.comparingLong(RecordRow::submittedAtMs).reversed());
        page = 0;
    }

    private static String safeText(String s, String def) {
        if (s == null) return def;
        String t = s.trim();
        return t.isBlank() ? def : t;
    }

    @Override
    protected void applyBlur() { }
    @Override
    public void blur() { }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0x66000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        // ===== 헤더 =====
        int headerX = OUTER_PAD;
        int headerY = HEADER_TOP;
        int headerW = this.width - OUTER_PAD * 2;
        int headerH = 66;

        context.fill(headerX, headerY, headerX + headerW, headerY + headerH, 0xCC000000);
        drawRectBorder(context, headerX, headerY, headerW, headerH, 0xFF2A2A2A);

        context.drawCenteredTextWithShadow(this.textRenderer, "PLAYER PROFILE", this.width / 2, headerY + 10, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, playerName, this.width / 2, headerY + 26, 0xFFDDAA);

        String sub = (loading) ? "불러오는 중..."
                : (error != null ? ("오류: " + error) : ("총 기록 수: " + records.size()));
        context.drawCenteredTextWithShadow(this.textRenderer, sub, this.width / 2, headerY + 44, 0xBBBBBB);

        // ===== 테이블 =====
        int tableX = OUTER_PAD + 8;
        int tableY = headerY + headerH + 10;
        int tableW = this.width - (OUTER_PAD + 8) * 2;
        int tableBottom = this.height - 46;
        int tableH = Math.max(120, tableBottom - tableY);

        context.fill(tableX, tableY, tableX + tableW, tableY + tableH, 0x66000000);
        drawRectBorder(context, tableX, tableY, tableW, tableH, 0xFF222222);

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer, "불러오는 중...", this.width / 2, tableY + 30, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (error != null) {
            // error 문자열에 http가 포함되어 있다면 서비스 종료 안내 문구로 변경
            String displayError = error;
            if (error.toLowerCase().contains("http")) {
                displayError = "이 버전은 서비스 종료 되었습니다. 최신 버전을 이용해 주세요.";
            }

            context.drawCenteredTextWithShadow(this.textRenderer, "오류: " + displayError, this.width / 2, tableY + 30, 0xFF5555);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (records.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, "해당 플레이어 기록이 없습니다.", this.width / 2, tableY + 30, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        updateButtons();

        modeHits.clear();
        dateHits.clear();
        tireHits.clear();

        int hoveredDateIndex = -1;
        int hoveredModeIndex = -1;
        int hoveredTireIndex = -1;

        int smx = (int) (mouseX / TABLE_SCALE);
        int smy = (int) (mouseY / TABLE_SCALE);

        context.getMatrices().push();
        context.getMatrices().scale(TABLE_SCALE, TABLE_SCALE, 1.0f);

        int sx = (int) (tableX / TABLE_SCALE);
        int sy = (int) (tableY / TABLE_SCALE);
        int sW = (int) (tableW / TABLE_SCALE);

        int headerRowY = sy + 8;

        int colDate   = sx + 10;
        int colTrack  = sx + 105;
        int colTime   = sx + (int)(sW * 0.52f);
        int colBody   = sx + (int)(sW * 0.66f);
        int colEngine = sx + (int)(sW * 0.83f);
        int colMode   = sx + (int)(sW * 0.92f);

        context.drawTextWithShadow(textRenderer, "날짜", colDate, headerRowY, 0xDDDDDD);
        context.drawTextWithShadow(textRenderer, "트랙", colTrack, headerRowY, 0xDDDDDD);
        context.drawTextWithShadow(textRenderer, "기록", colTime, headerRowY, 0xDDDDDD);
        context.drawTextWithShadow(textRenderer, "카트바디", colBody, headerRowY, 0xDDDDDD);
        context.drawTextWithShadow(textRenderer, "엔진", colEngine, headerRowY, 0xDDDDDD);
        context.drawTextWithShadow(textRenderer, "모드", colMode, headerRowY, 0xDDDDDD);

        int startY = sy + 24;

        int start = page * rowsPerPage;
        int end = Math.min(start + rowsPerPage, records.size());

        for (int i = start; i < end; i++) {
            RecordRow r = records.get(i);
            int idx = i - start;
            int ry = startY + idx * ROW_H;

            if ((idx & 1) == 1) {
                context.fill((int)(tableX / TABLE_SCALE) + 1, ry - 2,
                        (int)((tableX + tableW) / TABLE_SCALE) - 1, ry + ROW_H - 1, 0x22000000);
            }

            String date = formatDateYY(r.submittedAtMs());
            String eng = normalizeEngine(r.engineName());
            String modeShort = formatModePlusCount(r.modes());

            // 날짜 hit
            int dateW = textRenderer.getWidth(date);
            DateHit dh = new DateHit(colDate, ry - 2, colDate + dateW, ry + 10, r.submittedAtMs());
            dateHits.add(dh);
            boolean hoverDate = dh.hit(smx, smy);

            // 모드 hit
            int modeW = textRenderer.getWidth(modeShort);
            ModeHit mh = new ModeHit(colMode, ry - 2, colMode + modeW, ry + 10, r.modes());
            modeHits.add(mh);
            boolean hoverMode = mh.hit(smx, smy);

            // 바디+타이어 hit
            String bodyLabel = TireUtil.composeBodyLabel(r.bodyName(), r.tireName());
            int bodyW = textRenderer.getWidth(bodyLabel);
            TireHit th = new TireHit(colBody, ry - 2, colBody + bodyW, ry + 10, r.tireName());
            tireHits.add(th);
            boolean hoverTire = th.hit(smx, smy);

            if (hoverDate) hoveredDateIndex = i;
            if (hoverMode) hoveredModeIndex = i;
            if (hoverTire) hoveredTireIndex = i;

            int dateColor = hoverDate ? 0xFFFFEE88 : 0xFFFFFF;

            context.drawTextWithShadow(textRenderer, date, colDate, ry, dateColor);
            context.drawTextWithShadow(textRenderer, r.track(), colTrack, ry, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, r.timeStr(), colTime, ry, 0xFFFFFF);

            context.drawTextWithShadow(textRenderer, bodyLabel, colBody, ry, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, eng, colEngine, ry, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, modeShort, colMode, ry, 0xFFFFFF);
        }

        context.getMatrices().pop();

        // 툴팁 우선순위: 날짜 > 타이어 > 모드
        if (hoveredDateIndex >= 0) {
            RecordRow r = records.get(hoveredDateIndex);
            drawTooltipBox(context, mouseX, mouseY, "등록: " + formatDateTimeFull(r.submittedAtMs()));
        } else if (hoveredTireIndex >= 0) {
            RecordRow r = records.get(hoveredTireIndex);
            drawTooltipBox(context, mouseX, mouseY, TireUtil.tooltipName(r.tireName()));
        } else {
            for (ModeHit h : modeHits) {
                if (h.hit(smx, smy)) {
                    drawTooltipBox(context, mouseX, mouseY, "모드: " + h.fullText);
                    break;
                }
            }
        }

        int totalPages = Math.max(1, (records.size() + rowsPerPage - 1) / rowsPerPage);
        String pageInfo = String.format("페이지 %d / %d", (page + 1), totalPages);
        context.drawCenteredTextWithShadow(this.textRenderer, pageInfo, this.width / 2, this.height - 26, 0xAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    private static String formatDateYY(long ms) {
        if (ms <= 0) return "알 수 없음";
        return new SimpleDateFormat("yy-MM-dd").format(new Date(ms));
    }

    private static String formatDateTimeFull(long ms) {
        if (ms <= 0) return "알 수 없음";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ms));
    }

    private static String normalizeEngine(String engineName) {
        if (engineName == null) return "UNKNOWN";
        String s = engineName.trim();
        if (s.startsWith("[") && s.endsWith("]") && s.length() >= 3) s = s.substring(1, s.length() - 1).trim();
        s = s.replace("엔진", "").replace("ENGINE", "").replace("engine", "").trim();
        if (s.isBlank()) return "UNKNOWN";
        return s.toUpperCase();
    }

    private static String formatModePlusCount(String modes) {
        if (modes == null) return "없음";
        String s = modes.trim();
        if (s.isBlank() || s.equals("없음")) return "없음";

        String[] parts = s.split(",");
        int count = 0;
        for (String p : parts) {
            String v = p.trim();
            if (!v.isEmpty() && !v.equals("없음")) count++;
        }
        return (count <= 0) ? "없음" : ("+" + count);
    }

    private void drawTooltipBox(DrawContext context, int mouseX, int mouseY, String text) {
        int pad = 6;
        int tw = this.textRenderer.getWidth(text);
        int th = 9;

        int x = mouseX + 10;
        int y = mouseY + 10;

        if (x + tw + pad * 2 > this.width - 4) x = mouseX - (tw + pad * 2) - 10;
        if (y + th + pad * 2 > this.height - 4) y = mouseY - (th + pad * 2) - 10;

        context.fill(x, y, x + tw + pad * 2, y + th + pad * 2, 0xEE000000);
        drawRectBorder(context, x, y, tw + pad * 2, th + pad * 2, 0xFF777777);
        context.drawTextWithShadow(this.textRenderer, text, x + pad, y + pad, 0xFFFFFF);
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private record RecordRow(
            long submittedAtMs,
            String track,
            String timeStr,
            String bodyName,
            String tireName,
            String engineName,
            String modes
    ) {}
}