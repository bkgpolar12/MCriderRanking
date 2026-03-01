// RiderFindScreen.java
package com.example.rankinglog;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.*;

public class RiderFindScreen extends Screen {

    private final Screen parent;

    private TextFieldWidget input;

    private final List<String> allPlayers = new ArrayList<>();
    private final List<String> filtered = new ArrayList<>();

    private int page = 0;
    private int rowsPerPage = 10;

    private boolean loading = false;
    private String error = null;

    private ButtonWidget backBtn;
    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;
    private ButtonWidget refreshBtn;

    private static final int OUTER_PAD = 12;

    public RiderFindScreen(Screen parent) {
        super(Text.literal("라이더 찾기"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        backBtn = ButtonWidget.builder(Text.literal("뒤로"), b -> {
            if (this.client != null) this.client.setScreen(parent);
        }).dimensions(OUTER_PAD + 8, this.height - 28, 70, 20).build();
        addDrawableChild(backBtn);

        prevBtn = ButtonWidget.builder(Text.literal("◀"), b -> {
            if (page > 0) page--;
            updateButtons();
        }).dimensions(cx - 70, this.height - 28, 20, 20).build();
        addDrawableChild(prevBtn);

        nextBtn = ButtonWidget.builder(Text.literal("▶"), b -> {
            if ((page + 1) * rowsPerPage < filtered.size()) page++;
            updateButtons();
        }).dimensions(cx + 50, this.height - 28, 20, 20).build();
        addDrawableChild(nextBtn);

        refreshBtn = ButtonWidget.builder(Text.literal("새로 고침"), b -> {
            loading = true;
            error = null;
            updateButtons();

            RankingScreen.ApiCache.fetchAllAsync(true, p -> {
                loading = false;
                error = null;
                rebuildPlayerList();
                page = 0;
                applyFilter();     //상태가 바뀐 시점에만 필터
                updateButtons();
            }, err -> {
                loading = false;
                error = err;
                updateButtons();
            });
        }).dimensions(this.width - 92, this.height - 28, 80, 20).build();
        addDrawableChild(refreshBtn);

        input = new TextFieldWidget(this.textRenderer, cx - 150, 42, 300, 20, Text.literal(""));
        input.setMaxLength(32);
        input.setChangedListener(s -> {
            page = 0;
            applyFilter();     //텍스트 변경 시에만 필터
            updateButtons();
        });
        addSelectableChild(input);
        setInitialFocus(input);

        // 초기 로드
        rebuildPlayerList();
        page = 0;
        applyFilter();
        updateButtons();
    }

    //화면 크기에 따라 rowsPerPage 자동 계산
    private void computeRowsPerPage() {
        int tableY = 80;
        int tableH = this.height - 46 - tableY;

        int headerRow = 18;
        int padding = 18;
        int rowH = 18;

        int available = Math.max(1, tableH - headerRow - padding);
        int possible = Math.max(1, available / rowH);

        rowsPerPage = Math.min(14, possible);
        if (rowsPerPage < 1) rowsPerPage = 1;
    }

    private void rebuildPlayerList() {
        allPlayers.clear();

        RankingScreen.ApiCache.AllPayload all = RankingScreen.ApiCache.getAllIfReady();
        if (all == null) return;

        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (var it : all.rankingsByTrack.entrySet()) {
            for (RankingScreen.Entry e : it.getValue().ranking) {
                String name = (e.player() == null) ? "" : e.player().trim();
                if (!name.isBlank()) uniq.add(name);
            }
        }
        allPlayers.addAll(uniq);
        allPlayers.sort(String.CASE_INSENSITIVE_ORDER);
    }

    private void applyFilter() {
        computeRowsPerPage();

        filtered.clear();
        String q = (input == null) ? "" : input.getText().trim().toLowerCase(Locale.ROOT);

        for (String p : allPlayers) {
            if (q.isEmpty() || p.toLowerCase(Locale.ROOT).contains(q)) {
                filtered.add(p);
            }
        }

        //page clamp
        int maxPage = Math.max(0, (filtered.size() - 1) / rowsPerPage);
        if (page > maxPage) page = maxPage;
        if (page < 0) page = 0;
    }

    private void updateButtons() {
        computeRowsPerPage();

        if (prevBtn != null) prevBtn.active = (!loading && error == null && page > 0);
        if (nextBtn != null) nextBtn.active = (!loading && error == null && (page + 1) * rowsPerPage < filtered.size());
    }

    //블러 완전 차단(안전장치)
    @Override
    protected void applyBlur() { }
    @Override
    public void blur() { }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x88000000);
    }

    //리사이즈되면 rowsPerPage/페이지 다시 맞추기
    @Override
    public void resize(net.minecraft.client.MinecraftClient client, int width, int height) {
        super.resize(client, width, height);
        applyFilter();
        updateButtons();
    }

    //버튼 누른 뒤에도 입력이 잘 되게 키 입력을 TextField에 넘김
    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (input != null && input.charTyped(chr, modifiers)) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (input != null && input.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (input != null && input.mouseClicked(mouseX, mouseY, button)) return true;

        if (!loading && error == null && !filtered.isEmpty()) {
            int tableX = OUTER_PAD + 8;
            int tableY = 80;
            int tableW = this.width - (OUTER_PAD + 8) * 2;
            int tableH = this.height - 46 - tableY;

            int start = page * rowsPerPage;
            int end = Math.min(start + rowsPerPage, filtered.size());

            int y = tableY + 24;
            int rowH = 18;

            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableY && mouseY <= tableY + tableH) {
                for (int i = start; i < end; i++) {
                    String name = filtered.get(i);

                    int nameX = tableX + 16;
                    int nameY = y;
                    int nameW = this.textRenderer.getWidth(name);

                    boolean hit = mouseX >= nameX && mouseX <= nameX + nameW
                            && mouseY >= nameY - 2 && mouseY <= nameY + 10;

                    if (hit) {
                        if (this.client != null) {
                            this.client.setScreen(new PlayerProfileScreen(name, this));
                        }
                        return true;
                    }
                    y += rowH;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, "라이더 찾기", width / 2, 18, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, "닉네임을 입력하세요", width / 2, 30, 0xBBBBBB);

        if (input != null) input.render(context, mouseX, mouseY, delta);

        int tableX = OUTER_PAD + 8;
        int tableY = 80;
        int tableW = this.width - (OUTER_PAD + 8) * 2;
        int tableH = this.height - 46 - tableY;

        context.fill(tableX, tableY, tableX + tableW, tableY + tableH, 0x66000000);
        drawRectBorder(context, tableX, tableY, tableW, tableH, 0xFF222222);

        if (loading) {
            context.drawCenteredTextWithShadow(textRenderer, "불러오는 중...", width / 2, tableY + 26, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (error != null) {
            context.drawCenteredTextWithShadow(textRenderer, "오류: " + error, width / 2, tableY + 26, 0xFF5555);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        //render에서는 상태 변경(필터/버튼 갱신) 절대 하지 않음!
        context.drawTextWithShadow(textRenderer, "닉네임 (클릭하면 프로필)", tableX + 16, tableY + 8, 0xDDDDDD);

        int start = page * rowsPerPage;
        int end = Math.min(start + rowsPerPage, filtered.size());

        int y = tableY + 24;
        int rowH = 18;

        for (int i = start; i < end; i++) {
            String name = filtered.get(i);

            boolean hover = mouseX >= tableX + 16 && mouseX <= tableX + 16 + textRenderer.getWidth(name)
                    && mouseY >= y - 2 && mouseY <= y + 10;

            int color = hover ? 0xFFFFEE88 : 0xFFFFFF;
            context.drawTextWithShadow(textRenderer, name, tableX + 16, y, color);

            y += rowH;
        }

        int totalPages = Math.max(1, (filtered.size() + rowsPerPage - 1) / rowsPerPage);
        String pageInfo = String.format("페이지 %d / %d", (page + 1), totalPages);
        context.drawCenteredTextWithShadow(textRenderer, pageInfo, width / 2, height - 26, 0xAAAAAA);

        super.render(context, mouseX, mouseY, delta);
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
}