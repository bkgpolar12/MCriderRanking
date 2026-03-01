package com.example.rankinglog;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

public final class BodyCaptureManager {

    private BodyCaptureManager() {}

    // ===== 상태 =====
    private static boolean active = false;            // 캡처 진행중
    private static boolean capturedThisRace = false;  // 이번 판에서 "종료 확정"까지 했는지

    // 스캔 주기
    private static final long BODY_SCAN_INTERVAL_MS = 150;
    private static long lastBodyScanMs = 0;

    // 제출용 캐시 (최종으로 쓸 값)
    private static String cachedKartBodyName = null;

    // ✅ 타이어 캐시 (최종으로 쓸 값)
    private static String cachedTireName = null;

    // 손 감지 캐시
    private static String cachedOffhandName = null;   //왼손 마지막 유효 이름
    private static String cachedMainhandName = null;  //오른손 마지막 유효 이름

    // 중복 갱신 방지 키
    private static String lastOffhandKey = null;
    private static String lastMainhandKey = null;

    //item_display 중복 갱신 방지 키
    private static String lastItemDisplayKey = null;

    // ✅ 타이어 중복 로그 방지 키
    private static String lastTireKey = null;

    // 로그 스팸 방지
    private static long lastBodyLogMs = 0;
    private static String lastBodyLogValue = null;
    private static final long LOG_COOLDOWN_MS = 600;

    //item_display 탐색 반경(블록)
    private static final double ITEM_DISPLAY_SCAN_RADIUS = 16.0;

    // ✅ kart-tire modifier id
    private static final Identifier KART_TIRE_ID = Identifier.of("minecraft", "kart-tire");

    public static String getCachedKartBodyNameOrUnknown() {
        if (cachedKartBodyName == null || cachedKartBodyName.isBlank()) return "UNKNOWN";
        return cachedKartBodyName;
    }


    /** HUD 타이틀/커스텀 스크린 텍스트에서 "로딩중..." 감지하면 호출 */
    public static void onLoadingDetected(String sourceTag) {
        if (capturedThisRace) {
            capturedThisRace = false;
        }
        if (active) return;

        active = true;

        // 스캔 간격 초기화(시작 직후 바로 스캔되게)
        lastBodyScanMs = 0;

        // 캐시 초기화
        cachedKartBodyName = null;
        cachedTireName = null;

        cachedOffhandName = null;
        cachedMainhandName = null;

        lastOffhandKey = null;
        lastMainhandKey = null;
        lastItemDisplayKey = null;
        lastTireKey = null;

        if (DebugLog.enabled()) {
            DebugLog.chat("§7[Body] 캡처 시작 (" + sourceTag + ")");
        }
    }

    /** HUD 타이틀이 "3"이 되면 종료 확정 */
    public static void onTitle3() {
        if (!active) return;

        active = false;
        capturedThisRace = true;

        if (cachedKartBodyName == null || cachedKartBodyName.isBlank()) {
            cachedKartBodyName = "UNKNOWN";
        }
        if (cachedTireName == null || cachedTireName.isBlank()) {
            cachedTireName = "UNKNOWN";
        }

        logBody("§a[Body] 캡처 종료(title=3) : " + safeShow(cachedKartBodyName) + " | Tire=" + safeShow(cachedTireName));
    }

    /** "완주 실패" 등 실패 상황에서 종료 확정 */
    public static void onRaceFailed() {
        if (!active && capturedThisRace) return;

        active = false;
        capturedThisRace = true;

        if (cachedKartBodyName == null || cachedKartBodyName.isBlank()) {
            cachedKartBodyName = "UNKNOWN";
        }
        if (cachedTireName == null || cachedTireName.isBlank()) {
            cachedTireName = "UNKNOWN";
        }

        logBody("§c[Body] 캡처 종료(완주 실패) : " + safeShow(cachedKartBodyName) + " | Tire=" + safeShow(cachedTireName));
    }

    /** InGameHud render에서 매 프레임 호출해도 됨(active일 때만 내부에서 스캔) */
    public static void tickScan() {
        if (!active) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        long now = System.currentTimeMillis();
        if (now - lastBodyScanMs < BODY_SCAN_INTERVAL_MS) return;
        lastBodyScanMs = now;

        // ✅ 0) 타이어는 매번 갱신 시도 (동시 감지)
        readTireNameAndCacheLast();

        // 1) 손 감지는 "항상" 먼저 갱신해둔다 (동시 감지)
        readOffhandNameAndCacheLast();
        readMainhandNameAndCacheLast();

        // 2) item_display customName 시도
        String displayName = readNearestItemDisplayCustomNameIfPresent();
        if (displayName != null && !displayName.isBlank()) {
            cachedKartBodyName = displayName;
            logBody("§b[Body] ITEM_DISPLAY 감지: " + cachedKartBodyName);
            return;
        }

        // 3) item_display가 null/빈값이면 "미리 캐싱된 손 값" 사용 (왼손 우선)
        if (cachedOffhandName != null && !cachedOffhandName.isBlank()) {
            cachedKartBodyName = cachedOffhandName;
            logBody("§a[Body] OFFHAND fallback: " + cachedKartBodyName);
            return;
        }

        if (cachedMainhandName != null && !cachedMainhandName.isBlank()) {
            cachedKartBodyName = cachedMainhandName;
            logBody("§e[Body] MAINHAND fallback: " + cachedKartBodyName);
        }
    }

    // ===== 내부 유틸 =====

    /** kart-tire modifier value -> 타이어 이름으로 캐싱 */
    private static void readTireNameAndCacheLast() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        try {
            EntityAttributeInstance inst = client.player.getAttributeInstance(EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE);
            if (inst == null) return;

            Double tireValue = null;

            // 1.21.5+build.1: EntityAttributeModifier는 record accessor (id(), value(), operation())
            for (var mod : inst.getModifiers()) {
                Identifier id = mod.id();
                if (KART_TIRE_ID.equals(id)) {
                    tireValue = mod.value();
                    break;
                }
            }

            if (tireValue == null) return;

            String tireName = TireUtil.nameFromValue(tireValue);
            String key = KART_TIRE_ID + "|" + tireName;

            if (key.equals(lastTireKey)) return;
            lastTireKey = key;

            cachedTireName = tireName;

            if (DebugLog.enabled()) DebugLog.chat("§7[Tire] value=" + tireValue + " => " + tireName);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * 플레이어 주변에서 가장 가까운 item_display 엔티티를 찾고,
     * 그 엔티티의 CustomName을 반환한다.
     * - 없거나 CustomName이 없으면 null 반환
     */
    private static String readNearestItemDisplayCustomNameIfPresent() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return null;

        try {
            Box box = client.player.getBoundingBox().expand(ITEM_DISPLAY_SCAN_RADIUS);

            DisplayEntity.ItemDisplayEntity nearest = null;
            double bestDistSq = Double.MAX_VALUE;

            for (DisplayEntity.ItemDisplayEntity e : client.world.getEntitiesByClass(
                    DisplayEntity.ItemDisplayEntity.class,
                    box,
                    entity -> true
            )) {
                double d = client.player.squaredDistanceTo(e);
                if (d < bestDistSq) {
                    bestDistSq = d;
                    nearest = e;
                }
            }

            if (nearest == null) return null;

            Text custom = nearest.getCustomName();
            if (custom == null) return null;

            String name = safeItemName(custom.getString());
            if (name == null) return null;

            // 🔥 추가 조건:
            // 이름이 "mcrider-modelsaddle" 이면 무시하고 null 반환
            if (name.equalsIgnoreCase("mcrider-modelsaddle")) {
                if (DebugLog.enabled()) {
                    DebugLog.chat("§7[Body] ItemDisplay 무시됨 (modelsaddle)");
                }
                return null; // 손 감지 fallback 사용하게 됨
            }

            // 중복 로그 방지
            String key = nearest.getId() + "|" + name;
            if (!key.equals(lastItemDisplayKey)) {
                lastItemDisplayKey = key;
                if (DebugLog.enabled()) {
                    DebugLog.chat("§7[Body] ItemDisplay raw=" + name);
                }
            }

            return name;

        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void readOffhandNameAndCacheLast() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        try {
            var stack = client.player.getOffHandStack();
            if (stack == null || stack.isEmpty()) return;

            String name = safeItemName(stack.getName().getString());
            if (name == null) return;

            String key = stack.getItem().toString() + "|" + name;
            if (key.equals(lastOffhandKey)) return;

            lastOffhandKey = key;
            cachedOffhandName = name;

            if (DebugLog.enabled()) DebugLog.chat("§7[Body] Offhand raw=" + cachedOffhandName);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void readMainhandNameAndCacheLast() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        try {
            var stack = client.player.getMainHandStack();
            if (stack == null || stack.isEmpty()) return;

            String name = safeItemName(stack.getName().getString());
            if (name == null) return;

            String key = stack.getItem().toString() + "|" + name;
            if (key.equals(lastMainhandKey)) return;

            lastMainhandKey = key;
            cachedMainhandName = name;

            if (DebugLog.enabled()) DebugLog.chat("§7[Body] Mainhand raw=" + cachedMainhandName);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static String safeItemName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }

    private static void logBody(String msg) {
        if (!DebugLog.enabled()) return;
        long now = System.currentTimeMillis();
        if (msg.equals(lastBodyLogValue) && (now - lastBodyLogMs) < LOG_COOLDOWN_MS) return;
        lastBodyLogValue = msg;
        lastBodyLogMs = now;
        DebugLog.chat(msg);
    }

    private static String safeShow(String v) {
        if (v == null) return "null";
        String s = v.replace("\n", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "(blank)";
        return s;
    }
}