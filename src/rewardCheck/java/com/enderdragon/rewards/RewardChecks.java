package com.enderdragon.rewards;

import com.enderdragon.rewards.config.DragonRewardsConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Standalone regression checks; run with ./gradlew rewardChecks (also included in build). */
public final class RewardChecks {
    public static void main(String[] args) throws Exception {
        configUpgradeAndPersistence();
        fixedRollsAndPity();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        enchantedRewardsSurviveSave();
        commands();
        System.out.println("Reward checks passed: config upgrades, fixed rolls, pity, book persistence and commands.");
    }

    private static void configUpgradeAndPersistence() throws Exception {
        Path path = Path.of("config-upgrade.json").toAbsolutePath();
        Files.writeString(path, "{\"elytraBaseChance\":0.25,\"enableDragonHeadDrops\":false,\"messages\":{\"bothDropped\":\"custom\"}}");
        DragonRewardsConfig config = DragonRewardsConfig.load(path);
        equal(0.25, config.elytraBaseChance, "Existing chance preserved");
        check(!config.enableDragonHeadDrops, "Existing toggle preserved");
        check(config.enableEgapDrops && config.enableSwiftSneakDrops, "New rewards enabled by default");
        equal(0.30, config.egapChance, "Egap default");
        equal(0.05, config.swiftSneakChance, "Swift Sneak default");
        check(config.swiftSneakLevel == 3, "Swift Sneak III default");
        check(config.messages.bothDropped.equals("custom") && config.messages.rewardsDropped.contains("{rewards}"), "Message upgrade");
        RewardType.EGAP.setChance(config, 0.42);
        RewardType.SWIFT_SNEAK.setChance(config, 0.12);
        RewardType.EGAP.setEnabled(config, false);
        config.save(path);
        config = DragonRewardsConfig.load(path);
        equal(0.42, config.egapChance, "Egap chance persists");
        equal(0.12, config.swiftSneakChance, "Swift Sneak chance persists");
        check(!config.enableEgapDrops, "Toggle persists");
        config.egapChance = -1;
        config.swiftSneakChance = 2;
        config.swiftSneakLevel = 10;
        config.sanitize();
        equal(0, config.egapChance, "Lower chance limit");
        equal(1, config.swiftSneakChance, "Upper chance limit");
        check(config.swiftSneakLevel == 3, "Book level limit");
        config.egapChance = Double.NaN;
        config.swiftSneakChance = Double.POSITIVE_INFINITY;
        config.sanitize();
        check(Double.isFinite(config.egapChance) && Double.isFinite(config.swiftSneakChance), "Non-finite values sanitized");
    }

    private static void fixedRollsAndPity() {
        DragonRewardsConfig config = new DragonRewardsConfig();
        for (RewardType reward : List.of(RewardType.EGAP, RewardType.SWIFT_SNEAK)) {
            double base = reward.baseChance(config);
            double chance = base;
            for (int miss = 0; miss < 1000; miss++) {
                chance = reward.nextChance(config, chance, false);
            }
            equal(base, chance, "Misses never increase " + reward);
            equal(base, reward.nextChance(config, chance, true), "Success keeps fixed chance");
            check(reward.rollsDrop(config, chance, Math.nextDown(base)), "Below threshold drops");
            check(!reward.rollsDrop(config, chance, base), "At threshold misses");
            reward.setChance(config, 0);
            check(!reward.rollsDrop(config, reward.baseChance(config), 0), "Zero never drops");
            reward.setChance(config, 1);
            check(reward.rollsDrop(config, reward.baseChance(config), Math.nextDown(1.0)), "One always drops");
            reward.setEnabled(config, false);
            check(!reward.rollsDrop(config, 1, 0), "Disabled never drops");
        }
        equal(0.15, RewardType.ELYTRA.nextChance(config, 0.10, false), "Elytra pity still increases");
        equal(0.20, RewardType.DRAGON_HEAD.nextChance(config, 0.80, true), "Head success resets pity");
        equal(1, RewardType.ELYTRA.nextChance(config, 0.99, false), "Pity cap");
        config.enableElytraDrops = false;
        equal(0.60, RewardType.ELYTRA.nextChance(config, 0.60, false), "Disabled pity unchanged");
    }

    private static void enchantedRewardsSurviveSave() {
        DragonRewardsMod.CONFIG = new DragonRewardsConfig();
        var vanilla = VanillaRegistries.createWorldLookup();
        // Datagen holders cannot be serialized as runtime holders. Bind Swift Sneak to a runtime registry.
        var enchantments = new net.minecraft.core.MappedRegistry<>(Registries.ENCHANTMENT, com.mojang.serialization.Lifecycle.stable());
        enchantments.register(Enchantments.SWIFT_SNEAK,
            vanilla.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SWIFT_SNEAK).value(),
            net.minecraft.core.RegistrationInfo.BUILT_IN);
        enchantments.freeze();
        var registries = net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.concat(
            vanilla.listRegistries().filter(registry -> !registry.key().equals(Registries.ENCHANTMENT)),
            java.util.stream.Stream.of(enchantments)));
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
            .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        var swiftSneak = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SWIFT_SNEAK);
        for (int level = 1; level <= 3; level++) {
            DragonRewardsMod.CONFIG.swiftSneakLevel = level;
            var book = RewardType.SWIFT_SNEAK.createStack(registries, DragonRewardsMod.CONFIG);
            var apple = RewardType.EGAP.createStack(registries, DragonRewardsMod.CONFIG);
            check(book.is(Items.ENCHANTED_BOOK), "Reward is a book");
            check(apple.is(Items.ENCHANTED_GOLDEN_APPLE) && apple.getCount() == 1, "Reward is one enchanted golden apple");
            UUID owner = UUID.randomUUID();
            var pending = new PendingRewardSpawn(owner, "Player", List.of(apple, book), 100);
            var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            var decodedPending = PendingRewardSpawn.CODEC.parse(ops,
                PendingRewardSpawn.CODEC.encodeStart(ops, pending).getOrThrow()).getOrThrow();
            var chest = new ActiveRewardChest(owner, "Player", BlockPos.ZERO, null, decodedPending.rewards(), 1000);
            var decodedChest = ActiveRewardChest.CODEC.parse(ops,
                ActiveRewardChest.CODEC.encodeStart(ops, chest).getOrThrow()).getOrThrow();
            var savedBook = decodedChest.rewards().get(1);
            check(savedBook.get(DataComponents.STORED_ENCHANTMENTS).getLevel(swiftSneak) == level,
                "Swift Sneak level survives delayed spawn and chest serialization");

            // Exercise the complete world-save payload, including duplicate-kill protection.
            var state = new RewardState();
            UUID dragon = UUID.randomUUID();
            state.setCurrentElytraChance(0.65);
            state.setCurrentDragonHeadChance(0.75);
            state.markDragonProcessed(dragon);
            state.addPendingSpawn(pending);
            state.addChest(chest);
            var savedState = RewardState.CODEC.parse(ops,
                RewardState.CODEC.encodeStart(ops, state).getOrThrow()).getOrThrow();
            equal(0.65, savedState.getCurrentElytraChance(), "Saved Elytra pity preserved");
            equal(0.75, savedState.getCurrentDragonHeadChance(), "Saved head pity preserved");
            check(savedState.isDragonProcessed(dragon), "Processed kill survives save");
            check(savedState.getPendingSpawns().getFirst().ownerUuid().equals(owner), "Pending owner preserved");
            check(savedState.getPendingSpawns().getFirst().executeAtTick() == 100, "Pending timer preserved");
            var savedChest = savedState.getChestAt(BlockPos.ZERO);
            check(savedChest.ownerUuid().equals(owner) && savedChest.expiresAtTick() == 1000,
                "Chest ownership and expiration preserved");
            check(savedChest.rewards().get(1).get(DataComponents.STORED_ENCHANTMENTS).getLevel(swiftSneak) == level,
                "Complete world save preserves enchanted rewards");
        }
    }

    private static void commands() throws Exception {
        DragonRewardsMod.CONFIG = new DragonRewardsConfig();
        DragonRewardsMod.CONFIG_PATH = Path.of("command-config.json").toAbsolutePath();
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        DragonRewardsCommands.register(dispatcher);
        var source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
            PermissionSet.ALL_PERMISSIONS, Component.literal("Tests"), null);
        for (String root : List.of("dr", "dragonrewards")) {
            check(dispatcher.execute(root, source) == 1, "Root displays help");
            for (String command : List.of("reward egap chance 30", "reward swift_sneak chance 5",
                "reward elytra chance 10", "reward dragon_head reset", "reward swift_sneak level 3",
                "chest time 30", "chest delay 8", "chest spawn Player all", "reload")) {
                var parsed = dispatcher.parse(root + " " + command, source);
                check(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty(), "Valid command: " + command);
            }
            for (String command : List.of("reward egap chance -1", "reward swift_sneak chance 101", "reward swift_sneak level 4",
                "chest time 0", "chest time 1441", "chest delay -1", "chest delay 601",
                "config save", "config reload", "admin simulate 100", "admin processed clear", "chest cleanup")) {
                check(dispatcher.parse(root + " " + command, source).getReader().canRead(), "Reject invalid command: " + command);
            }
            var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(root + " reward ", source)).get();
            check(suggestions.getList().stream().anyMatch(s -> s.getText().equals("egap")), "Egap tab completion");
            check(suggestions.getList().stream().anyMatch(s -> s.getText().equals("swift_sneak")), "Swift Sneak tab completion");
        }
        check(dispatcher.execute("dr reward egap enabled false", source) == 1, "Toggle command executes");
        check(!DragonRewardsConfig.load(DragonRewardsMod.CONFIG_PATH).enableEgapDrops, "Command automatically saves");
        check(dispatcher.execute("dr reward swift_sneak level 2", source) == 1, "Book level command executes");
        check(DragonRewardsConfig.load(DragonRewardsMod.CONFIG_PATH).swiftSneakLevel == 2, "Level command persists");
        check(dispatcher.execute("dr reward egap chance 42", source) == 1, "Egap chance command executes");
        check(dispatcher.execute("dragonrewards reward swift_sneak chance 12.5", source) == 1, "Swift Sneak chance command executes");
        check(dispatcher.execute("dr chest time 45", source) == 1, "Claim timer command executes");
        check(dispatcher.execute("dr chest delay 10", source) == 1, "Spawn delay command executes");
        var savedConfig = DragonRewardsConfig.load(DragonRewardsMod.CONFIG_PATH);
        check(savedConfig.rewardClaimTimeMinutes == 45, "Claim timer persists");
        check(savedConfig.rewardSpawnDelaySeconds == 10, "Spawn delay persists");
        equal(0.42, savedConfig.egapChance, "Egap command converts percent and persists");
        equal(0.125, savedConfig.swiftSneakChance, "Swift Sneak command converts percent and persists");
    }

    private static void equal(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 0.0000001, message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
