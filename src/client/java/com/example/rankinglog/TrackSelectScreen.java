// TrackSelectScreen.java
package com.example.rankinglog;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class TrackSelectScreen extends Screen {

    private static final int PAGE_SIZE_MAX = 10;
    private final Screen parent;
    private final String initialTrack;
    private final Consumer<String> onSelectTrack;
    private final List<TrackEntry> all = new ArrayList<>();
    private final List<TrackEntry> filtered = new ArrayList<>();

    private int page = 0;
    private int rowsPerPage = 8;
    private boolean loading = true;
    private String error = null;

    private TextFieldWidget searchBox;
    private ButtonWidget prevBtn, nextBtn, refreshBtn, closeBtn;

    private static final int OUTER_PAD = 12, HEADER_TOP = 10, HOVER_YELLOW = 0xFFFFEE88;

    public TrackSelectScreen(Screen parent, String initialTrack, Consumer<String> onSelectTrack) {
        super(Text.literal("트랙 선택"));
        this.parent = parent;
        this.initialTrack = initialTrack;
        this.onSelectTrack = onSelectTrack;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        searchBox = new TextFieldWidget(this.textRenderer, cx - 160, HEADER_TOP + 26, 320, 18, Text.literal("검색"));
        searchBox.setMaxLength(64);
        searchBox.setChangedListener(s -> {
            page = 0;
            applySearch();
            updateButtons();
        });
        addDrawableChild(searchBox);

        prevBtn = ButtonWidget.builder(Text.literal("◀"), b -> {
            if (page > 0) page--;
            updateButtons();
        }).dimensions(cx - 70, this.height - 28, 20, 20).build();
        nextBtn = ButtonWidget.builder(Text.literal("▶"), b -> {
            if ((page + 1) * rowsPerPage < filtered.size()) page++;
            updateButtons();
        }).dimensions(cx + 50, this.height - 28, 20, 20).build();
        closeBtn = ButtonWidget.builder(Text.literal("닫기"), b -> close()).dimensions(cx - 30, this.height - 28, 60, 20).build();
        addDrawableChild(prevBtn);
        addDrawableChild(nextBtn);
        addDrawableChild(closeBtn);

        refreshBtn = ButtonWidget.builder(Text.literal("새로 고침"), b -> {
            playUiClick();
            loading = true;
            error = null;
            RankingScreen.ApiCache.fetchAllAsync(true, p -> {
                loading = false;
                error = null;
                loadFromCache();
            }, err -> {
                loading = false;
                error = err;
            });
        }).dimensions(this.width - 92, this.height - 28, 80, 20).build();
        addDrawableChild(refreshBtn);

        setInitialFocus(searchBox);
        if (RankingScreen.ApiCache.isAllReady()) {
            loading = false;
            loadFromCache();
        } else {
            loading = true;
            error = null;
            RankingScreen.ApiCache.fetchAllAsync(false, p -> {
                loading = false;
                error = null;
                loadFromCache();
            }, err -> {
                loading = false;
                error = err;
            });
        }
        updateButtons();
    }

    private void playUiClick() {
        if (this.client == null) return;
        this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private void computeRowsPerPage() {
        rowsPerPage = Math.min(PAGE_SIZE_MAX, Math.max(1, Math.max(1, this.height - 46 - (HEADER_TOP + 58) - 18 - 18) / 18));
    }

    private void loadFromCache() {
        all.clear();
        filtered.clear();
        page = 0;
        RankingScreen.ApiCache.AllPayload p = RankingScreen.ApiCache.getAllIfReady();
        if (p != null && p.tracks != null) {
            all.addAll(p.tracks);
            all.sort(Comparator.comparingInt(TrackEntry::count).reversed());
            applySearch();
        }
        updateButtons();
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    private void updateButtons() {
        computeRowsPerPage();
        if (prevBtn != null) prevBtn.active = (page > 0);
        if (nextBtn != null) nextBtn.active = (!filtered.isEmpty() && (page + 1) * rowsPerPage < filtered.size());
    }

    private void applySearch() {
        filtered.clear();
        String q = searchBox == null ? "" : searchBox.getText().trim().toLowerCase();
        if (q.isEmpty()) filtered.addAll(all);
        else {
            for (TrackEntry e : all) if (e.track.toLowerCase().contains(q)) filtered.add(e);
        }
        computeRowsPerPage();
        int maxPage = Math.max(0, (filtered.size() - 1) / rowsPerPage);
        if (page > maxPage) page = maxPage;
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
        int headerX = OUTER_PAD;
        int headerY = HEADER_TOP;
        int headerW = this.width - OUTER_PAD * 2;
        int headerH = 52;
        context.fill(headerX, headerY, headerX + headerW, headerY + headerH, 0xCC000000);
        drawRectBorder(context, headerX, headerY, headerW, headerH, 0xFF2A2A2A);
        context.drawCenteredTextWithShadow(this.textRenderer, "트랙 선택", cx, headerY + 8, 0xFFFFFF);

        int listTop = HEADER_TOP + 58;
        int listX = OUTER_PAD;
        int listW = this.width - OUTER_PAD * 2;
        int listH = Math.max(80, this.height - 46 - listTop);
        context.fill(listX, listTop, listX + listW, listTop + listH, 0x66000000);
        drawRectBorder(context, listX, listTop, listW, listH, 0xFF222222);

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer, "불러오는 중...", cx, listTop + 24, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (error != null) {
            String displayError = error.toLowerCase().contains("http") ? "이 버전은 서비스 종료 되었습니다. 최신 버전을 이용해 주세요." : error;
            context.drawCenteredTextWithShadow(this.textRenderer, "오류: " + displayError, cx, listTop + 24, 0xFF5555);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (all.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, "데이터가 없습니다. 우측 하단 '새로 고침'을 눌러주세요.", cx, listTop + 24, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (filtered.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, "트랙이 없습니다.", cx, listTop + 24, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        computeRowsPerPage();
        int colY = listTop + 8;
        context.drawTextWithShadow(this.textRenderer, "순위", listX + 20, colY, 0xDDDDDD);
        context.drawTextWithShadow(this.textRenderer, "트랙", listX + 90, colY, 0xDDDDDD);
        context.drawTextWithShadow(this.textRenderer, "기록", listX + listW - 60, colY, 0xDDDDDD);

        int rowStartY = listTop + 24;
        int rowH = 18;
        int start = page * rowsPerPage;
        int end = Math.min(start + rowsPerPage, filtered.size());
        for (int i = start; i < end; i++) {
            TrackEntry te = filtered.get(i);
            int idxInPage = i - start;
            int y = rowStartY + idxInPage * rowH;
            if ((idxInPage & 1) == 1) context.fill(listX + 1, y - 2, listX + listW - 1, y + rowH - 1, 0x22000000);
            int trackX = listX + 90;
            int trackW = this.textRenderer.getWidth(te.track);
            boolean hoverTrack = mouseX >= trackX && mouseX <= trackX + trackW && mouseY >= y - 2 && mouseY <= y - 2 + 9 + 6;
            boolean isCur = te.track.equals(initialTrack);
            int trackColor = hoverTrack ? HOVER_YELLOW : (isCur ? 0xFF55FF55 : 0xFFFFFFFF);

            context.drawTextWithShadow(this.textRenderer, String.valueOf(i + 1), listX + 22, y, 0xFFFFFF);
            context.drawTextWithShadow(this.textRenderer, te.track, trackX, y, trackColor);
            context.drawTextWithShadow(this.textRenderer, te.count + "개", listX + listW - 60, y, 0xFFFFFF);
        }

        int totalPages = Math.max(1, (filtered.size() + rowsPerPage - 1) / rowsPerPage);
        context.drawCenteredTextWithShadow(this.textRenderer, String.format("페이지 %d / %d", (page + 1), totalPages), cx, this.height - 46, 0xAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int listTop = HEADER_TOP + 58;
        int listX = OUTER_PAD;
        int listW = this.width - OUTER_PAD * 2;
        int rowStartY = listTop + 24;
        int rowH = 18;
        computeRowsPerPage();
        int start = page * rowsPerPage;
        int end = Math.min(start + rowsPerPage, filtered.size());

        for (int i = start; i < end; i++) {
            int idx = i - start;
            int y = rowStartY + idx * rowH;
            if (mouseY >= y - 2 && mouseY <= y + rowH - 2 && mouseX >= listX + 8 && mouseX <= listX + listW - 8) {
                playUiClick();
                String selectedTrack = filtered.get(i).track;
                if (onSelectTrack != null) onSelectTrack.accept(selectedTrack);
                close();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public static record TrackEntry(String track, int count) {
    }
}