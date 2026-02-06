package com.example.rankinglog;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class RankingApiCache {

    private RankingApiCache() {}

    // ===== TrackList Cache =====
    private static volatile List<TrackSelectScreen.TrackEntry> cachedTracks = null;
    private static volatile long cachedTracksAtMs = 0;
    private static final long TRACKS_TTL_MS = 5 * 60_000; // 5분 캐시

    private static volatile boolean trackListFetching = false;
    private static final List<Runnable> trackListWaiters = new ArrayList<>();

    // ===== Ranking Cache (per track) =====
    public static final class RankingPayload {
        public final List<RankingScreen.Entry> ranking;
        public final List<String> engines;
        public final long fetchedAtMs;

        public RankingPayload(List<RankingScreen.Entry> ranking, List<String> engines, long fetchedAtMs) {
            this.ranking = ranking;
            this.engines = engines;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    private static final Map<String, RankingPayload> rankingCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> rankingFetching = new ConcurrentHashMap<>();
    private static final Map<String, List<Runnable>> rankingWaiters = new ConcurrentHashMap<>();
    private static final long RANKING_TTL_MS = 2 * 60_000; // 2분 캐시

    // ========= TrackList =========
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

        synchronized (RankingApiCache.class) {
            // 이미 누군가 가져오는 중이면 대기자 등록
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
                JsonObject res = RankingNet.postJson(RankingScreen.GAS_URL, "{\"action\":\"getTrackList\"}");

                if (!res.has("ok") || !res.get("ok").getAsBoolean()) {
                    err = res.has("error") ? res.get("error").getAsString() : "unknown error";
                } else {
                    var arr = res.getAsJsonArray("tracks");
                    List<TrackSelectScreen.TrackEntry> list = new ArrayList<>();
                    for (int i = 0; i < arr.size(); i++) {
                        var o = arr.get(i).getAsJsonObject();
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
                synchronized (RankingApiCache.class) {
                    trackListFetching = false;
                    if (fgot != null) {
                        cachedTracks = fgot;
                        cachedTracksAtMs = System.currentTimeMillis();
                    }
                    // 대기자 깨우기
                    for (Runnable r : trackListWaiters) r.run();
                    trackListWaiters.clear();
                }

                if (ferr != null) onError.accept(ferr);
                else onDone.accept(fgot);
            });
        }, "TrackListCacheFetch").start();
    }

    // ========= Ranking per track =========
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

        // in-flight dedupe
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
                String body = String.format("{\"action\":\"getTrackRanking\",\"track\":\"%s\"}",
                        RankingNet.escapeJson(track));

                JsonObject res = RankingNet.postJson(RankingScreen.GAS_URL, body);

                if (!res.has("ok") || !res.get("ok").getAsBoolean()) {
                    err = res.has("error") ? res.get("error").getAsString() : "unknown error";
                } else {
                    // engines
                    List<String> engines = new ArrayList<>();
                    if (res.has("engines") && res.get("engines").isJsonArray()) {
                        var engArr = res.getAsJsonArray("engines");
                        for (int i = 0; i < engArr.size(); i++) {
                            String eng = engArr.get(i).getAsString();
                            if (eng != null && !eng.isBlank()) engines.add(eng.trim().toUpperCase());
                        }
                    }

                    // ranking list
                    List<RankingScreen.Entry> list = new ArrayList<>();
                    var arr = res.getAsJsonArray("ranking");
                    for (int i = 0; i < arr.size(); i++) {
                        var o = arr.get(i).getAsJsonObject();
                        String player = o.get("player").getAsString();
                        String time = o.get("time").getAsString();
                        long ms = o.get("timeMillis").getAsLong();
                        String engineName = o.has("engineName") ? o.get("engineName").getAsString() : "UNKNOWN";
                        String bodyName = o.has("bodyName") ? o.get("bodyName").getAsString() : "UNKNOWN";
                        list.add(new RankingScreen.Entry(player, time, ms, engineName, bodyName));
                    }

                    list.sort(Comparator.comparingLong(RankingScreen.Entry::timeMillis));

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
                    if (waiters != null) {
                        for (Runnable r : waiters) r.run();
                    }
                }

                if (ferr != null) onError.accept(ferr);
                else onDone.accept(fpayload);
            });
        }, "RankingCacheFetch").start();
    }
}
