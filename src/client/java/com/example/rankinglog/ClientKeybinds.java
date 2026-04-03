package com.example.rankinglog;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ClientKeybinds {

    private static KeyBinding OPEN_RANKING;
    // private static KeyBinding OPEN_SETTINGS_KEY;
    private static KeyBinding DEBUG_LOBBY_TEXT_KEY; // U키 디버그용

    // InGameHudMixin에 있던 로비 텍스트 디스플레이 탐색용 박스 좌표
    private static final Box LOBBY_BOX = new Box(
            -21, 3, 155,
            -15, -1, 152
    );

    public static void init() {
        ModConfig.load();

        OPEN_RANKING = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rankinglog.open_ranking",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                "category.rankinglog"
        ));

        // U 키 디버그 바인딩 등록
        DEBUG_LOBBY_TEXT_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rankinglog.debug_lobby_text",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                "category.rankinglog"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_RANKING.wasPressed()) {
                openMainMenu();
            }
            // U 키가 눌렸을 때 디버그 메서드 실행
//            while (DEBUG_LOBBY_TEXT_KEY.wasPressed()) {
//                debugPrintLobbyTextDisplays();
//            }
        });
    }

    private static void debugPrintLobbyTextDisplays() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        // 월드의 모든 엔티티를 도는 대신, getEntitiesByClass를 사용해 박스 안의 텍스트 디스플레이만 빠르게 가져옵니다.
        List<DisplayEntity.TextDisplayEntity> list = new ArrayList<>(
                client.world.getEntitiesByClass(
                        DisplayEntity.TextDisplayEntity.class,
                        LOBBY_BOX,
                        entity -> true
                )
        );

        if (list.isEmpty()) {
            client.player.sendMessage(Text.literal("§c[Debug] 지정된 LOBBY_BOX 내에 텍스트 디스플레이가 없습니다."), false);
            return;
        }

        // InGameHudMixin과 동일한 정렬 방식 (Y 내림차순 -> X 오름차순 -> Z 오름차순)
        list.sort(
                Comparator
                        .comparingDouble(Entity::getY).reversed()
                        .thenComparingDouble(Entity::getX)
                        .thenComparingDouble(Entity::getZ)
        );

        client.player.sendMessage(Text.literal("§a[Debug] LOBBY_BOX 텍스트 디스플레이 목록 (" + list.size() + "개)"), false);

        // 정렬된 순서대로 인덱스와 내용 출력
        for (int i = 0; i < list.size(); i++) {
            DisplayEntity.TextDisplayEntity td = list.get(i);
            Text textObj = td.getText();
            String content = (textObj != null) ? textObj.getString().replace("\n", "\\n") : "null";

            client.player.sendMessage(
                    Text.literal(String.format(" §7[%d] §f(y=%.2f) §e%s", i, td.getY(), content)),
                    false
            );
        }
    }

    private static void openRankingWithPrepare() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        String track = TrackNameUtil.readTrackNameFromLobbyBox();
        String finalTrack = (track == null || track.isBlank())
                ? "[α] 빌리지 고가의 질주"
                : track.replace("\n", " ").replaceAll("\\s+", " ").trim();

        // 1) 화면을 즉시 연다 (UX)
        client.send(() -> client.setScreen(new RankingScreen(finalTrack)));

        // 2) 동시에 "전체 데이터(getAll)"를 한번에 준비한다
        RankingScreen.ApiCache.fetchAllAsync(false, p -> {}, err -> {});
    }

    private static void openMainMenu() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // 1) 화면을 즉시 연다 (UX)
        client.send(() -> client.setScreen(new MainMenuScreen()));

        // 2) 동시에 "전체 데이터(getAll)"를 한번에 준비한다
        RankingScreen.ApiCache.fetchAllAsync(false, p -> {}, err -> {});
    }

    private static void openSettingsScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.setScreen(new ModSettingsScreen(client.currentScreen));
    }
}