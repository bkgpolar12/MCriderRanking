package com.example.rankinglog;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.option.KeyBinding;


public class RankingLogMod implements ClientModInitializer {

    private static KeyBinding OPEN_UI_KEY;

    @Override
    public void onInitializeClient() {
        ClientKeybinds.init();
        ModGatekeeper.init();


        System.out.println("[RankingLog] Client initialized");
}
}