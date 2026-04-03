package com.example.mixin.client;

import com.example.rankinglog.AddRankingScreen;
import com.example.rankinglog.AutoSubmitter;
import com.example.rankinglog.BodyCaptureManager;
import com.example.rankinglog.DebugLog;
import com.example.rankinglog.ModConfig;
import com.example.rankinglog.ModGatekeeper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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

	//로그 스팸 방지용
	@Unique private static long lastTrackLogMs = 0;
	@Unique private static String lastTrackLogValue = null;

	@Unique private static long lastEngineLogMs = 0;
	@Unique private static String lastEngineLogValue = null;

	@Unique private static final long LOG_COOLDOWN_MS = 600; // 같은 내용 0.6초 내 중복 출력 방지

	// 엔진 이름 후보 패턴
	@Unique
	private static final Pattern ENGINE_NAME_PATTERN =
			Pattern.compile("\\[[A-Z0-9.+]+\\s*엔진", Pattern.CASE_INSENSITIVE);

	@Unique private static final double ENGINE_SCAN_RADIUS_XZ = 3.0;
	@Unique private static final double ENGINE_SCAN_RADIUS_Y  = 6.0;

	@Unique private static String cachedTireName = "UNKNOWN";
	@Unique private static long lastTireScanMs = 0;
	@Unique private static final long TIRE_SCAN_INTERVAL_MS = 800;

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
		//서버 체크
		// -------------------------
		if (isAllowedServer()) {

			// -------------------------
			//(1) 카트바디 캡처 시작 조건: HUD 타이틀 "로딩중..."
			//(2) 적용된 모드 캡처 시작 조건도 동일(여기서 onLoadingTitle 호출)
			// -------------------------
			if ("로딩중...".equals(t)) {
				BodyCaptureManager.onLoadingDetected("hud_title");
				try { ModGatekeeper.onLoadingTitle(); } catch (Throwable ignored) {}
			}

			// -------------------------
			//카트바디 스캔: 매 프레임 호출(내부에서 active일 때만 150ms)
			// -------------------------
			BodyCaptureManager.tickScan();

			// -------------------------
			//카트바디 캡처 종료 조건: 타이틀 "3"
			// -------------------------
			if ("3".equals(t)) {
				BodyCaptureManager.onTitle3();
				// 타이어 감지
				long nowTire = System.currentTimeMillis();
				if (nowTire - lastTireScanMs > TIRE_SCAN_INTERVAL_MS) {
					lastTireScanMs = nowTire;

					String tire = findTireNameFromAttribute();
					cachedTireName = tire;

					if (DebugLog.enabled()) {
						DebugLog.chat("§d[Tire] 감지: " + cachedTireName);
					}
				}
			}

			// -------------------------
			//카트바디/모드 공통 실패 종료 조건: "완주 실패"
			//  - 카트바디: 종료 처리
			//  - 모드: freezeNow로 종료 확정
			// -------------------------
			if ("완주 실패".equals(t)) {
				BodyCaptureManager.onRaceFailed();
				try { ModGatekeeper.freezeNow(); } catch (Throwable ignored) {}
			}

			// -------------------------
			//적용된 모드 캡처 종료 조건: 타이틀 "1"
			// -------------------------
			if ("1".equals(t)) {
				try { ModGatekeeper.freezeNow(); } catch (Throwable ignored) {}
			}
		}

		// -------------------------
		// 트랙 이름 재시도 처리
		// -------------------------
		if (pendingTrackRetry) {
			long now = System.currentTimeMillis();
			if (now - trackRetryStartMs >= 200) {
				trackRetryStartMs = now;
				trackRetryCount++;

				boolean ok = tryCacheTrackName();
				logTrack(ok ? ("§7[Track] 재시도 성공: " + safeShow(cachedTrackName)) : "§7[Track] 재시도 실패(list 부족/빈값)");

				if (ok && cachedTrackName != null && !cachedTrackName.isBlank()) {
					pendingTrackRetry = false;

					String track = cachedTrackName.replace("\n", " ").replaceAll("\\s+", " ").trim();

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

					//여기서는 freezeNow 호출하지 않음 (요구사항: "1" 또는 "완주 실패"에서만 종료)
					String modesCsv = "없음";
					try { modesCsv = ModGatekeeper.getModesCsv(); } catch (Throwable ignored) {}

					String player = client.player.getGameProfile().getName();
					if (!ModConfig.get().autoSubmitEnabled) return;

					String bodyName = BodyCaptureManager.getCachedKartBodyNameOrUnknown();

					AutoSubmitter.submitAsync(
							player,
							track,
							pendingTimeStr,
							pendingTimeMillis,
							0,
							engineName,
							bodyName,
							cachedTireName,
							modesCsv
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

				// "3" 타이틀 감지 시: 엔진 이름 캐싱(모드 freezeNow는 여기서 하지 않음)
				if (t.equals("3")) {
					long now = System.currentTimeMillis();
					if (now - lastEngineScanMs > 800) {
						lastEngineScanMs = now;

						//엔진 감지
						String engineRaw = findEngineNameNearPlayer();
						if (engineRaw != null) {
							String engine = engineRaw
									.replace("[", "")
									.replace("]", "")
									.replace("엔진", "")
									.trim();
							cachedEngineName = engine.isBlank() ? "UNKNOWN" : engine;
							logEngine("§a[Engine] 감지 성공: " + cachedEngineName);
						} else {
							logEngine("§c[Engine] 감지 실패(주변 텍스트디스플레이 없음/패턴 불일치)");
							if (DebugLog.enabled()) DebugLog.chat("§7[Engine] box 내 텍스트디스플레이가 없거나 패턴이 안맞음");
						}
					}
				}
			}

			// -------------------------
			// 완주 subtitle 감지
			// -------------------------
			if (!s.equals(lastSubtitle) && s.matches("^\\d{2}:\\d{2}\\.\\d{3}$")) {

				if (!soloOk) {
					long now = System.currentTimeMillis();
					boolean sameTimeAsLast = (lastSoloFailTimeStr != null && lastSoloFailTimeStr.equals(s));
					boolean inCooldown = (now - lastSoloFailMsgMs) < SOLO_FAIL_COOLDOWN_MS;

					// 1. 자동 제출이 꺼져 있으면 아예 실행하지 않고 돌아감
					if (!ModConfig.get().autoSubmitEnabled) {
						return;
					}

					// 2. 자동 제출이 켜져 있는 경우 중복 메시지 방지 로직 확인
					if (!sameTimeAsLast && !inCooldown) {
						client.player.sendMessage(Text.literal("플레이어가 최대 1명이어야 기록이 등록됩니다."), false);
						lastSoloFailTimeStr = s;
						lastSoloFailMsgMs = now;
					}
					return;
				}

				//여기서는 freezeNow 호출하지 않음 (요구사항 준수)
				String modesCsv = "없음";
				try { modesCsv = ModGatekeeper.getModesCsv(); } catch (Throwable ignored) {}

				if (DebugLog.enabled()) DebugLog.chat("§7[Mode] 제출 모드: " + modesCsv);

				boolean ok = tryCacheTrackName();
				logTrack(ok ? ("§a[Track] 감지 성공: " + safeShow(cachedTrackName)) : "§c[Track] 감지 실패(list 부족/빈값)");

				if (!ok) {
					pendingTrackRetry = true;
					trackRetryStartMs = System.currentTimeMillis();
					trackRetryCount = 0;

					pendingTimeStr = s;
					pendingTimeMillis = AddRankingScreen.parseTimeToMillis(s);
					return;
				}

				String track = cachedTrackName.replace("\n", " ").replaceAll("\\s+", " ").trim();

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
				logEngine("§7[Engine] 제출 엔진: " + engineName);

				long timeMillis = AddRankingScreen.parseTimeToMillis(s);
				if (timeMillis < 0) return;

				String player = client.player.getGameProfile().getName();
				if (!ModConfig.get().autoSubmitEnabled) return;

				String bodyName = BodyCaptureManager.getCachedKartBodyNameOrUnknown();
				if (DebugLog.enabled()) DebugLog.chat("§7[Body] 제출 바디: " + bodyName);

				AutoSubmitter.submitAsync(
						player,
						track,
						s,
						timeMillis,
						0,
						engineName,
						bodyName,
						cachedTireName,
						modesCsv
				);
			}

			lastTitle = t;
			lastSubtitle = s;
		}
	}

	@Unique
	private String findTireNameFromAttribute() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return "UNKNOWN";

		var inst = client.player.getAttributeInstance(
				net.minecraft.entity.attribute.EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE
		);
		if (inst == null) return "UNKNOWN";

		for (var mod : inst.getModifiers()) {
			var id = mod.id();
			if (id != null && id.toString().equals("minecraft:kart-tire")) {
				int value = (int) mod.value();
				return mapTireValueToName(value);
			}
		}

		return "UNKNOWN";
	}

	@Unique
	private static String mapTireValueToName(int value) {
		return switch (value) {
			case 0 -> "레이싱 타이어";
			case 1 -> "스파이크 타이어";
			default -> "UNKNOWN";
		};
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
			var m = ENGINE_NAME_PATTERN.matcher(text);
			if (m.find()) {
				return m.group(0).toUpperCase();
			}
		}

		// fallback: 위쪽 텍스트
		list.sort(Comparator.comparingDouble(Entity::getY).reversed());
		String top = list.get(0).getText().getString().replace("\n", " ").replaceAll("\\s+", " ").trim();
		return top.isBlank() ? null : top;
	}

	@Unique
	private static final Set<String> ALLOWED_PLAYERS = Set.of(
			"BKGpolar"
	);

	@Unique
	private static boolean isAllowedPlayer() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return false;

		String name = client.player.getGameProfile().getName();

		return ALLOWED_PLAYERS.contains(name);
	}

	@Unique
	private static final boolean USE_PLAYER_LIMIT = false; //개발자 전용 또는 CBT (false = 배포용)

	@Unique
	private static boolean isAllowedServer() {
		MinecraftClient client = MinecraftClient.getInstance();
		var me = client.player;

		// 1. 싱글 플레이어 체크
		if (client.getServer() != null) {
			showServerDebugOnce("single", "§e[MCRiderRanking] 통합 서버(싱글플레이) 감지 -> 자동 기록 비활성화");
			return false;
		}

		// 2. 서버 정보 존재 여부 체크
		ServerInfo info = client.getCurrentServerEntry();
		if (info == null || info.address == null) {
			showServerDebugOnce("none", "§c[MCRiderRanking] 서버 정보 없음 (접속 전/나가는 중/로딩 중)");
			return false;
		}

		String addr = info.address.trim().toLowerCase();
		String allowed = ALLOWED_ADDRESS.trim().toLowerCase();
		String devserver = ALLOWED_ADDRESS1.trim().toLowerCase();

		boolean ok = addr.equals(allowed) || (USE_PLAYER_LIMIT && addr.equals(devserver));

		// 3. 허용되지 않은 서버인 경우
		if (!ok) {
			String key = "multi_fail:" + addr;

			if (me != null && isNewKey(key)) {
				boolean addrHasPort = addr.contains(":");
				boolean allowedHasPort = allowed.contains(":");
				if (addrHasPort != allowedHasPort) {
					DebugLog.chat("§e[MCRiderRanking] 포트 포함 여부가 달라서 불일치일 수 있음");
				}
			}

			showServerDebugOnce(key, "§c[MCRiderRanking] 자동 기록 비활성화 (허용되지 않은 서버)");
			return false;
		}

		// 4. 플레이어 제한 체크 (CBT 모드 전용 로직)
		if (USE_PLAYER_LIMIT) {
			boolean playerOk = isAllowedPlayer();
			if (!playerOk) {
				showServerDebugOnce("blocked_player", "§c[MCRiderRanking] 권한이 없는 플레이어입니다. (자동 기록 비활성화)");
				return false;
			}
		}

		// 5. 서버도 맞고, (제한이 꺼져있거나 권한이 있는) 경우 최종 성공
		showServerDebugOnce("multi_ok:" + addr, "§a[MCRiderRanking] 자동 기록 활성화");
		return true;
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

	/* =========================
      로그 유틸(스팸 방지)
       ========================= */
	@Unique
	private void logTrack(String msg) {
		if (!DebugLog.enabled()) return;
		long now = System.currentTimeMillis();
		if (msg.equals(lastTrackLogValue) && (now - lastTrackLogMs) < LOG_COOLDOWN_MS) return;
		lastTrackLogValue = msg;
		lastTrackLogMs = now;
		DebugLog.chat(msg);
	}

	@Unique
	private void logEngine(String msg) {
		if (!DebugLog.enabled()) return;
		long now = System.currentTimeMillis();
		if (msg.equals(lastEngineLogValue) && (now - lastEngineLogMs) < LOG_COOLDOWN_MS) return;
		lastEngineLogValue = msg;
		lastEngineLogMs = now;
		DebugLog.chat(msg);
	}

	@Unique
	private String safeShow(String v) {
		if (v == null) return "null";
		String s = v.replace("\n", " ").replaceAll("\\s+", " ").trim();
		if (s.isEmpty()) return "(blank)";
		return s;
	}
}
