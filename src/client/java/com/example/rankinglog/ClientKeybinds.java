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
import java.util.Set;

public class ClientKeybinds {

    private static KeyBinding OPEN_RANKING;
    private static KeyBinding DEBUG_PASSENGER_KEY;

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

/*        DEBUG_PASSENGER_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rankinglog.debug_passenger",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                "category.rankinglog"
        ));*/

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_RANKING.wasPressed()) {
                openMainMenu();
            }

/*            while (DEBUG_PASSENGER_KEY.wasPressed()) {
                debugRootVehiclePassengers();
            }*/
        });
    }

    private static void debugRootVehiclePassengers() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        client.player.sendMessage(Text.literal("§6§l[Debug] RootVehicle 승객 분석"), false);

        // 1. 탑승 여부 체크
        Entity vehicle = client.player.getRootVehicle();
        if (vehicle == client.player) {
            client.player.sendMessage(Text.literal("§c[결과] 현재 탑승 중인 차량이 없습니다."), false);
            return;
        }

        // 2. 승객 리스트 할당 및 foreach 루프
        List<Entity> passengers = vehicle.getPassengerList();
        client.player.sendMessage(Text.literal("§f차량: §a" + vehicle.getType().getName().getString() + " §7| 승객: §e" + passengers.size()), false);

        for (Entity p : passengers) {
            boolean isMe = (p == client.player);
            String className = p.getClass().getSimpleName();
            Text customNameText = p.getCustomName();
            String customName = (customNameText != null) ? customNameText.getString() : "§8(null)§f";
            Set<String> tags = p.getCommandTags();

            client.player.sendMessage(Text.literal("§e- " + (isMe ? "§d(본인) " : "") + "§b" + className), false);
            client.player.sendMessage(Text.literal("    §f이름: " + customName + " §7| 태그: " + tags), false);

            if (p instanceof DisplayEntity.ItemDisplayEntity display) {
                var stack = display.getItemStack();
                String stackName = (stack != null && !stack.isEmpty()) ? stack.getName().getString() : "§8(없음)§f";
                client.player.sendMessage(Text.literal("    §3└ [Item] §f" + stackName), false);

                if ("mcrider-datacarrier".equalsIgnoreCase(customName) || tags.contains("mcrider-datacarrier") || stackName.contains("mcrider-datacarrier")) {
                    client.player.sendMessage(Text.literal("    §a§l▶ DataCarrier 감지 성공!"), false);
                }
            }
        }
    }

    private static void openMainMenu() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        client.send(() -> client.setScreen(new MainMenuScreen()));
        RankingScreen.ApiCache.fetchAllAsync(false, p -> {}, err -> {});
    }
}