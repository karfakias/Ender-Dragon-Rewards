package com.enderdragon.rewards.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class DragonRewardsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enableElytraDrops = true;
    public boolean enableDragonHeadDrops = true;

    public double elytraBaseChance = 0.10;
    public double elytraFailureIncrement = 0.05;
    public double elytraMaxChance = 1.0;

    public double dragonHeadBaseChance = 0.20;
    public double dragonHeadFailureIncrement = 0.05;
    public double dragonHeadMaxChance = 1.0;

    public int chestSpawnRadius = 5;
    public int chestYOffsetFromPortal = 2;
    public int chestSpawnCenterX = 0;
    public int chestSpawnCenterZ = 0;
    public int chestSpawnFixedY = 65;
    public int rewardSpawnDelaySeconds = 8;
    public int rewardClaimTimeMinutes = 30;
    public boolean debugMode = false;

    public Messages messages = new Messages();

    public static DragonRewardsConfig load(Path configFile) {
        DragonRewardsConfig defaults = new DragonRewardsConfig();
        try {
            if (Files.notExists(configFile.getParent())) {
                Files.createDirectories(configFile.getParent());
            }

            if (Files.notExists(configFile)) {
                defaults.save(configFile);
                return defaults;
            }

            try (Reader reader = Files.newBufferedReader(configFile)) {
                DragonRewardsConfig config = GSON.fromJson(reader, DragonRewardsConfig.class);
                if (config == null) {
                    defaults.save(configFile);
                    return defaults;
                }
                config.sanitize();
                config.save(configFile);
                return config;
            }
        } catch (IOException | JsonSyntaxException ex) {
            return defaults;
        }
    }

    public void save(Path configFile) throws IOException {
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(this, writer);
        }
    }

    public void sanitize() {
        elytraBaseChance = clamp01(elytraBaseChance);
        elytraFailureIncrement = clampNonNegative(elytraFailureIncrement);
        elytraMaxChance = clampRange(elytraMaxChance, elytraBaseChance, 1.0);

        dragonHeadBaseChance = clamp01(dragonHeadBaseChance);
        dragonHeadFailureIncrement = clampNonNegative(dragonHeadFailureIncrement);
        dragonHeadMaxChance = clampRange(dragonHeadMaxChance, dragonHeadBaseChance, 1.0);

        chestSpawnRadius = Math.max(1, chestSpawnRadius);
        chestYOffsetFromPortal = Math.max(1, chestYOffsetFromPortal);
        chestSpawnFixedY = Math.max(-64, Math.min(320, chestSpawnFixedY));
        rewardSpawnDelaySeconds = Math.max(0, rewardSpawnDelaySeconds);
        rewardClaimTimeMinutes = Math.max(1, rewardClaimTimeMinutes);

        if (messages == null) {
            messages = new Messages();
        }
    }

    private static double clamp01(double value) {
        return clampRange(value, 0.0, 1.0);
    }

    private static double clampNonNegative(double value) {
        return Math.max(0.0, value);
    }

    private static double clampRange(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static class Messages {
        public String nothingDropped = "{player} killed the Ender Dragon but was unlucky. The chances for Elytra and Dragon Head have increased.";
        public String onlyElytra = "{player} killed the Ender Dragon and received an Elytra. Dragon Head drop chance has increased.";
        public String onlyDragonHead = "{player} killed the Ender Dragon and received a Dragon Head. Elytra drop chance has increased.";
        public String bothDropped = "{player} killed the Ender Dragon and received rare rewards.";
        public String unauthorizedChest = "This reward chest belongs to {player}.";
    }
}
