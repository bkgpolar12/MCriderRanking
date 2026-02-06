package com.example.rankinglog;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.text.Text;

public final class ModGatekeeper {

    // "----적용된 모드----"를 본 직후인지
    private static boolean waitingNextLine = false;

    // 최근 판정 결과 (true면 "없음"이라서 통과)
    private static boolean modsClean = false;

    // 메시지가 오래전에 온 거면 무효처리하기 위한 시간(선택)
    private static long waitingStartedAtMs = 0;
    private static final long WAIT_TIMEOUT_MS = 3000;

    private ModGatekeeper() {}

    public static void init() {
        // 일반 채팅
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) -> {
            handleMessage(message);
        });

        // 서버가 system/game 메시지로 보내는 경우도 대비 (필요 없으면 빼도 됨)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            handleMessage(message);
        });
    }

    private static void handleMessage(Text message) {
        String raw = message.getString();
        if (raw == null) return;

        // 한 메시지에 줄바꿈이 들어오는 경우도 있어서 통일 처리
        String[] lines = raw.split("\\R"); // \n, \r\n 모두 처리

        for (String line : lines) {
            processLine(line);
        }
    }

    private static void processLine(String line) {
        String s = normalize(line);

        // 타임아웃(선택): "다음 줄"이 너무 늦게 오면 대기 상태 해제
        if (waitingNextLine) {
            long now = System.currentTimeMillis();
            if (now - waitingStartedAtMs > WAIT_TIMEOUT_MS) {
                waitingNextLine = false;
            }
        }

        if (s.equals("----적용된 모드----")) {
            waitingNextLine = true;
            waitingStartedAtMs = System.currentTimeMillis();
            return;
        }

        if (waitingNextLine) {
            // 다음 줄 판정
            // 정확히 "없음"만 통과
            modsClean = s.equals("없음");
            waitingNextLine = false;
        }
    }

    private static String normalize(String s) {
        // 공백/색코드 영향 최소화
        return s.replaceAll("\\s+", " ").trim();
    }

    public static boolean isModsClean() {
        return modsClean;
    }

    // 원하면 수동 초기화도 가능
    public static void reset() {
        waitingNextLine = false;
        modsClean = false;
        waitingStartedAtMs = 0;
    }
}
