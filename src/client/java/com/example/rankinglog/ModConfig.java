package com.example.rankinglog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/mcrider_ranking_config.json 에 저장되는 간단한 설정
 */
public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mcrider_ranking_config.json";

    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

    // ===== 설정 값들 =====
    public boolean autoSubmitEnabled = true;

    // 디버그 로그(채팅 출력) 토글
    public boolean debugLogEnabled = false;

    // 캐시 유지 시간 (단위: 초, 기본값 60초)
    public int cacheTtlSeconds = 60;

    // 밀리초 단위로 변환해서 반환하는 메서드
    public long getCacheTtlMs() {
        return (long) Math.clamp(cacheTtlSeconds, 10, 600) * 1000L;
    }

    // 카트바디 인식 모드 (0: 오리지널, 1: 엄격)
    public int bodyScanMode = 0;

    // 배경 불투명도 (0 ~ 255, 기본값 110)
    public int backgroundAlpha = 110;

    // 배경 불투명도를 바탕으로 배경색(ARGB)을 계산해서 반환하는 메서드
    public int getBgColor() {
        // alpha 값을 0~255 사이로 제한하고, 검은색(0x000000)과 합성합니다.
        int alpha = Math.clamp(backgroundAlpha, 0, 255);
        return (alpha << 24);
    }

    // 싱글톤
    private static ModConfig INSTANCE = new ModConfig();

    public static ModConfig get() {
        return INSTANCE;
    }

    public static void load() {
        if (!Files.exists(PATH)) {
            save(); // 기본값으로 생성
            return;
        }

        try {
            String json = Files.readString(PATH, StandardCharsets.UTF_8);
            ModConfig loaded = GSON.fromJson(json, ModConfig.class);
            if (loaded != null) INSTANCE = loaded;
        } catch (IOException | JsonSyntaxException e) {
            // 읽기 실패하면 기본값으로 복구하고 덮어쓰기
            INSTANCE = new ModConfig();
            save();
        }
    }

    public static void save() {
        try {
            String json = GSON.toJson(INSTANCE);
            Files.writeString(PATH, json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}