package com.example.rankinglog;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import org.lwjgl.glfw.GLFW;

public class ClientKeybinds {

    private static KeyBinding OPEN_RANKING;
    private static KeyBinding OPEN_SETTINGS_KEY;

    public static void init() {
        ModConfig.load();
        OPEN_RANKING = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rankinglog.open_ranking",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                "category.rankinglog"
        ));

        // F8 키 등록
        OPEN_SETTINGS_KEY = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.mcrider_ranking.open_settings", // 번역 키 (lang 파일 없어도 동작함)
                        GLFW.GLFW_KEY_F8,
                        "category.rankinglog"
                )
        );
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_RANKING.wasPressed()) {
                openRankingScreen();
            }
            while (OPEN_SETTINGS_KEY.wasPressed()) {
                openSettingsScreen();
            }
        });
    }


    private static void openRankingScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // F7 눌렀을 때 “다시” LOBBY_BOX에서 읽기
        String track = TrackNameUtil.readTrackNameFromLobbyBox();

//        if (track == null || track.isBlank()) {
//            client.player.sendMessage(net.minecraft.text.Text.literal("§c트랙 이름을 찾지 못했습니다 (LOBBY_BOX 확인)"), false);
//            return;
//        }

        client.send(() -> client.setScreen(new RankingScreen(track)));
    }


    private static void openSettingsScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.setScreen(new ModSettingsScreen(client.currentScreen));
    }
}
