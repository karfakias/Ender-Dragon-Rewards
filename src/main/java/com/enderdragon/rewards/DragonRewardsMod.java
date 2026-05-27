package com.enderdragon.rewards;

import com.enderdragon.rewards.config.DragonRewardsConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class DragonRewardsMod implements ModInitializer {
    public static final String MOD_ID = "dragonrewards";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static DragonRewardsConfig CONFIG;
    public static Path CONFIG_PATH;

    @Override
    public void onInitialize() {
        CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("dragonrewards.json");
        CONFIG = DragonRewardsConfig.load(CONFIG_PATH);

        DragonRewardManager.init();
        DragonRewardsCommands.init();

        LOGGER.info("Dragon Rewards initialized.");
    }

    public static void reloadConfig() {
        CONFIG = DragonRewardsConfig.load(CONFIG_PATH);
    }

    public static boolean saveConfig() {
        try {
            CONFIG.sanitize();
            CONFIG.save(CONFIG_PATH);
            return true;
        } catch (IOException ex) {
            LOGGER.error("Failed to save dragon rewards config", ex);
            return false;
        }
    }
}
