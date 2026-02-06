package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class RankingScreen extends Screen {

    private static final int PAGE_SIZE = 10;

    // ✅ final 제거 (트랙 선택에서 같은 화면 재사용)
    private String track;

    // 서버에서 받은 전체 랭킹
    private final List<Entry> ranking = new ArrayList<>();
    // 필터 적용 결과
    private final List<Entry> filtered = new ArrayList<>();

    private int page = 0;

    private boolean loading = true;
    private String error = null;

    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;

    // 엔진 드롭다운(접기/펼치기)
    private ButtonWidget engineToggleBtn;
    private ButtonWidget trackSelectBtn;
    private boolean enginePanelOpen = false;

    // GAS에서 받아온 엔진 목록(이 트랙에 실제로 존재하는 엔진들)
    private final List<String> engines = new ArrayList<>();
    private final List<ButtonWidget> engineButtons = new ArrayList<>();

    // 기본 선택: X(없으면 ALL)
    private String selectedEngine = "ALL";

    private static final String DEFAULT_TRACK = "[α] 빌리지 고가의 질주";

    public static final String GAS_URL =
            "https://script.google.com/macros/s/AKfycbzwM3cZMnhBMOyXn7d1T9HLHtpcn-C2SuPDOEZNdLEKvqLvjs2fcvBXgpj6gQ1T3V2C/exec";

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

        // 엔진 토글 버튼(접기/펼치기)
        engineToggleBtn = ButtonWidget.builder(getEngineToggleText(), b -> {
            enginePanelOpen = !enginePanelOpen;
            updateEnginePanelVisibility();
            b.setMessage(getEngineToggleText());
        }).dimensions(12, 30, 140, 20).build();
        addDrawableChild(engineToggleBtn);

        // 트랙 선택 버튼
        trackSelectBtn = ButtonWidget.builder(Text.literal("트랙 선택"), b -> {
            if (this.client != null) {
                // ✅ 트랙 선택 화면: 선택하면 콜백으로 현재 RankingScreen을 갱신
                this.client.setScreen(new TrackSelectScreen(this, this.track, this::setTrackAndReload));
            }
        }).dimensions(160, 30, 80, 20).build();
        addDrawableChild(trackSelectBtn);

        updateButtons();

        // ✅ 들어오자마자 트랙 리스트 프리패치 (트랙 선택 화면 열 때 즉시 뜨게)
        ApiCache.prefetchTrackList();

        // ✅ 랭킹은 캐시 있으면 즉시 표시, 없으면 1번만 로딩
        loadRankingFromCacheOrFetch();
    }

    // ✅ 트랙만 바꾸고 같은 화면에서 데이터 갱신 (new RankingScreen 금지)
    private void setTrackAndReload(String newTrack) {
        this.track = sanitizeTrackOrDefault(newTrack);

        loading = true;
        error = null;

        page = 0;
        ranking.clear();
        filtered.clear();
        engines.clear();
        selectedEngine = "ALL";

        enginePanelOpen = false;
        if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());

        // 엔진 버튼 제거
        for (ButtonWidget b : engineButtons) {
            try {
                this.remove(b);
            } catch (Throwable ignored) {}
        }
        engineButtons.clear();

        updateButtons();
        loadRankingFromCacheOrFetch();
    }

    private Text getEngineToggleText() {
        String arrow = enginePanelOpen ? "▾" : "▸";
        String show = selectedEngine.equalsIgnoreCase("ALL") ? "전체" : "[" + selectedEngine + "]";
        return Text.literal("엔진 선택 " + arrow + " : " + show);
    }

    private void updateButtons() {
        if (prevBtn != null) prevBtn.active = (page > 0);
        if (nextBtn != null) nextBtn.active = (!filtered.isEmpty() && (page + 1) * PAGE_SIZE < filtered.size());
    }

    // ✅ 캐시 있으면 즉시 적용, 없으면 fetch
    private void loadRankingFromCacheOrFetch() {
        ApiCache.RankingPayload cached = ApiCache.getCachedRankingIfFresh(track);
        if (cached != null) {
            applyPayload(cached);
            loading = false;
            rebuildEngineButtons();
            if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
            updateButtons();
            return;
        }

        loading = true;
        error = null;
        updateButtons();

        ApiCache.fetchRankingIfNeededAsync(track, payload -> {
            if (payload == null) {
                error = "empty payload";
                loading = false;
                updateButtons();
                return;
            }
            applyPayload(payload);
            loading = false;

            rebuildEngineButtons();
            if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
            updateButtons();
        }, err -> {
            error = err;
            loading = false;
            updateButtons();
        });
    }

    private void applyPayload(ApiCache.RankingPayload payload) {
        ranking.clear();
        filtered.clear();
        engines.clear();

        ranking.addAll(payload.ranking);

        if (payload.engines != null) engines.addAll(payload.engines);

        // 보험: engines가 비었으면 ranking에서 추출
        if (engines.isEmpty()) {
            for (Entry e : ranking) {
                String eng = normalizeEngine(e.engineName);
                if (!engines.contains(eng)) engines.add(eng);
            }
            engines.sort(String::compareTo);
        }

        // 기본 선택: X가 있으면 X, 없으면 ALL
        if (engines.contains("X")) selectedEngine = "X";
        else selectedEngine = "ALL";

        // 정렬(빠른 기록 위)
        ranking.sort(Comparator.comparingLong(Entry::timeMillis));

        applyFilter();
        filtered.sort(Comparator.comparingLong(Entry::timeMillis));

        page = 0;
    }

    // 엔진 버튼 패널 생성(엔진 목록이 들어온 뒤에 호출)
    private void rebuildEngineButtons() {
        // 기존 버튼 제거
        for (ButtonWidget b : engineButtons) {
            try {
                this.remove(b);
            } catch (Throwable ignored) {}
        }
        engineButtons.clear();

        int startX = 12;
        int y = 54;              // 토글 버튼 아래
        int w = 52;
        int h = 18;
        int gap = 4;

        int x = startX;

        // ✅ ALL(전체) 버튼은 항상 맨 앞 고정
        addEngineButton("ALL", x, y, w, h);
        x += w + gap;

        // ✅ 엔진 버튼 순서: 해당 트랙에서 많이 쓰인 순 (GAS에서 engines가 이미 그렇게 내려오도록 추천)
        // 여기서는 받은 engines 그대로 사용하되, ALL은 제외
        for (String eng : engines) {
            if (eng == null) continue;
            if (eng.equalsIgnoreCase("ALL")) continue;

            addEngineButton(eng, x, y, w, h);

            x += w + gap;
            if (x + w > this.width - 12) {
                x = startX;
                y += h + 4;
            }
        }

        updateEnginePanelVisibility();
        updateEngineButtonLabels();
    }

    private void addEngineButton(String eng, int x, int y, int w, int h) {
        ButtonWidget btn = ButtonWidget.builder(getEngineButtonText(eng), b -> {
            selectedEngine = eng;
            page = 0;
            applyFilter();
            updateEngineButtonLabels();
            updateButtons();

            if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
        }).dimensions(x, y, w, h).build();

        engineButtons.add(btn);
        addDrawableChild(btn);
    }

    private Text getEngineButtonText(String eng) {
        String label = eng.equalsIgnoreCase("ALL") ? "전체" : eng;
        if (eng.equalsIgnoreCase(selectedEngine)) {
            return Text.literal("§a" + label);
        }
        return Text.literal(label);
    }

    private void updateEngineButtonLabels() {
        for (ButtonWidget b : engineButtons) {
            String raw = b.getMessage().getString().replace("§a", "").trim();
            String eng = raw.equals("전체") ? "ALL" : raw;
            b.setMessage(getEngineButtonText(eng));
        }
    }

    private void updateEnginePanelVisibility() {
        for (ButtonWidget b : engineButtons) {
            // 1.21.x 대부분 visible 있음
            b.visible = enginePanelOpen;
            b.active = enginePanelOpen;
        }
    }

    // selectedEngine 기준 필터
    private void applyFilter() {
        filtered.clear();

        for (Entry e : ranking) {
            String eng = normalizeEngine(e.engineName);

            if (selectedEngine.equalsIgnoreCase("ALL")) {
                filtered.add(e);
                continue;
            }

            if (selectedEngine.equalsIgnoreCase(eng)) {
                filtered.add(e);
            }
        }

        int maxPage = Math.max(0, (filtered.size() - 1) / PAGE_SIZE);
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

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int cx = this.width / 2;

        String engineTitle = selectedEngine.equalsIgnoreCase("ALL") ? "전체" : "[" + selectedEngine + "]";
        context.drawCenteredTextWithShadow(this.textRenderer,
                "트랙: " + track + "  |  엔진: " + engineTitle,
                cx, 15, 0xFFFFFF);

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "불러오는 중...",
                    cx, 80, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        if (error != null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "오류: " + error,
                    cx, 80, 0xFF5555);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        if (filtered.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "해당 엔진 기록이 없습니다.",
                    cx, 80, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        int startY = enginePanelOpen ? 110 : 80;
        int lineH = 12;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, filtered.size());

        for (int i = start; i < end; i++) {
            Entry e = filtered.get(i);

            String eng = normalizeEngine(e.engineName);
            String body = (e.bodyName == null || e.bodyName.isBlank()) ? "UNKNOWN" : e.bodyName;

            String line = String.format("%d위  %s  -  %s  %s [%s]",
                    (i + 1),
                    e.player,
                    e.timeStr,
                    body,
                    eng
            );

            context.drawCenteredTextWithShadow(this.textRenderer,
                    line,
                    cx,
                    startY + (i - start) * lineH,
                    0xFFFFFF);
        }

        String pageInfo = String.format("페이지 %d / %d",
                (page + 1),
                (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);

        context.drawCenteredTextWithShadow(this.textRenderer, pageInfo, cx, this.height - 26, 0xAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ✅ 캐시에서 사용해야 해서 public static
    public static record Entry(String player, String timeStr, long timeMillis, String engineName, String bodyName) {}

    /* =========================================================
       ✅ 내부 유틸: 네트워크 + 캐시 (추가 파일 없이 여기 포함)
       ========================================================= */

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

        public static String escapeJson(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public static final class ApiCache {
        private ApiCache() {}

        // ===== TrackList Cache =====
        private static volatile List<TrackSelectScreen.TrackEntry> cachedTracks = null;
        private static volatile long cachedTracksAtMs = 0;
        private static final long TRACKS_TTL_MS = 5 * 60_000; // 5분

        private static volatile boolean trackListFetching = false;
        private static final List<Runnable> trackListWaiters = new ArrayList<>();

        // ===== Ranking Cache (per track) =====
        public static final class RankingPayload {
            public final List<Entry> ranking;
            public final List<String> engines;
            public final long fetchedAtMs;

            public RankingPayload(List<Entry> ranking, List<String> engines, long fetchedAtMs) {
                this.ranking = ranking;
                this.engines = engines;
                this.fetchedAtMs = fetchedAtMs;
            }
        }

        private static final Map<String, RankingPayload> rankingCache = new ConcurrentHashMap<>();
        private static final Map<String, Boolean> rankingFetching = new ConcurrentHashMap<>();
        private static final Map<String, List<Runnable>> rankingWaiters = new ConcurrentHashMap<>();
        private static final long RANKING_TTL_MS = 2 * 60_000; // 2분

        // 트랙 리스트 미리 받아두기 (실패해도 무시)
        public static void prefetchTrackList() {
            fetchTrackListIfNeededAsync(list -> {}, err -> {});
        }

        public static List<TrackSelectScreen.TrackEntry> getCachedTracksIfFresh() {
            List<TrackSelectScreen.TrackEntry> v = cachedTracks;
            if (v == null) return null;
            long now = System.currentTimeMillis();
            if (now - cachedTracksAtMs > TRACKS_TTL_MS) return null;
            return v;
        }

        public static void fetchTrackListIfNeededAsync(Consumer<List<TrackSelectScreen.TrackEntry>> onDone,
                                                       Consumer<String> onError) {

            List<TrackSelectScreen.TrackEntry> fresh = getCachedTracksIfFresh();
            if (fresh != null) {
                onDone.accept(fresh);
                return;
            }

            synchronized (ApiCache.class) {
                if (trackListFetching) {
                    trackListWaiters.add(() -> onDone.accept(cachedTracks));
                    return;
                }
                trackListFetching = true;
            }

            new Thread(() -> {
                String err = null;
                List<TrackSelectScreen.TrackEntry> got = null;

                try {
                    JsonObject res = Net.postJson(GAS_URL, "{\"action\":\"getTrackList\"}");

                    if (!res.has("ok") || !res.get("ok").getAsBoolean()) {
                        err = res.has("error") ? res.get("error").getAsString() : "unknown error";
                    } else {
                        JsonArray arr = res.getAsJsonArray("tracks");
                        List<TrackSelectScreen.TrackEntry> list = new ArrayList<>();

                        for (int i = 0; i < arr.size(); i++) {
                            JsonObject o = arr.get(i).getAsJsonObject();
                            String track = o.get("track").getAsString();
                            int count = o.get("count").getAsInt();
                            list.add(new TrackSelectScreen.TrackEntry(track, count));
                        }

                        list.sort(Comparator.comparingInt((TrackSelectScreen.TrackEntry t) -> t.count()).reversed());
                        got = list;
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }

                final String ferr = err;
                final List<TrackSelectScreen.TrackEntry> fgot = got;

                MinecraftClient.getInstance().execute(() -> {
                    synchronized (ApiCache.class) {
                        trackListFetching = false;
                        if (fgot != null) {
                            cachedTracks = fgot;
                            cachedTracksAtMs = System.currentTimeMillis();
                        }

                        for (Runnable r : trackListWaiters) r.run();
                        trackListWaiters.clear();
                    }

                    if (ferr != null) onError.accept(ferr);
                    else onDone.accept(fgot);
                });
            }, "TrackListCacheFetch").start();
        }

        public static RankingPayload getCachedRankingIfFresh(String track) {
            if (track == null) return null;
            RankingPayload p = rankingCache.get(track);
            if (p == null) return null;
            long now = System.currentTimeMillis();
            if (now - p.fetchedAtMs > RANKING_TTL_MS) return null;
            return p;
        }

        public static void fetchRankingIfNeededAsync(String track,
                                                     Consumer<RankingPayload> onDone,
                                                     Consumer<String> onError) {

            RankingPayload fresh = getCachedRankingIfFresh(track);
            if (fresh != null) {
                onDone.accept(fresh);
                return;
            }

            synchronized (rankingCache) {
                if (Boolean.TRUE.equals(rankingFetching.get(track))) {
                    rankingWaiters.computeIfAbsent(track, k -> new ArrayList<>())
                            .add(() -> onDone.accept(rankingCache.get(track)));
                    return;
                }
                rankingFetching.put(track, true);
            }

            new Thread(() -> {
                String err = null;
                RankingPayload payload = null;

                try {
                    String body = String.format(
                            "{\"action\":\"getTrackRanking\",\"track\":\"%s\"}",
                            Net.escapeJson(track)
                    );

                    JsonObject res = Net.postJson(GAS_URL, body);

                    if (!res.has("ok") || !res.get("ok").getAsBoolean()) {
                        err = res.has("error") ? res.get("error").getAsString() : "unknown error";
                    } else {
                        // engines
                        List<String> engines = new ArrayList<>();
                        if (res.has("engines") && res.get("engines").isJsonArray()) {
                            JsonArray engArr = res.getAsJsonArray("engines");
                            for (int i = 0; i < engArr.size(); i++) {
                                String eng = engArr.get(i).getAsString();
                                if (eng != null && !eng.isBlank()) engines.add(eng.trim().toUpperCase());
                            }
                        }

                        // ranking
                        List<Entry> list = new ArrayList<>();
                        JsonArray arr = res.getAsJsonArray("ranking");
                        for (int i = 0; i < arr.size(); i++) {
                            JsonObject o = arr.get(i).getAsJsonObject();
                            String player = o.get("player").getAsString();
                            String time = o.get("time").getAsString();
                            long ms = o.get("timeMillis").getAsLong();
                            String engineName = o.has("engineName") ? o.get("engineName").getAsString() : "UNKNOWN";
                            String bodyName = o.has("bodyName") ? o.get("bodyName").getAsString() : "UNKNOWN";
                            list.add(new Entry(player, time, ms, engineName, bodyName));
                        }

                        list.sort(Comparator.comparingLong(Entry::timeMillis));
                        payload = new RankingPayload(list, engines, System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }

                final String ferr = err;
                final RankingPayload fpayload = payload;

                MinecraftClient.getInstance().execute(() -> {
                    synchronized (rankingCache) {
                        rankingFetching.put(track, false);
                        if (fpayload != null) rankingCache.put(track, fpayload);

                        List<Runnable> waiters = rankingWaiters.remove(track);
                        if (waiters != null) for (Runnable r : waiters) r.run();
                    }

                    if (ferr != null) onError.accept(ferr);
                    else onDone.accept(fpayload);
                });
            }, "RankingCacheFetch").start();
        }
    }
}
