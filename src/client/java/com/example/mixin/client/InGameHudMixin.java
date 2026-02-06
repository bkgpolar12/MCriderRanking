package com.example.mixin.client;

import com.example.rankinglog.AddRankingScreen;
import com.example.rankinglog.AutoSubmitter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.util.math.Vec3d;

import java.util.regex.Pattern;

import net.minecraft.client.network.ServerInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mixin(InGameHud.class)
public class InGameHudMixin {

	/* =========================
       Shadow
       ========================= */
	@Shadow private Text title;
	@Shadow private Text subtitle;

	/* =========================
       상태 변수
       ========================= */
	@Unique private String lastTitle = "";
	@Unique private String lastSubtitle = "";
	@Unique private boolean modWarningShown = false;

	@Unique private boolean pendingTrackRetry = false;
	@Unique private long trackRetryStartMs = 0;
	@Unique private int trackRetryCount = 0;

	@Unique private static final String ALLOWED_ADDRESS = "mcriders.64bit.kr";
	@Unique private static final String ALLOWED_ADDRESS1 = "kart-dev-server.kro.kr";

	@Unique private static String cachedTrackName = null;
	@Unique private static List<DisplayEntity.TextDisplayEntity> cachedList = null;

	@Unique private static String cachedEngineName = null;
	@Unique private static long lastEngineScanMs = 0;
	@Unique private boolean israce = false;

	// ✅ 카트바디 캐시(최종 제출용)
	@Unique private static String cachedKartBodyName = null;

	// ✅ 메인핸드 마지막 유효 이름(메인핸드가 비어도 이 값은 유지)
	@Unique private static String cachedMainhandName = null;

	// ✅ 중복 갱신 방지 키(아이템 타입+이름)
	@Unique private static String lastOffhandKey = null;
	@Unique private static String lastMainhandKey = null;

	// ✅ 캡처 구간: subtitle 로딩중... ~ title 3
	@Unique private boolean bodyCaptureActive = false;
	@Unique private boolean bodyCapturedThisRace = false;

	// 너무 빡세게 돌지 않게(150ms)
	@Unique private static final long BODY_SCAN_INTERVAL_MS = 150;
	@Unique private long lastBodyScanMs = 0;

	@Unique private boolean soloOk = true;
	@Unique private long lastSoloScanMs = 0;

	@Unique private String lastSoloFailTimeStr = null;
	@Unique private long lastSoloFailMsgMs = 0;
	@Unique private static final long SOLO_FAIL_COOLDOWN_MS = 1500;

	@Unique private static final double NEAR_PLAYER_RADIUS = 25.0;
	@Unique private static final double NEAR_PLAYER_RADIUS_SQ = NEAR_PLAYER_RADIUS * NEAR_PLAYER_RADIUS;

	@Unique private String pendingTimeStr = null;
	@Unique private long pendingTimeMillis = -1;

	@Unique private static String lastDebugKey = null;
	@Unique private static long lastDebugAtMs = 0;

	@Unique private boolean random_text = true;

	// 엔진 이름 후보 패턴(원하는 엔진 표기들에 맞춰 조정)
	@Unique
	private static final Pattern ENGINE_NAME_PATTERN =
			Pattern.compile("\\[(X|EX|JIU|NEW|Z7|V1|A2|1\\.0|PRO|RALLY|CHARGE|N1|KE|BOAT|GEAR|F1|MK|KRP)\\엔진",
					Pattern.CASE_INSENSITIVE);

	@Unique private static final double ENGINE_SCAN_RADIUS_XZ = 3.0;
	@Unique private static final double ENGINE_SCAN_RADIUS_Y  = 6.0;

	/* =========================
       로비 범위 (트랙 텍스트 디스플레이)
       ========================= */
	@Unique
	private static final Box LOBBY_BOX = new Box(
			-21, 3, 155,
			-15, -1, 152
	);

	/* =========================
       메인 렌더 훅
       ========================= */
	@Inject(method = "render", at = @At("HEAD"))
	private void onRender(CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) return;

		String t = title != null ? title.getString() : "";
		String s = subtitle != null ? subtitle.getString() : "";

		// -------------------------
		// ✅ 카트바디 캡처 구간 제어
		// 시작: subtitle == "로딩중..."
		// 종료: title == "3"
		// -------------------------
		if (isAllowedServer()) {

			// 시작 트리거(처음 로딩중... 감지)
			if ("로딩중...".equals(t) && !bodyCaptureActive && !bodyCapturedThisRace) {
				israce = true;
				bodyCaptureActive = true;
				lastBodyScanMs = 0;

				// 캐시 초기화
				cachedKartBodyName = null;
				lastOffhandKey = null;
				lastMainhandKey = null;
				// 메인핸드 마지막 유효값은 "이 레이스에서 다시 잡은 값"이 좋으니 리셋 권장
				cachedMainhandName = null;

				// 디버그 원하면
				//client.player.sendMessage(Text.literal("§7[Body] 캡처 시작"), false);
			}

			// 캡처 진행 중이면, 주기적으로 스캔
			if (bodyCaptureActive && !bodyCapturedThisRace) {
				scanAndCacheKartBody();
			}

			if ("완주 실패".equals(t)){
				bodyCapturedThisRace = false;
			}

			if ("".equals(t) && israce && lastTitle.equals("로딩중...")){
				bodyCapturedThisRace = false;
				bodyCaptureActive = false;
				//client.player.sendMessage(Text.literal("§7[Body] 캡처 종료: " + cachedKartBodyName), false);
			}

			// 종료 트리거: title == "3"
			if ("3".equals(t)) {

				// 끝까지 못 잡았으면 UNKNOWN
				if (cachedKartBodyName == null || cachedKartBodyName.isBlank()) {
					cachedKartBodyName = "UNKNOWN";
				}

				// 디버그 원하면

			}

			// 레이스가 완전히 리셋되는 타이밍이 필요하면 여기에 추가 가능
			// 예: 타이틀이 "로딩중..."으로 다시 시작될 때 bodyCapturedThisRace=false로 초기화되므로 지금 구조면 OK
			if (!"로딩중...".equals(s) && !"3".equals(t)) {
				// 다음 레이스 준비(너무 공격적으로 리셋하면 안되니 조건은 가볍게)
				// 보통 "로딩중..."에서 다시 시작하니까 여기서는 굳이 안 건드려도 됨
			}
		}

		// -------------------------
		// 트랙 이름 재시도 처리(렌더마다 체크)
		// -------------------------
		if (pendingTrackRetry) {
			long now = System.currentTimeMillis();
			if (now - trackRetryStartMs >= 200) {
				trackRetryStartMs = now;
				trackRetryCount++;

				boolean ok = tryCacheTrackName();

				if (ok && cachedTrackName != null && !cachedTrackName.isBlank()) {
					pendingTrackRetry = false;

					String track = cachedTrackName
							.replace("\n", " ")
							.replaceAll("\\s+", " ")
							.trim();

					if (track.toUpperCase().contains("RANDOM")) {
						if (random_text) {
							client.player.sendMessage(Text.literal("랜덤 트랙은 기록되지 않습니다."), false);
							random_text = false;
						}
						return;
					}

					if (track.isBlank()) {
						client.player.sendMessage(Text.literal("등록 실패 : 트랙 이름을 불러오지 못했습니다."), false);
						pendingTimeStr = null;
						pendingTimeMillis = -1;
						return;
					}

					String engineName = (cachedEngineName == null) ? "UNKNOWN" : cachedEngineName;

					if (pendingTimeMillis < 0 || pendingTimeStr == null) {
						client.player.sendMessage(Text.literal("등록 실패 : 기록 데이터를 잃어버렸습니다."), false);
						return;
					}

					String player = client.player.getGameProfile().getName();
					if (!com.example.rankinglog.ModConfig.get().autoSubmitEnabled) return;

					String bodyName = (cachedKartBodyName == null || cachedKartBodyName.isBlank())
							? "UNKNOWN"
							: cachedKartBodyName;

					AutoSubmitter.submitAsync(
							player,
							track,
							pendingTimeStr,
							pendingTimeMillis,
							0,
							engineName,
							bodyName
					);

					pendingTimeStr = null;
					pendingTimeMillis = -1;

				} else if (trackRetryCount >= 5) {
					pendingTrackRetry = false;
					client.player.sendMessage(Text.literal("등록 실패 : 트랙 이름을 불러오지 못했습니다."), false);
					pendingTimeStr = null;
					pendingTimeMillis = -1;
				}
			}
		}

		// -------------------------
		// 타이틀 변경 감지
		// -------------------------
		if (isAllowedServer()) {
			if (!t.equals(lastTitle)) {

				if (t.equals("시작")) {
					long now = System.currentTimeMillis();
					if (now - lastSoloScanMs > 800) {
						lastSoloScanMs = now;
						boolean nearOther = hasOtherPlayerNearMe();
						soloOk = !nearOther;
					}
				}

				// "3" 타이틀 감지 시 엔진 이름 캐싱
				if (t.equals("3")) {
					long now = System.currentTimeMillis();
					if (now - lastEngineScanMs > 800) {
						lastEngineScanMs = now;

						String engine = findEngineNameNearPlayer();
						if (engine != null) {
							cachedEngineName = engine;
						} else {
							client.player.sendMessage(
									Text.literal("§c엔진 감지 실패(주변 텍스트디스플레이 없음/패턴 불일치)"),
									false
							);
						}
					}
				}
			}

			// -------------------------
			// 완주 subtitle 감지
			// -------------------------
			if (!s.equals(lastSubtitle) && s.matches("^\\d{2}:\\d{2}\\.\\d{3}$")) {

				bodyCapturedThisRace = false;

				if (!soloOk) {
					long now = System.currentTimeMillis();
					boolean sameTimeAsLast = (lastSoloFailTimeStr != null && lastSoloFailTimeStr.equals(s));
					boolean inCooldown = (now - lastSoloFailMsgMs) < SOLO_FAIL_COOLDOWN_MS;

					if (!sameTimeAsLast && !inCooldown) {
						client.player.sendMessage(
								Text.literal("플레이어가 최대 1명이어야 기록이 등록됩니다."),
								false
						);
						lastSoloFailTimeStr = s;
						lastSoloFailMsgMs = now;
					}
					return;
				}

				if (!com.example.rankinglog.ModGatekeeper.isModsClean()) {
					if (!modWarningShown) {
						client.player.sendMessage(
								Text.literal("모드가 모두 꺼져있어야 기록이 등록됩니다."),
								false
						);
						modWarningShown = true;
					}
					return;
				}

				boolean ok = tryCacheTrackName();
				if (!ok) {
					pendingTrackRetry = true;
					trackRetryStartMs = System.currentTimeMillis();
					trackRetryCount = 0;

					pendingTimeStr = s;
					pendingTimeMillis = AddRankingScreen.parseTimeToMillis(s);
					return;
				}

				String track = cachedTrackName
						.replace("\n", " ")
						.replaceAll("\\s+", " ")
						.trim();

				if (track.toUpperCase().contains("RANDOM")) {
					if (random_text) {
						client.player.sendMessage(Text.literal("랜덤 트랙은 기록되지 않습니다."), false);
						random_text = false;
					}
					return;

				}

				if (track.isBlank()) {
					client.player.sendMessage(Text.literal("등록 실패 : 트랙 이름을 불러오지 못했습니다."), false);
					return;
				}

				String engineName = (cachedEngineName == null) ? "UNKNOWN" : cachedEngineName;

				long timeMillis = AddRankingScreen.parseTimeToMillis(s);
				if (timeMillis < 0) return;

				String player = client.player.getGameProfile().getName();
				if (!com.example.rankinglog.ModConfig.get().autoSubmitEnabled) return;

				String bodyName = (cachedKartBodyName == null || cachedKartBodyName.isBlank())
						? "UNKNOWN"
						: cachedKartBodyName;

				AutoSubmitter.submitAsync(
						player,
						track,
						s,
						timeMillis,
						0,
						engineName,
						bodyName
				);
			}

			lastTitle = t;
			lastSubtitle = s;
		}
	}

	/* =========================
	   ✅ 카트바디 스캔
	   - 왼손 우선
	   - 왼손이 비면: "메인핸드 마지막 유효값" 사용
	   - 메인핸드는 "비었을 때 갱신하지 않음" => 직전 값 유지
	   ========================= */
	@Unique
	private void scanAndCacheKartBody() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;

		long now = System.currentTimeMillis();
		if (now - lastBodyScanMs < BODY_SCAN_INTERVAL_MS) return;
		lastBodyScanMs = now;

		// 1) 메인핸드는 유효할 때만 cachedMainhandName 갱신(비면 직전 유지)
		String main = readMainhandNameAndCacheLast();

		// 2) 왼손 우선으로 읽기(유효하면 즉시 최종값으로)
		String off = readOffhandNameIfPresent();

		if (off != null && !off.isBlank()) {
			cachedKartBodyName = off;
			return;
		}

		// 3) 왼손이 없으면 "메인핸드 직전값"
		if (cachedMainhandName != null && !cachedMainhandName.isBlank()) {
			cachedKartBodyName = cachedMainhandName;
			return;
		}

		// 4) 그래도 없으면 (아직 아이템이 안 잡힌 상태) 그냥 유지
		// 캡처 종료 시 UNKNOWN으로 처리됨
	}

	@Unique
	private String readOffhandNameIfPresent() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return null;

		try {
			var stack = client.player.getOffHandStack();
			if (stack == null || stack.isEmpty()) return null;

			String name = safeItemName(stack.getName().getString());
			if (name == null) return null;

			String key = stack.getItem().toString() + "|" + name;
			if (key.equals(lastOffhandKey)) return name;
			lastOffhandKey = key;

			return name;
		} catch (Throwable ignored) {
			return null;
		}
	}

	/**
	 * ✅ 요구사항:
	 * - 메인핸드에 아이템이 있다가 empty가 됐을 때, 직전 이름을 쓰고 싶다
	 * => 그래서 empty이면 cachedMainhandName을 "절대 지우지 않음"
	 * => 아이템이 유효할 때만 갱신
	 */
	@Unique
	private String readMainhandNameAndCacheLast() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return null;

		try {
			var stack = client.player.getMainHandStack();

			// ✅ empty면 "직전값 유지"
			if (stack == null || stack.isEmpty()) {
				return cachedMainhandName;
			}

			String name = safeItemName(stack.getName().getString());
			if (name == null) return cachedMainhandName;

			String key = stack.getItem().toString() + "|" + name;
			if (key.equals(lastMainhandKey)) return cachedMainhandName;

			lastMainhandKey = key;
			cachedMainhandName = name;
			return cachedMainhandName;
		} catch (Throwable ignored) {
			return cachedMainhandName;
		}
	}

	@Unique
	private String safeItemName(String raw) {
		if (raw == null) return null;
		String s = raw.trim();
		return s.isEmpty() ? null : s;
	}

	@Unique
	private String findEngineNameNearPlayer() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) return null;

		Vec3d p = client.player.getPos();

		Box box = new Box(
				p.x - ENGINE_SCAN_RADIUS_XZ, p.y - ENGINE_SCAN_RADIUS_Y,  p.z - ENGINE_SCAN_RADIUS_XZ,
				p.x + ENGINE_SCAN_RADIUS_XZ, p.y + ENGINE_SCAN_RADIUS_Y,  p.z + ENGINE_SCAN_RADIUS_XZ
		);

		List<DisplayEntity.TextDisplayEntity> list = new ArrayList<>();

		for (Entity e : client.world.getEntities()) {
			if (e instanceof DisplayEntity.TextDisplayEntity td) {
				if (box.contains(td.getPos())) {
					list.add(td);
				}
			}
		}

		if (list.isEmpty()) return null;

		for (DisplayEntity.TextDisplayEntity td : list) {
			String text = td.getText().getString().replace("\n", " ").trim();
			if (ENGINE_NAME_PATTERN.matcher(text).find()) {
				var m = ENGINE_NAME_PATTERN.matcher(text);
				if (m.find()) {
					return m.group(1).toUpperCase();
				}
				return text;
			}
		}

		list.sort(Comparator.comparingDouble(Entity::getY).reversed());
		String top = list.get(0).getText().getString().replace("\n", " ").replaceAll("\\s+", " ").trim();
		return top.isBlank() ? null : top;
	}

	@Unique
	private static boolean isAllowedServer() {
		MinecraftClient client = MinecraftClient.getInstance();
		var me = client.player;

		String key;

		if (client.getServer() != null) {
			key = "single";
			showServerDebugOnce(key, "§e[MCRiderRanking] 통합 서버(싱글플레이) 감지 -> 자동 기록 비활성화");
			return false;
		}

		ServerInfo info = client.getCurrentServerEntry();
		if (info == null || info.address == null) {
			key = "none";
			showServerDebugOnce(key, "§c[MCRiderRanking] 서버 정보 없음 (접속 전/나가는 중/로딩 중)");
			return false;
		}

		String addr = info.address.trim().toLowerCase();
		key = "multi:" + addr;

		String allowed = ALLOWED_ADDRESS.trim().toLowerCase();
		String devserver = ALLOWED_ADDRESS1.trim().toLowerCase();

		boolean ok = addr.equals(allowed) || addr.equals(devserver);

		showServerDebugOnce(
				key,
				"§a[MCRiderRanking] " + (ok ? "자동 기록 활성화" : "자동 기록 비활성화")
		);

		if (me != null && isNewKey(key)) {
			boolean addrHasPort = addr.contains(":");
			boolean allowedHasPort = allowed.contains(":");
			if (!ok && addrHasPort != allowedHasPort) {
				me.sendMessage(Text.literal("§e[MCRiderRanking] 포트 포함 여부가 달라서 불일치일 수 있음"), false);
			}
		}

		return ok;
	}

	@Unique
	private static void showServerDebugOnce(String key, String msg) {
		MinecraftClient client = MinecraftClient.getInstance();
		var me = client.player;
		if (me == null) return;

		if (!isNewKey(key)) return;

		long now = System.currentTimeMillis();
		if (now - lastDebugAtMs < 300) return;
		lastDebugAtMs = now;

		lastDebugKey = key;
		me.sendMessage(Text.literal(msg), false);
	}

	@Unique
	private static boolean isNewKey(String key) {
		return lastDebugKey == null || !lastDebugKey.equals(key);
	}

	@Unique
	private boolean tryCacheTrackName() {
		List<DisplayEntity.TextDisplayEntity> list = getTextDisplaysSortedByY();
		cachedList = list;

		if (list.size() < 3) {
			cachedTrackName = null;
			return false;
		}

		String raw = list.get(2).getText().getString();
		if (raw == null || raw.isBlank()) {
			cachedTrackName = null;
			return false;
		}

		cachedTrackName = raw;
		return true;
	}

	@Unique
	private List<DisplayEntity.TextDisplayEntity> getTextDisplaysSortedByY() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) return List.of();

		List<DisplayEntity.TextDisplayEntity> result = new ArrayList<>();

		for (Entity e : client.world.getEntities()) {
			if (e instanceof DisplayEntity.TextDisplayEntity td) {
				if (LOBBY_BOX.contains(td.getPos())) {
					result.add(td);
				}
			}
		}

		result.sort(
				Comparator
						.comparingDouble(Entity::getY).reversed()
						.thenComparingDouble(Entity::getX)
						.thenComparingDouble(Entity::getZ)
		);

		return result;
	}

	@Unique
	private boolean hasOtherPlayerNearMe() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) return false;

		var me = client.player;
		double mx = me.getX();
		double my = me.getY();
		double mz = me.getZ();

		for (var p : client.world.getPlayers()) {
			if (p == null || p == me) continue;

			double dx = p.getX() - mx;
			double dy = p.getY() - my;
			double dz = p.getZ() - mz;

			double distSq = dx*dx + dy*dy + dz*dz;
			if (distSq <= NEAR_PLAYER_RADIUS_SQ) {
				return true;
			}
		}
		return false;
	}
}
