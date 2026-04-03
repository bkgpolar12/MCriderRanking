package com.example.rankinglog;

import com.example.mixin.client.InGameHudMixin;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Unique;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class EventOptionSelectScreen extends Screen {

    // ===== 캐시 =====
    private static List<EventEntry> cachedEvents = null;
    private static long cachedAt = 0;

    // 1분 TTL
    private static final long TTL_MS = 60_000;

    private static boolean isCacheValid() {
        if (cachedEvents == null) return false;
        return System.currentTimeMillis() - cachedAt <= TTL_MS;
    }

    private final Screen parent;

    private final List<EventEntry> events = new ArrayList<>();

    private static final Set<String> ALLOWED_PLAYERS = Set.of(
            "BKGpolar"
    );

    private boolean loading = true;

    private static final int OUTER_PAD = 15;
    private static final int CARD_H = 55;
    private static final int START_Y = 45;

    public record EventEntry(
            String name,
            String startDate,
            String endDate,
            String track,
            String mode,
            String kart,
            String engine,
            String tire,
            String sheetTitle,
            String eventID,
            String visible
    ) {}

    public EventOptionSelectScreen(Screen parent) {
        super(Text.literal("이벤트 선택"));
        this.parent = parent;
    }

    @Override
    protected void init() {

        if (isCacheValid()) {
            events.clear();
            events.addAll(cachedEvents);
            loading = false;
        } else {
            fetchEvents();
        }

        rebuildUI();
    }

    private void rebuildUI() {

        clearChildren();

        int y = height - 30;

// 버튼 너비 설정 (기호 전용 20x20 정사각형)
        int iconBtnSize = 20;
        int margin = 12; // 기존 margin 값 유지 또는 설정
        int gap = 5;    // 버튼 사이 간격

// 1. 새로고침 (가장 오른쪽 끝)
        addDrawableChild(
                ButtonWidget.builder(Text.literal("🔄"), b -> {
                            loading = true;
                            cachedEvents = null;
                            events.clear();
                            fetchEvents();
                        })
                        .dimensions(this.width - iconBtnSize - margin, y, iconBtnSize, 20)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침")))
                        .build()
        );

// 2. 뒤로가기 (새로고침 버튼의 왼쪽)
        addDrawableChild(
                ButtonWidget.builder(Text.literal("⏴"), b ->
                                Objects.requireNonNull(client).setScreen(parent)
                        )
                        .dimensions(this.width - (iconBtnSize * 2) - margin - gap, y, iconBtnSize, 20)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기")))
                        .build()
        );
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {

        int cx = width / 2;
        int cardW = width - 60;

        int left = cx - cardW / 2;
        int right = left + cardW;

        int count = Math.min(3, events.size());

        for (int i = 0; i < count; i++) {

            int y = START_Y + i * (CARD_H + 8);

            if (mouseX >= left && mouseX <= right &&
                    mouseY >= y && mouseY <= y + CARD_H) {

                if (client != null) {
                    client.setScreen(new EventRankingScreen(this, events.get(i)));
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void fetchEvents() {

        new Thread(() -> {

            try {

                URI uri = URI.create(RankingScreen.GAS_URL);

                HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);

                String jsonBody = "{\"action\":\"getEventList\"}";

                try (OutputStream os = con.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                InputStream is = con.getInputStream();
                String res = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                JsonObject obj = JsonParser.parseString(res).getAsJsonObject();

                if (obj.get("ok").getAsBoolean()) {

                    JsonArray arr = obj.getAsJsonArray("events");
                    events.clear();

                    // 현재 플레이어 이름 가져오기 및 개발자 여부 확인
                    MinecraftClient client = MinecraftClient.getInstance();
                    String playerName = (client.player != null) ? client.player.getName().getString() : "";

                    // InGameHudMixin에서 개발자 명단 불러오기 (해당 변수는 public이어야 함)
                    boolean isDev = ALLOWED_PLAYERS.contains(playerName);

                    for (int i = 0; i < arr.size(); i++) {

                        JsonObject o = arr.get(i).getAsJsonObject();

                        // visible 값 가져오기
                        String visibleValue = o.has("visible") ? o.get("visible").getAsString().trim() : "";

                        // 표시 여부 검사 로직
                        boolean shouldShow = true;
                        if ("false-dev".equalsIgnoreCase(visibleValue)) {
                            // false-dev: 개발자에게는 안 보임
                            if (isDev) shouldShow = false;
                        } else if ("true-dev".equalsIgnoreCase(visibleValue)) {
                            // true-dev: 개발자에게만 보임
                            if (!isDev) shouldShow = false;
                        }

                        if (shouldShow) {
                            events.add(new EventEntry(
                                    o.get("eventName").getAsString(),
                                    o.get("startDate").getAsString(),
                                    o.get("endDate").getAsString(),
                                    o.get("track").getAsString(),
                                    o.get("mode").getAsString(),
                                    o.get("kart").getAsString(),
                                    o.get("engine").getAsString(),
                                    o.get("tire").getAsString(),
                                    o.get("sheetTitle").getAsString(),
                                    o.get("eventID").getAsString(),
                                    visibleValue // 추가
                            ));
                        }
                    }

                    cachedEvents = new ArrayList<>(events);
                    cachedAt = System.currentTimeMillis();
                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            loading = false;

        }).start();
    }



    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {

        // (선택 사항) 만약 마인크래프트 특유의 짙은 블러 배경을 커스텀 배경 뒤에 깔고 싶다면 아래 주석을 해제하세요.
        // super.renderBackground(context, mouseX, mouseY, delta);

        int screenW = this.width;
        int screenH = this.height;

        int cardW = screenW - 60;
        int left = (screenW - cardW) / 2;
        int right = left + cardW;

        int centerX = screenW / 2;

        //1. 반투명 배경을 맨 먼저 그림 (가장 밑바탕)
        context.fill(0, 0, screenW, screenH, 0x88000000);

        //2. 헤더 및 UI 그리기
        context.fill(OUTER_PAD, 8, screenW - OUTER_PAD, 28, 0xFF000000);

        String title = "현재 진행 중인 이벤트";
        int titleX = centerX - textRenderer.getWidth(title) / 2;

        context.drawText(textRenderer, title, titleX, 13, 0xFFFFAA00, false);

        //카드 영역
        context.fill(OUTER_PAD, 40, screenW - OUTER_PAD, screenH - 40, 0xFF111111);

        if (loading) {

            String msg = "데이터 로딩 중...";
            int msgX = centerX - textRenderer.getWidth(msg) / 2;

            context.drawText(textRenderer, msg, msgX, screenH / 2, 0xFFFFFFFF, false);

        } else {

            int count = Math.min(3, events.size());

            for (int i = 0; i < count; i++) {

                EventEntry e = events.get(i);

                int y = START_Y + i * (CARD_H + 8);

                boolean hovered =
                        mouseX >= left &&
                                mouseX <= right &&
                                mouseY >= y &&
                                mouseY <= y + CARD_H;

                int boxColor = hovered ? 0xFF444444 : 0xFF222222;
                int borderColor = hovered ? 0xFFFFFFFF : 0xFF666666;

                context.fill(left, y, right, y + CARD_H, boxColor);
                context.drawBorder(left, y, cardW, CARD_H, borderColor);

                String period = e.startDate + " ~ " + e.endDate;
                int periodX = left + 8;

                context.drawText(textRenderer, period, periodX, y + 6, 0xFFAAAAAA, false);

                int nameX = periodX + textRenderer.getWidth(period) + 10;

                context.drawText(textRenderer, e.name, nameX, y + 6, 0xFFFFFF00, false);

                context.fill(left + 5, y + 17, right - 5, y + 18, 0xFF333333);

                int infoY = y + 23;

                int leftCol = left + 8;
                int rightCol = left + cardW / 2 + 20;

                context.drawText(textRenderer,"트랙: " + e.track,leftCol,infoY,0xFFFFFFFF,false);
                context.drawText(textRenderer,"카트: " + formatVal(e.kart),leftCol,infoY + 11,0xFFFFFFFF,false);
                context.drawText(textRenderer,"엔진: " + formatVal(e.engine),leftCol,infoY + 22,0xFFFFFFFF,false);

                context.drawText(textRenderer,"모드: " + formatVal(e.mode),rightCol,infoY + 11,0xFFFFFFFF,false);
                context.drawText(textRenderer,"타이어: " + formatVal(e.tire),rightCol,infoY + 22,0xFFFFFFFF,false);
            }
        }

        //3. 마지막으로 버튼 등 등록된 위젯만 맨 위에 그립니다.
        // 기존의 super.render(...)가 배경을 덮어씌우는 문제를 방지합니다.
        for (Element element : this.children()) {
            if (element instanceof Drawable drawable) {
                drawable.render(context, mouseX, mouseY, delta);
            }
        }
    }

    private String formatVal(String val) {
        if ("ALL".equalsIgnoreCase(val)) return "제한없음";
        if ("uniq[X]".equalsIgnoreCase(val)) return "유니크 카트바디 금지";
        if ("spe[X]".equalsIgnoreCase(val)) return "스페셜 카트바디 금지";
        if ("instant_boost[X]".equalsIgnoreCase(val)) return "순간 부스터 카트바디 금지";
        return val;
    }

}