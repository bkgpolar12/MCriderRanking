package com.example.rankinglog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mcrider_ranking_config.json";
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

    // 설정 값들
    public boolean autoSubmitEnabled = true;
    public boolean debugLogEnabled = false;
    public int cacheTtlSeconds = 60;
    public int bodyScanMode = 0;
    public int backgroundAlpha = 110;

    // ★ 추가: 기본 표시 트랙 설정 (기본값 설정)
    public String defaultTrack = "[α] 빌리지 고가의 질주";

    public long getCacheTtlMs() {
        return (long) Math.clamp(cacheTtlSeconds, 10, 600) * 1000L;
    }

    public int getBgColor() {
        int alpha = Math.clamp(backgroundAlpha, 0, 255);
        return (alpha << 24);
    }

    private static ModConfig INSTANCE = new ModConfig();
    public static ModConfig get() { return INSTANCE; }

    public static void load() {
        if (!Files.exists(PATH)) {
            save();
            return;
        }
        try {
            String json = Files.readString(PATH, StandardCharsets.UTF_8);
            ModConfig loaded = GSON.fromJson(json, ModConfig.class);
            if (loaded != null) {
                // 필드 누락 방지
                if (loaded.defaultTrack == null || loaded.defaultTrack.isBlank()) {
                    loaded.defaultTrack = "[α] 빌리지 고가의 질주";
                }
                INSTANCE = loaded;
            }
        } catch (IOException | JsonSyntaxException e) {
            INSTANCE = new ModConfig();
            save();
        }
    }

    public static void save() {
        try {
            String json = GSON.toJson(INSTANCE);
            Files.writeString(PATH, json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }
}