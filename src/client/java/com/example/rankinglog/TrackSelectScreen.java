package com.example.rankinglog;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class TrackSelectScreen extends Screen {

    private static final int PAGE_SIZE = 10;

    private final Screen parent;
    private final String initialTrack;

    // ✅ 트랙 선택 시 RankingScreen에 전달할 콜백
    private final Consumer<String> onSelectTrack;

    private final List<TrackEntry> all = new ArrayList<>();
    private final List<TrackEntry> filtered = new ArrayList<>();

    private int page = 0;
    private boolean loading = true;
    private String error = null;

    private TextFieldWidget searchBox;
    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;

    public TrackSelectScreen(Screen parent, String initialTrack, Consumer<String> onSelectTrack) {
        super(Text.literal("트랙 선택"));
        this.parent = parent;
        this.initialTrack = initialTrack;
        this.onSelectTrack = onSelectTrack;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // 검색창
        searchBox = new TextFieldWidget(
                this.textRenderer,
                cx - 100, 28,
                200, 18,
                Text.literal("검색")
        );
        searchBox.setMaxLength(64);
        searchBox.setChangedListener(s -> {
            page = 0;
            applySearch();
            updateButtons();
        });
        addDrawableChild(searchBox);

        // 페이지 버튼
        prevBtn = ButtonWidget.builder(Text.literal("◀"), b -> {
            if (page > 0) page--;
            updateButtons();
        }).dimensions(cx - 60, this.height - 28, 20, 20).build();

        nextBtn = ButtonWidget.builder(Text.literal("▶"), b -> {
            if ((page + 1) * PAGE_SIZE < filtered.size()) page++;
            updateButtons();
        }).dimensions(cx + 40, this.height - 28, 20, 20).build();

        addDrawableChild(prevBtn);
        addDrawableChild(nextBtn);

        // 닫기 버튼
        addDrawableChild(
                ButtonWidget.builder(Text.literal("닫기"), b -> close())
                        .dimensions(cx - 30, this.height - 28, 60, 20)
                        .build()
        );

        updateButtons();
        setInitialFocus(searchBox);

        // ✅ 캐시가 있으면 즉시 표시, 없으면 1번만 fetch
        loadTrackListFromCacheOrFetch();
    }

    private void loadTrackListFromCacheOrFetch() {
        loading = true;
        error = null;
        all.clear();
        filtered.clear();
        page = 0;
        updateButtons();

        // 1) 캐시 즉시 표시
        List<TrackEntry> cached = RankingScreen.ApiCache.getCachedTracksIfFresh();
        if (cached != null) {
            all.addAll(cached);
            all.sort(Comparator.comparingInt(TrackEntry::count).reversed());
            applySearch();
            loading = false;
            updateButtons();
            return;
        }

        // 2) 없으면 fetch
        RankingScreen.ApiCache.fetchTrackListIfNeededAsync(list -> {
            if (list != null) {
                all.addAll(list);
                all.sort(Comparator.comparingInt(TrackEntry::count).reversed());
                applySearch();
            }
            loading = false;
            updateButtons();
        }, err -> {
            error = err;
            loading = false;
            updateButtons();
        });
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    private void updateButtons() {
        if (prevBtn != null) prevBtn.active = (page > 0);
        if (nextBtn != null) nextBtn.active = (!filtered.isEmpty() && (page + 1) * PAGE_SIZE < filtered.size());
    }

    private void applySearch() {
        filtered.clear();

        String q = searchBox == null ? "" : searchBox.getText().trim().toLowerCase();
        if (q.isEmpty()) {
            filtered.addAll(all);
        } else {
            for (TrackEntry e : all) {
                if (e.track.toLowerCase().contains(q)) filtered.add(e);
            }
        }

        int maxPage = Math.max(0, (filtered.size() - 1) / PAGE_SIZE);
        if (page > maxPage) page = maxPage;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int cx = this.width / 2;

        context.drawCenteredTextWithShadow(this.textRenderer,
                "트랙 선택 (기록 많은 순) | 현재: " + initialTrack,
                cx, 10, 0xFFFFFF);

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "불러오는 중...",
                    cx, 70, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        if (error != null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "오류: " + error,
                    cx, 70, 0xFF5555);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        if (filtered.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "트랙이 없습니다.",
                    cx, 70, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        int startY = 60;
        int lineH = 14;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, filtered.size());

        for (int i = start; i < end; i++) {
            TrackEntry te = filtered.get(i);

            String line = String.format("%d. %s  (기록 %d개)",
                    (i + 1),
                    te.track,
                    te.count
            );

            int y = startY + (i - start) * lineH;
            context.drawCenteredTextWithShadow(this.textRenderer, line, cx, y, 0xFFFFFF);
        }

        String pageInfo = String.format("페이지 %d / %d",
                (page + 1),
                (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);

        context.drawCenteredTextWithShadow(this.textRenderer, pageInfo, cx, this.height - 46, 0xAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = this.width / 2;
        int startY = 60;
        int lineH = 14;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, filtered.size());

        int clickableHalfWidth = 180;

        for (int i = start; i < end; i++) {
            int y = startY + (i - start) * lineH;

            if (mouseY >= y - 2 && mouseY <= y + 10) {
                if (mouseX >= cx - clickableHalfWidth && mouseX <= cx + clickableHalfWidth) {
                    String selectedTrack = filtered.get(i).track;

                    // ✅ RankingScreen을 새로 만들지 않고 콜백으로 현재 화면 갱신
                    if (onSelectTrack != null) onSelectTrack.accept(selectedTrack);

                    close();
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ✅ 캐시에 쓰려고 public static
    public static record TrackEntry(String track, int count) {}
}
