package com.example.rankinglog;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class RankingLogMod implements ClientModInitializer {

    private static KeyBinding OPEN_UI_KEY;

    @Override
    public void onInitializeClient() {

        System.out.println("[RankingLog] Client initialized");

        OPEN_UI_KEY = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.rankinglog.open_ui",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_H,
                        "category.rankinglog"
                )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_UI_KEY.wasPressed()) {
                client.setScreen(new RankingLogScreen());
            }
        });
    }
}
