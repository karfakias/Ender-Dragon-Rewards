package com.enderdragon.rewards;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.Locale;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class DragonRewardsCommands {
    private DragonRewardsCommands() {
    }

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = dispatcher.register(literal("dragonrewards")
            .requires(DragonRewardsCommands::hasAdminPermission)
            .executes(DragonRewardsCommands::help)
            .then(literal("help").executes(DragonRewardsCommands::help))
            .then(literal("status").executes(DragonRewardsCommands::status))
            .then(rewardCommands())
            .then(literal("reload").executes(DragonRewardsCommands::reloadConfig))
            .then(chestCommands()));
        dispatcher.register(literal("dr").requires(DragonRewardsCommands::hasAdminPermission)
            .executes(DragonRewardsCommands::help).redirect(root));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rewardCommands() {
        var rewards = literal("reward").executes(DragonRewardsCommands::rewardHelp);
        for (RewardType reward : RewardType.values()) {
            var entry = literal(reward.id())
                .executes(ctx -> rewardStatus(ctx, reward))
                .then(literal("enabled").then(argument("enabled", BoolArgumentType.bool())
                    .executes(ctx -> setFeature(ctx, reward))))
                .then(literal("chance").then(argument("percent", DoubleArgumentType.doubleArg(0, 100))
                    .executes(ctx -> setChance(ctx, reward))));
            if (!reward.isFixed()) {
                entry.then(literal("reset").executes(ctx -> resetChance(ctx, reward)));
            }
            if (reward == RewardType.SWIFT_SNEAK) {
                entry.then(literal("level").then(argument("level", IntegerArgumentType.integer(1, 3))
                    .executes(DragonRewardsCommands::setSwiftSneakLevel)));
            }
            rewards.then(entry);
        }
        return rewards;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> chestCommands() {
        var player = argument("player", EntityArgument.player());
        for (RewardType reward : RewardType.values()) {
            player.then(literal(reward.id()).executes(ctx -> spawnChest(ctx, List.of(reward))));
        }
        player.then(literal("all").executes(ctx -> spawnChest(ctx, List.of(RewardType.values()))));
        return literal("chest")
            .executes(DragonRewardsCommands::chestHelp)
            .then(literal("list").executes(DragonRewardsCommands::listChests))
            .then(literal("clear").executes(DragonRewardsCommands::clearChests))
            .then(literal("remove")
                .then(argument("x", IntegerArgumentType.integer())
                    .then(argument("y", IntegerArgumentType.integer())
                        .then(argument("z", IntegerArgumentType.integer()).executes(DragonRewardsCommands::removeChestAt)))))
            .then(literal("spawn").then(player))
            .then(literal("time").then(argument("minutes", IntegerArgumentType.integer(1, 1440)).executes(DragonRewardsCommands::setChestTime)))
            .then(literal("delay").then(argument("seconds", IntegerArgumentType.integer(0, 600)).executes(DragonRewardsCommands::setChestDelay)));
    }

    private static boolean hasAdminPermission(CommandSourceStack source) {
        if (!source.isPlayer()) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return false;
        }
        return source.getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()));
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        send(ctx.getSource(), "Dragon Rewards | /dr or /dragonrewards");
        send(ctx.getSource(), "/dr status - reward chances and chest settings");
        send(ctx.getSource(), "/dr reward - configure rewards (percentages 0-100)");
        send(ctx.getSource(), "/dr reload - reload the config file");
        send(ctx.getSource(), "/dr chest - manage reward chests and timers");
        return 1;
    }

    private static int rewardHelp(CommandContext<CommandSourceStack> ctx) {
        send(ctx.getSource(), "Rewards: elytra, dragon_head, egap, swift_sneak");
        send(ctx.getSource(), "/dr reward <reward> [chance <0-100> | enabled <true|false>]");
        send(ctx.getSource(), "/dr reward <elytra|dragon_head> reset - reset pity to the configured base");
        send(ctx.getSource(), "/dr reward swift_sneak level <1-3>");
        send(ctx.getSource(), "Example: /dr reward egap chance 30. Changes save automatically.");
        return 1;
    }

    private static int chestHelp(CommandContext<CommandSourceStack> ctx) {
        send(ctx.getSource(), "/dr chest <list|clear>");
        send(ctx.getSource(), "/dr chest remove <x> <y> <z> - coordinates in The End");
        send(ctx.getSource(), "/dr chest spawn <player> <elytra|dragon_head|egap|swift_sneak|all>");
        send(ctx.getSource(), "/dr chest time <minutes> - claim time");
        send(ctx.getSource(), "/dr chest delay <seconds> - spawn delay");
        return 1;
    }

    private static int rewardStatus(CommandContext<CommandSourceStack> ctx, RewardType reward) {
        var config = DragonRewardsMod.CONFIG;
        RewardState state = RewardState.get(ctx.getSource().getServer());
        String details = reward.isFixed() ? " (fixed)" : " (base " + pct(reward.baseChance(config))
            + ", +" + pct(reward == RewardType.ELYTRA ? config.elytraFailureIncrement : config.dragonHeadFailureIncrement)
            + " per miss, cap " + pct(reward == RewardType.ELYTRA ? config.elytraMaxChance : config.dragonHeadMaxChance) + ")";
        if (reward == RewardType.SWIFT_SNEAK) {
            details += ", book level " + config.swiftSneakLevel;
        }
        send(ctx.getSource(), reward.displayName() + ": " + (reward.isEnabled(config) ? "ON" : "OFF")
            + " | " + pct(reward.currentChance(config, state)) + details);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        for (RewardType reward : RewardType.values()) {
            rewardStatus(ctx, reward);
        }
        RewardState state = RewardState.get(ctx.getSource().getServer());
        send(ctx.getSource(), "Chests: " + state.getActiveChests().size() + " active, " + state.getPendingSpawns().size()
            + " pending | claim " + DragonRewardsMod.CONFIG.rewardClaimTimeMinutes + " min | delay "
            + DragonRewardsMod.CONFIG.rewardSpawnDelaySeconds + " sec | debug " + DragonRewardsMod.CONFIG.debugMode);
        return 1;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        DragonRewardsMod.reloadConfig();
        RewardState.get(ctx.getSource().getServer()).sanitize();
        send(ctx.getSource(), "Reloaded config from disk.");
        return 1;
    }

    private static int setFeature(CommandContext<CommandSourceStack> ctx, RewardType reward) {
        boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
        reward.setEnabled(DragonRewardsMod.CONFIG, enabled);
        return saveChange(ctx.getSource(), reward.displayName() + " drops " + (enabled ? "enabled." : "disabled."));
    }

    private static int setChance(CommandContext<CommandSourceStack> ctx, RewardType reward) {
        double chance = DoubleArgumentType.getDouble(ctx, "percent") / 100.0;
        if (!Double.isFinite(chance)) {
            ctx.getSource().sendFailure(Component.literal("Chance must be a finite percentage from 0 to 100."));
            return 0;
        }
        reward.setChance(DragonRewardsMod.CONFIG, chance);
        if (!reward.isFixed()) {
            reward.updateState(RewardState.get(ctx.getSource().getServer()), chance);
        }
        return saveChange(ctx.getSource(), reward.displayName() + (reward.isFixed() ? " fixed chance" : " base and current chance")
            + " set to " + pct(chance) + ".");
    }

    private static int resetChance(CommandContext<CommandSourceStack> ctx, RewardType reward) {
        reward.updateState(RewardState.get(ctx.getSource().getServer()), reward.baseChance(DragonRewardsMod.CONFIG));
        send(ctx.getSource(), reward.displayName() + " current chance reset to " + pct(reward.baseChance(DragonRewardsMod.CONFIG)) + ".");
        return 1;
    }

    private static int setSwiftSneakLevel(CommandContext<CommandSourceStack> ctx) {
        DragonRewardsMod.CONFIG.swiftSneakLevel = IntegerArgumentType.getInteger(ctx, "level");
        return saveChange(ctx.getSource(), "Swift Sneak book level set to " + DragonRewardsMod.CONFIG.swiftSneakLevel + ".");
    }

    private static int listChests(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        RewardState state = RewardState.get(src.getServer());
        List<ActiveRewardChest> chests = state.getActiveChests();
        send(src, "Active reward chests: " + chests.size());
        for (int i = 0; i < chests.size(); i++) {
            ActiveRewardChest chest = chests.get(i);
            send(src, " [" + i + "] owner=" + chest.ownerName() + " pos=" + chest.chestPos().toShortString());
        }
        return 1;
    }

    private static int clearChests(CommandContext<CommandSourceStack> ctx) {
        ServerLevel end = getEndWorld(ctx.getSource());
        if (end == null) {
            send(ctx.getSource(), "The End is not loaded.");
            return 0;
        }
        int removed = DragonRewardManager.removeAllManagedChests(end);
        send(ctx.getSource(), "Removed " + removed + " managed reward chest(s).");
        return 1;
    }

    private static int setChestTime(CommandContext<CommandSourceStack> ctx) {
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
        DragonRewardsMod.CONFIG.rewardClaimTimeMinutes = minutes;
        return saveChange(ctx.getSource(), "Reward claim timer set to " + minutes + " minute(s) for new chests.");
    }

    private static int setChestDelay(CommandContext<CommandSourceStack> ctx) {
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        DragonRewardsMod.CONFIG.rewardSpawnDelaySeconds = seconds;
        return saveChange(ctx.getSource(), "Reward chest spawn delay set to " + seconds + " second(s) for future kills.");
    }

    private static int removeChestAt(CommandContext<CommandSourceStack> ctx) {
        ServerLevel end = getEndWorld(ctx.getSource());
        if (end == null) {
            send(ctx.getSource(), "The End is not loaded.");
            return 0;
        }

        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        BlockPos pos = new BlockPos(x, y, z);
        boolean removed = DragonRewardManager.removeManagedChest(end, pos);
        send(ctx.getSource(), removed ? "Removed managed chest at " + pos.toShortString() : "No managed chest found at " + pos.toShortString());
        return removed ? 1 : 0;
    }

    private static int spawnChest(CommandContext<CommandSourceStack> ctx, List<RewardType> rewards) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel end = getEndWorld(ctx.getSource());
        if (end == null) {
            send(ctx.getSource(), "The End is not loaded.");
            return 0;
        }

        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        BlockPos pos = DragonRewardManager.spawnManualRewardChest(end, player, rewards);
        send(ctx.getSource(), "Spawned manual reward chest for " + player.getName().getString() + " at " + pos.toShortString());
        return 1;
    }

    private static int saveChange(CommandSourceStack source, String message) {
        if (!DragonRewardsMod.saveConfig()) {
            source.sendFailure(Component.literal(message + " Applied in memory, but could not save config. Check logs, then repeat this command to retry saving."));
            return 0;
        }
        send(source, message + " Saved.");
        return 1;
    }

    private static ServerLevel getEndWorld(CommandSourceStack source) {
        return source.getServer().getLevel(Level.END);
    }

    private static String pct(double chance) {
        return String.format(Locale.ROOT, "%.2f%%", chance * 100.0);
    }

    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> DragonRewardsText.commandLine(message), false);
    }
}
