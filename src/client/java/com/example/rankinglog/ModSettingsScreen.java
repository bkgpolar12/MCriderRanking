package com.example.rankinglog;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ModSettingsScreen extends Screen {

    private final Screen parent;

    // ★ 드롭다운 상태 관리 변수들
    private boolean trackDropdownOpen = false;
    private TextFieldWidget trackSearchBox;
    private final List<String> allTracks = new ArrayList<>();
    private final List<String> filteredTracks = new ArrayList<>();
    private int trackScroll = 0;
    private ButtonWidget trackToggleBtn;

    // 드롭다운 패널 좌표 보관
    private int panelX, panelY, panelW, panelH;

    public ModSettingsScreen(Screen parent) {
        super(Text.literal("MCRiderRanking 설정"));
        this.parent = parent;
    }

    private static String getModVersion(){
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("modid");
        return modContainer.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("1.6.99");
    }

    private void playUiClick() {
        if (this.client != null) {
            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        // ★ 레이아웃 수정: 너비를 넓히고 높이를 대폭 줄임 (2열 배치)
        int boxW = 420;
        int boxH = 175;
        int boxY = cy - boxH / 2;

        int leftX = cx - 190;  // 왼쪽 열 X 좌표
        int rightX = cx + 10;  // 오른쪽 열 X 좌표
        int btnW = 180;        // 버튼 너비
        int gap = 24;          // 세로 간격
        int startY = boxY + 40;

        // ===== [왼쪽 열] 일반 설정들 =====

        // 1. 자동 기록 등록 토글
        this.addDrawableChild(ButtonWidget.builder(getAutoSubmitText(), btn -> {
            playUiClick();
            ModConfig.get().autoSubmitEnabled = !ModConfig.get().autoSubmitEnabled;
            ModConfig.save();
            btn.setMessage(getAutoSubmitText());
        }).dimensions(leftX, startY, btnW, 20).build());

        // 2. 로그 출력 토글
        this.addDrawableChild(ButtonWidget.builder(getDebugLogText(), btn -> {
            playUiClick();
            ModConfig.get().debugLogEnabled = !ModConfig.get().debugLogEnabled;
            ModConfig.save();
            btn.setMessage(getDebugLogText());
        }).dimensions(leftX, startY + gap, btnW, 20).build());

        // 3. 배경 불투명도 조절 슬라이더
        double initialAlphaValue = ModConfig.get().backgroundAlpha / 255.0;
        this.addDrawableChild(new SliderWidget(leftX, startY + gap * 2, btnW, 20, getBgAlphaText(), initialAlphaValue) {
            @Override protected void updateMessage() { this.setMessage(getBgAlphaText()); }
            @Override protected void applyValue() {
                ModConfig.get().backgroundAlpha = (int) (this.value * 255);
                ModConfig.save();
            }
        });

        // 4. 캐시 유지 시간 설정 슬라이더
        double minTtl = 30.0, maxTtl = 600.0;
        double currentTtl = ModConfig.get().cacheTtlSeconds;
        double initialTtlValue = (currentTtl - minTtl) / (maxTtl - minTtl);
        this.addDrawableChild(new SliderWidget(leftX, startY + gap * 3, btnW, 20, getCacheTtlText(), initialTtlValue) {
            @Override protected void updateMessage() { this.setMessage(getCacheTtlText()); }
            @Override protected void applyValue() {
                ModConfig.get().cacheTtlSeconds = (int) (minTtl + (this.value * (maxTtl - minTtl)));
                ModConfig.save();
            }
        });

        // 5. 모든 캐시 수동 초기화 버튼
        this.addDrawableChild(ButtonWidget.builder(Text.literal("⚠ 모든 캐시 즉시 초기화"), btn -> {
            playUiClick();
            RankingScreen.ApiCache.clearCache();
            EventOptionSelectScreen.clearCache();
            EventRankingScreen.clearCache();
            if (this.client != null && this.client.player != null) {
                this.client.player.sendMessage(Text.literal("§e[System] 모든 랭킹 데이터 캐시가 초기화되었습니다."), false);
            }
        }).dimensions(leftX, startY + gap * 4, btnW, 20).build());


        // ===== [오른쪽 열] 기본 트랙 설정 =====

        int configStartY = startY + 28; // 설명 텍스트 아래에 배치하기 위한 Y 좌표

        trackToggleBtn = ButtonWidget.builder(Text.literal("기본: " + ModConfig.get().defaultTrack), btn -> {
            playUiClick();
            trackDropdownOpen = !trackDropdownOpen;
            if (trackDropdownOpen) {
                panelW = trackToggleBtn.getWidth();
                panelX = trackToggleBtn.getX();
                panelY = trackToggleBtn.getY() + 22;

                trackSearchBox.setX(panelX + 10);
                trackSearchBox.setY(panelY + 10);

                trackSearchBox.setVisible(true);
                trackSearchBox.setText("");
                trackSearchBox.setFocused(true);
                this.setFocused(trackSearchBox);

                applyTrackSearch();
            } else {
                trackSearchBox.setVisible(false);
                trackSearchBox.setFocused(false);
            }
        }).dimensions(rightX, configStartY, btnW, 20).build();
        this.addDrawableChild(trackToggleBtn);

        trackSearchBox = new TextFieldWidget(this.textRenderer, 0, 0, btnW - 20, 14, Text.literal("트랙 검색"));
        trackSearchBox.setMaxLength(64);
        trackSearchBox.setVisible(false);
        trackSearchBox.setChangedListener(s -> {
            trackScroll = 0;
            applyTrackSearch();
        });
        this.addSelectableChild(trackSearchBox);

        // ApiCache에서 전체 트랙 목록 미리 불러오기
        if (RankingScreen.ApiCache.isAllReady()) {
            loadTracksFromCache();
        } else {
            RankingScreen.ApiCache.fetchAllAsync(false, p -> loadTracksFromCache(), err -> {});
        }
    }

    private void loadTracksFromCache() {
        RankingScreen.ApiCache.AllPayload payload = RankingScreen.ApiCache.getAllIfReady();
        allTracks.clear();
        if (payload != null && payload.tracks != null) {
            for (TrackSelectScreen.TrackEntry t : payload.tracks) {
                allTracks.add(t.track());
            }
        } else {
            allTracks.add("[α] 빌리지 고가의 질주"); // 기본값
        }
        applyTrackSearch();
    }

    private void applyTrackSearch() {
        filteredTracks.clear();
        String query = trackSearchBox.getText().toLowerCase().trim();
        if (query.isEmpty()) {
            filteredTracks.addAll(allTracks);
        } else {
            for (String t : allTracks) {
                if (t.toLowerCase().contains(query)) {
                    filteredTracks.add(t);
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (trackDropdownOpen) {
            if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= panelY && mouseY <= panelY + panelH) {
                int visibleRows = (panelH - 34) / 16;
                int maxScroll = Math.max(0, filteredTracks.size() - visibleRows);
                if (verticalAmount > 0) trackScroll--;
                else if (verticalAmount < 0) trackScroll++;

                trackScroll = Math.clamp(trackScroll, 0, maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (trackDropdownOpen) {
            // 1. 패널 내부를 클릭한 경우
            if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= panelY && mouseY <= panelY + panelH) {

                // 검색창 클릭 허용
                if (trackSearchBox.isMouseOver(mouseX, mouseY)) {
                    trackSearchBox.mouseClicked(mouseX, mouseY, button);
                    trackSearchBox.setFocused(true);
                    this.setFocused(trackSearchBox);
                    return true;
                }

                // 리스트 아이템 클릭 처리
                int listY = panelY + 34;
                int rowH = 16;
                if (mouseY >= listY) {
                    int clickIdx = (int) ((mouseY - listY) / rowH) + trackScroll;
                    if (clickIdx >= 0 && clickIdx < filteredTracks.size()) {
                        ModConfig.get().defaultTrack = filteredTracks.get(clickIdx);
                        ModConfig.save();
                        trackToggleBtn.setMessage(Text.literal("기본: " + ModConfig.get().defaultTrack));

                        trackDropdownOpen = false;
                        trackSearchBox.setVisible(false);
                        trackSearchBox.setFocused(false);
                        playUiClick();
                    }
                }
                return true;
            }
            // 2. 토글 버튼을 정확히 클릭한 경우
            else if (trackToggleBtn != null && trackToggleBtn.isMouseOver(mouseX, mouseY)) {
                // 버튼의 onClick 로직이 실행되며 자연스럽게 닫힙니다.
            }
            // 3. 화면 바깥의 다른 곳을 클릭한 경우
            else {
                trackDropdownOpen = false;
                trackSearchBox.setVisible(false);
                trackSearchBox.setFocused(false);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (trackDropdownOpen && trackSearchBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (trackDropdownOpen && trackSearchBox.charTyped(chr, modifiers)) return true;
        return super.charTyped(chr, modifiers);
    }

    private Text getAutoSubmitText() { return Text.literal("자동 기록 등록: " + (ModConfig.get().autoSubmitEnabled ? "§aON" : "§cOFF")); }
    private Text getDebugLogText() { return Text.literal("로그 출력: " + (ModConfig.get().debugLogEnabled ? "§aON" : "§cOFF")); }
    private Text getBgAlphaText() { return Text.literal("배경 불투명도: " + (int) Math.round((ModConfig.get().backgroundAlpha / 255.0) * 100) + "%"); }
    private Text getCacheTtlText() {
        int sec = ModConfig.get().cacheTtlSeconds;
        return Text.literal("캐시 갱신 주기: §e" + (sec >= 60 ? (sec / 60) + "분 " + (sec % 60) + "초" : sec + "초"));
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
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
        int cy = this.height / 2;

        // ★ 렌더링 박스 크기 및 위치 업데이트
        int boxW = 420;
        int boxH = 175;
        int boxX = cx - boxW / 2;
        int boxY = cy - boxH / 2;
        int rightX = cx + 10;

        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xCC000000);
        drawRectBorder(context, boxX, boxY, boxW, boxH, 0xFF2A2A2A);

        context.drawCenteredTextWithShadow(this.textRenderer, "§6§l" + this.title.getString(), cx, boxY + 12, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, "§7V" + getModVersion(), cx, boxY + 24, 0xFFFFFF);

        // ★ 오른쪽 열 상단에 설명 텍스트 렌더링
        context.drawTextWithShadow(this.textRenderer, "§e[ 기본 트랙 설정 ]", rightX, boxY + 40, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "§7로비 디스플레이 미감지 시 표시됩니다.", rightX, boxY + 52, 0xAAAAAA);

        super.render(context, mouseX, mouseY, delta);

        // 드롭다운 렌더링 (가장 마지막에 렌더링하여 다른 UI 위에 덮어씌움)
        if (trackDropdownOpen && trackToggleBtn != null) {
            panelW = trackToggleBtn.getWidth();
            panelX = trackToggleBtn.getX();
            panelY = trackToggleBtn.getY() + 22;
            panelH = 140;

            context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFA0F0F0F);
            drawRectBorder(context, panelX, panelY, panelW, panelH, 0xFF555555);

            trackSearchBox.setX(panelX + 10);
            trackSearchBox.setY(panelY + 10);
            trackSearchBox.render(context, mouseX, mouseY, delta);

            int listY = panelY + 34;
            int rowH = 16;
            int visibleRows = (panelH - 34) / rowH;

            int maxScroll = Math.max(0, filteredTracks.size() - visibleRows);
            if (trackScroll > maxScroll) trackScroll = maxScroll;

            for (int i = 0; i < visibleRows; i++) {
                int idx = trackScroll + i;
                if (idx >= filteredTracks.size()) break;

                String tr = filteredTracks.get(idx);
                int itemY = listY + i * rowH;

                boolean hover = mouseX >= panelX && mouseX < panelX + panelW && mouseY >= itemY && mouseY < itemY + rowH;
                if (hover) context.fill(panelX + 1, itemY, panelX + panelW - 1, itemY + rowH, 0xFF222222);

                if (tr.equals(ModConfig.get().defaultTrack)) {
                    context.fill(panelX + 1, itemY, panelX + panelW - 1, itemY + rowH, 0xFF153015);
                }

                String displayTr = this.textRenderer.getWidth(tr) > panelW - 20 ? this.textRenderer.trimToWidth(tr, panelW - 24) + ".." : tr;
                context.drawTextWithShadow(this.textRenderer, displayTr, panelX + 8, itemY + 4, 0xFFFFFF);
            }

            if (maxScroll > 0) {
                int scrollW = 6;
                int barX = panelX + panelW - scrollW - 1;
                context.fill(barX, listY, barX + scrollW, listY + visibleRows * rowH, 0xFF111111);

                float ratio = (float) visibleRows / filteredTracks.size();
                int thumbH = Math.max(10, (int) ((visibleRows * rowH) * ratio));
                float pos = (float) trackScroll / maxScroll;
                int thumbY = listY + (int) (((visibleRows * rowH) - thumbH) * pos);

                context.fill(barX, thumbY, barX + scrollW, thumbY + thumbH, 0xFF555555);
            }
        } else {
            trackSearchBox.setVisible(false);
            trackSearchBox.setFocused(false);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}