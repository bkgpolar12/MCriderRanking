package com.example.rankinglog;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
//import net.minecraft.entity.decoration.DisplayEntity;
//import net.minecraft.text.Text;
//import net.minecraft.util.math.Box;
//import net.minecraft.entity.attribute.EntityAttributeInstance;
//import net.minecraft.entity.attribute.EntityAttributes;
//import net.minecraft.entity.attribute.EntityAttributeModifier;
//import net.minecraft.util.Identifier;

import org.lwjgl.glfw.GLFW;

public class ClientKeybinds {

    private static KeyBinding OPEN_RANKING;
    private static KeyBinding OPEN_SETTINGS_KEY;

    //테스트용: U 키
//    private static KeyBinding DEBUG_NEAREST_ITEM_DISPLAY_KEY;

    public static void init() {
        ModConfig.load();

        OPEN_RANKING = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rankinglog.open_ranking",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                "category.rankinglog"
        ));

        OPEN_SETTINGS_KEY = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.mcrider_ranking.open_settings",
                        GLFW.GLFW_KEY_F8,
                        "category.rankinglog"
                )
        );

        //U 키 등록
//        DEBUG_NEAREST_ITEM_DISPLAY_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
//                "key.rankinglog.debug_nearest_item_display_name",
//                InputUtil.Type.KEYSYM,
//                GLFW.GLFW_KEY_U,
//                "category.rankinglog"
//        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_RANKING.wasPressed()) {
                openRankingWithPrepare();
            }
            while (OPEN_SETTINGS_KEY.wasPressed()) {
                openSettingsScreen();
            }
//            while (DEBUG_NEAREST_ITEM_DISPLAY_KEY.wasPressed()) {
//                debugPrintNearestItemDisplayCustomName();
//            }
        });
    }

//    private static void debugPrintNearestItemDisplayCustomName() {
//        MinecraftClient client = MinecraftClient.getInstance();
//        if (client.world == null || client.player == null) return;
//
//        // ===== 1) nearest item_display customName =====
//        double radius = 16.0;
//        Box box = client.player.getBoundingBox().expand(radius);
//
//        DisplayEntity.ItemDisplayEntity nearest = null;
//        double bestDistSq = Double.MAX_VALUE;
//
//        for (DisplayEntity.ItemDisplayEntity e : client.world.getEntitiesByClass(
//                DisplayEntity.ItemDisplayEntity.class,
//                box,
//                entity -> true
//        )) {
//            double d = client.player.squaredDistanceTo(e);
//            if (d < bestDistSq) {
//                bestDistSq = d;
//                nearest = e;
//            }
//        }
//
//        if (nearest == null) {
//            client.player.sendMessage(Text.literal("근처 item_display 없음. (반경 " + (int) radius + "블록)"), false);
//        } else {
//            Text customName = nearest.getCustomName(); // null 가능
//            String nameStr = (customName == null) ? "(customName 없음)" : customName.getString();
//            double dist = Math.sqrt(bestDistSq);
//
//            client.player.sendMessage(
//                    Text.literal("가장 가까운 item_display customName: " + nameStr
//                            + " (거리 " + String.format("%.2f", dist) + ")"),
//                    false
//            );
//        }
//
//        // ===== 2) EXPLOSION_KNOCKBACK_RESISTANCE modifiers dump =====
//        EntityAttributeInstance inst =
//                client.player.getAttributeInstance(EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE);
//
//        if (inst == null) {
//            client.player.sendMessage(Text.literal("[EXPLOSION_KNOCKBACK_RESISTANCE] attribute instance 없음"), false);
//            return;
//        }
//
//        client.player.sendMessage(Text.literal("§b[EXPLOSION_KNOCKBACK_RESISTANCE modifiers]"), false);
//
//        boolean foundKartEngineReal = false;
//
//        for (net.minecraft.entity.attribute.EntityAttributeModifier mod : inst.getModifiers()) {
//            Identifier id = mod.id();              //1.21.5: record accessor
//            double amount = mod.value();          //1.21.5: record accessor
//            String op = String.valueOf(mod.operation()); //1.21.5: record accessor
//
//            String idStr = (id == null) ? "(id null?)" : id.toString();
//
//            client.player.sendMessage(
//                    Text.literal(" - " + idStr + " amount=" + amount + " op=" + op),
//                    false
//            );
//
//            if (id != null && "kart-engine-real".equals(id.getPath())) {
//                foundKartEngineReal = true;
//                client.player.sendMessage(
//                        Text.literal("§a[FOUND] kart-engine-real => id=" + idStr
//                                + " amount=" + amount + " op=" + op),
//                        false
//                );
//            }
//        }
//
//        if (!foundKartEngineReal) {
//            client.player.sendMessage(Text.literal("§7(kart-engine-real path를 가진 modifier를 못 찾음)"), false);
//        }
//    }

    private static void openRankingWithPrepare() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        String track = TrackNameUtil.readTrackNameFromLobbyBox();
        String finalTrack = (track == null || track.isBlank())
                ? "[α] 빌리지 고가의 질주"
                : track.replace("\n", " ").replaceAll("\\s+", " ").trim();

        //1) 화면을 즉시 연다 (UX)
        client.send(() -> client.setScreen(new RankingScreen(finalTrack)));

        //2) 동시에 "전체 데이터(getAll)"를 한번에 준비한다
        RankingScreen.ApiCache.fetchAllAsync(false, p -> {}, err -> {});
    }

    private static void openSettingsScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.setScreen(new ModSettingsScreen(client.currentScreen));
    }
}