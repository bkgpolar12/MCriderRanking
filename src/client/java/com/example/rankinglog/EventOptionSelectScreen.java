package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class EventOptionSelectScreen extends Screen {

    private static List<EventEntry> cachedEvents = null;
    private static long cachedAt = 0;
    private static final long TTL_MS = 60_000;

    private static boolean isCacheValid() {
        if (cachedEvents == null) return false;
        return System.currentTimeMillis() - cachedAt <= TTL_MS;
    }

    private final Screen parent;
    private final List<EventEntry> events = new ArrayList<>();
    private static final Set<String> ALLOWED_PLAYERS = Set.of("BKGpolar1");
    private boolean loading = true;

    private static final int OUTER_PAD = 15;
    private static final int CARD_H = 55;
    private static final int START_Y = 45;

    public record EventEntry(String name, String startDate, String endDate, String track,
                             String mode, String kart, String engine, String tire,
                             String sheetTitle, String eventID, String visible) {}

    public EventOptionSelectScreen(Screen parent) {
        super(Text.literal("이벤트 선택"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (isCacheValid()) {
            events.clear(); events.addAll(cachedEvents); loading = false;
        } else fetchEvents();
        rebuildUI();
    }

    private void rebuildUI() {
        clearChildren();
        int y = height - 30;
        int iconBtnSize = 20, margin = 12, gap = 5;

        addDrawableChild(ButtonWidget.builder(Text.literal("🔄"), b -> {
            loading = true; cachedEvents = null; events.clear(); fetchEvents();
        }).dimensions(this.width - iconBtnSize - margin, y, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("⏴"), b -> Objects.requireNonNull(client).setScreen(parent))
                .dimensions(this.width - (iconBtnSize * 2) - margin - gap, y, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = width / 2; int cardW = width - 60;
        int left = cx - cardW / 2; int right = left + cardW;
        int count = Math.min(3, events.size());

        for (int i = 0; i < count; i++) {
            int y = START_Y + i * (CARD_H + 8);
            if (mouseX >= left && mouseX <= right && mouseY >= y && mouseY <= y + CARD_H) {
                if (client != null) { client.setScreen(new EventRankingScreen(this, events.get(i))); return true; }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void fetchEvents() {
        new Thread(() -> {
            try {
                // ===== [수정] Supabase RPC 호출로 변경 =====
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_event_list", "{}");

                if (obj.get("ok").getAsBoolean()) {
                    JsonArray arr = obj.getAsJsonArray("events");
                    events.clear();
                    MinecraftClient client = MinecraftClient.getInstance();
                    String playerName = (client.player != null) ? client.player.getName().getString() : "";
                    boolean isDev = ALLOWED_PLAYERS.contains(playerName);

                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        String visibleValue = o.has("visible") ? o.get("visible").getAsString().trim() : "";
                        boolean shouldShow = true;

                        if ("false-dev".equalsIgnoreCase(visibleValue)) { if (isDev) shouldShow = false; }
                        else if ("true-dev".equalsIgnoreCase(visibleValue)) { if (!isDev) shouldShow = false; }

                        if (shouldShow) {
                            events.add(new EventEntry(
                                    o.get("eventName").getAsString(), o.get("startDate").getAsString(), o.get("endDate").getAsString(),
                                    o.get("track").getAsString(), o.get("mode").getAsString(), o.get("kart").getAsString(),
                                    o.get("engine").getAsString(), o.get("tire").getAsString(), o.get("sheetTitle").getAsString(),
                                    o.get("eventID").getAsString(), visibleValue
                            ));
                        }
                    }
                    cachedEvents = new ArrayList<>(events);
                    cachedAt = System.currentTimeMillis();
                }
            } catch (Exception e) { e.printStackTrace(); }
            loading = false;
        }).start();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int screenW = this.width; int screenH = this.height;
        int cardW = screenW - 60; int left = (screenW - cardW) / 2; int right = left + cardW; int centerX = screenW / 2;

        context.fill(0, 0, screenW, screenH, 0x88000000);
        context.fill(OUTER_PAD, 8, screenW - OUTER_PAD, 28, 0xFF000000);
        String title = "현재 진행 중인 이벤트";
        context.drawText(textRenderer, title, centerX - textRenderer.getWidth(title) / 2, 13, 0xFFFFAA00, false);
        context.fill(OUTER_PAD, 40, screenW - OUTER_PAD, screenH - 40, 0xFF111111);

        if (loading) {
            String msg = "데이터 로딩 중...";
            context.drawText(textRenderer, msg, centerX - textRenderer.getWidth(msg) / 2, screenH / 2, 0xFFFFFFFF, false);
        } else {
            int count = Math.min(3, events.size());
            for (int i = 0; i < count; i++) {
                EventEntry e = events.get(i);
                int y = START_Y + i * (CARD_H + 8);
                boolean hovered = mouseX >= left && mouseX <= right && mouseY >= y && mouseY <= y + CARD_H;
                int boxColor = hovered ? 0xFF444444 : 0xFF222222;
                int borderColor = hovered ? 0xFFFFFFFF : 0xFF666666;

                context.fill(left, y, right, y + CARD_H, boxColor);
                context.drawBorder(left, y, cardW, CARD_H, borderColor);
                String period = e.startDate + " ~ " + e.endDate;
                context.drawText(textRenderer, period, left + 8, y + 6, 0xFFAAAAAA, false);
                context.drawText(textRenderer, e.name, left + 8 + textRenderer.getWidth(period) + 10, y + 6, 0xFFFFFF00, false);
                context.fill(left + 5, y + 17, right - 5, y + 18, 0xFF333333);

                int infoY = y + 23; int leftCol = left + 8; int rightCol = left + cardW / 2 + 20;
                context.drawText(textRenderer,"트랙: " + e.track,leftCol,infoY,0xFFFFFFFF,false);
                context.drawText(textRenderer,"카트: " + formatVal(e.kart),leftCol,infoY + 11,0xFFFFFFFF,false);
                context.drawText(textRenderer,"엔진: " + formatVal(e.engine),leftCol,infoY + 22,0xFFFFFFFF,false);
                context.drawText(textRenderer,"모드: " + formatVal(e.mode),rightCol,infoY + 11,0xFFFFFFFF,false);
                context.drawText(textRenderer,"타이어: " + formatVal(e.tire),rightCol,infoY + 22,0xFFFFFFFF,false);
            }
        }
        for (Element element : this.children()) {
            if (element instanceof Drawable drawable) drawable.render(context, mouseX, mouseY, delta);
        }
    }

    private String formatVal(String val) {
        if ("ALL".equalsIgnoreCase(val)) return "제한없음";
        if ("uniq[X]".equalsIgnoreCase(val)) return "유니크 금지";
        if ("spe[X]".equalsIgnoreCase(val)) return "스페셜 금지";
        if ("instant_boost[X]".equalsIgnoreCase(val)) return "순간 부스터 금지";
        return val;
    }
}