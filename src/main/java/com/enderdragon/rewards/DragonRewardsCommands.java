package com.enderdragon.rewards;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class DragonRewardsCommands {
    private DragonRewardsCommands() {
    }

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            literal("dragonrewards")
                .then(literal("help").executes(DragonRewardsCommands::help))
                .then(literal("status").executes(DragonRewardsCommands::status))
                .then(literal("reload").executes(DragonRewardsCommands::reloadConfig))
                .then(literal("save").executes(DragonRewardsCommands::saveConfig))
                .then(literal("debug")
                    .then(argument("enabled", BoolArgumentType.bool())
                        .executes(DragonRewardsCommands::setDebug)))
                .then(literal("feature")
                    .then(literal("elytra")
                        .then(argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> setFeature(ctx, true))))
                    .then(literal("dragon_head")
                        .then(argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> setFeature(ctx, false)))))
                .then(literal("chance")
                    .then(literal("get").executes(DragonRewardsCommands::chanceGet))
                    .then(literal("reset")
                        .executes(ctx -> chanceReset(ctx, true, true))
                        .then(literal("elytra").executes(ctx -> chanceReset(ctx, true, false)))
                        .then(literal("dragon_head").executes(ctx -> chanceReset(ctx, false, true))))
                    .then(literal("set")
                        .then(literal("elytra")
                            .then(argument("value", DoubleArgumentType.doubleArg(0.0, 1.0))
                                .executes(ctx -> chanceSet(ctx, true))))
                        .then(literal("dragon_head")
                            .then(argument("value", DoubleArgumentType.doubleArg(0.0, 1.0))
                                .executes(ctx -> chanceSet(ctx, false)))))
                    .then(literal("add")
                        .then(literal("elytra")
                            .then(argument("delta", DoubleArgumentType.doubleArg(-1.0, 1.0))
                                .executes(ctx -> chanceAdd(ctx, true))))
                        .then(literal("dragon_head")
                            .then(argument("delta", DoubleArgumentType.doubleArg(-1.0, 1.0))
                                .executes(ctx -> chanceAdd(ctx, false)))))
                    .then(literal("simulate")
                        .then(argument("kills", IntegerArgumentType.integer(1, 10000))
                            .executes(DragonRewardsCommands::simulate))))
                .then(literal("chests")
                    .then(literal("list").executes(DragonRewardsCommands::listChests))
                    .then(literal("cleanup").executes(DragonRewardsCommands::cleanupChests))
                    .then(literal("clear").executes(DragonRewardsCommands::clearChests))
                    .then(literal("time")
                        .then(argument("minutes", IntegerArgumentType.integer(1, 1440))
                            .executes(DragonRewardsCommands::setChestTime)))
                    .then(literal("delay")
                        .then(argument("seconds", IntegerArgumentType.integer(0, 600))
                            .executes(DragonRewardsCommands::setChestDelay)))
                    .then(literal("remove")
                        .then(argument("x", IntegerArgumentType.integer())
                            .then(argument("y", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer())
                                    .executes(DragonRewardsCommands::removeChestAt)))))
                    .then(literal("spawn")
                        .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                            .then(argument("elytra", BoolArgumentType.bool())
                                .then(argument("dragon_head", BoolArgumentType.bool())
                                    .executes(DragonRewardsCommands::spawnChest))))))
                .then(literal("processed")
                    .then(literal("count").executes(DragonRewardsCommands::processedCount))
                    .then(literal("clear").executes(DragonRewardsCommands::clearProcessed))))
        );
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

    private static boolean ensureAdmin(CommandSourceStack source) {
        if (hasAdminPermission(source)) {
            return true;
        }
        source.sendFailure(Component.literal("You must be an operator to use /dragonrewards."));
        return false;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!ensureAdmin(src)) {
            return 0;
        }
        send(src, "/dragonrewards status");
        send(src, "/dragonrewards reload");
        send(src, "/dragonrewards save");
        send(src, "/dragonrewards debug <true|false>");
        send(src, "/dragonrewards feature elytra <true|false>");
        send(src, "/dragonrewards feature dragon_head <true|false>");
        send(src, "/dragonrewards chance get");
        send(src, "/dragonrewards chance reset [elytra|dragon_head]");
        send(src, "/dragonrewards chance set <elytra|dragon_head> <0..1>");
        send(src, "/dragonrewards chance add <elytra|dragon_head> <-1..1>");
        send(src, "/dragonrewards chance simulate <kills>");
        send(src, "/dragonrewards chests list");
        send(src, "/dragonrewards chests cleanup");
        send(src, "/dragonrewards chests clear");
        send(src, "/dragonrewards chests time <minutes>");
        send(src, "/dragonrewards chests delay <seconds>");
        send(src, "/dragonrewards chests remove <x> <y> <z>");
        send(src, "/dragonrewards chests spawn <player> <elytra:true|false> <dragon_head:true|false>");
        send(src, "/dragonrewards processed count");
        send(src, "/dragonrewards processed clear");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!ensureAdmin(src)) {
            return 0;
        }
        RewardState state = RewardState.get(src.getServer());
        send(src, "DragonRewards status:");
        send(src, " Elytra: enabled=" + DragonRewardsMod.CONFIG.enableElytraDrops + ", current=" + pct(state.getCurrentElytraChance()) + ", base=" + pct(DragonRewardsMod.CONFIG.elytraBaseChance) + ", inc=" + pct(DragonRewardsMod.CONFIG.elytraFailureIncrement) + ", cap=" + pct(DragonRewardsMod.CONFIG.elytraMaxChance));
        send(src, " Dragon Head: enabled=" + DragonRewardsMod.CONFIG.enableDragonHeadDrops + ", current=" + pct(state.getCurrentDragonHeadChance()) + ", base=" + pct(DragonRewardsMod.CONFIG.dragonHeadBaseChance) + ", inc=" + pct(DragonRewardsMod.CONFIG.dragonHeadFailureIncrement) + ", cap=" + pct(DragonRewardsMod.CONFIG.dragonHeadMaxChance));
        send(src, " Chests: active=" + state.getActiveChests().size() + ", processedDragonIds=" + state.getProcessedDragonCount() + ", debugMode=" + DragonRewardsMod.CONFIG.debugMode);
        return 1;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        DragonRewardsMod.reloadConfig();
        RewardState.get(ctx.getSource().getServer()).sanitize();
        send(ctx.getSource(), "Reloaded config from disk.");
        return 1;
    }

    private static int saveConfig(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        boolean ok = DragonRewardsMod.saveConfig();
        send(ctx.getSource(), ok ? "Saved config." : "Failed to save config. Check logs.");
        return ok ? 1 : 0;
    }

    private static int setDebug(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
        DragonRewardsMod.CONFIG.debugMode = enabled;
        send(ctx.getSource(), "Debug mode set to " + enabled + ". Use /dragonrewards save to persist.");
        return 1;
    }

    private static int setFeature(CommandContext<CommandSourceStack> ctx, boolean elytra) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
        if (elytra) {
            DragonRewardsMod.CONFIG.enableElytraDrops = enabled;
        } else {
            DragonRewardsMod.CONFIG.enableDragonHeadDrops = enabled;
        }
        send(ctx.getSource(), (elytra ? "Elytra" : "Dragon Head") + " drops set to " + enabled + ". Use /dragonrewards save to persist.");
        return 1;
    }

    private static int chanceGet(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        return status(ctx);
    }

    private static int chanceReset(CommandContext<CommandSourceStack> ctx, boolean resetElytra, boolean resetHead) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        RewardState state = RewardState.get(ctx.getSource().getServer());
        if (resetElytra) {
            state.setCurrentElytraChance(DragonRewardsMod.CONFIG.elytraBaseChance);
        }
        if (resetHead) {
            state.setCurrentDragonHeadChance(DragonRewardsMod.CONFIG.dragonHeadBaseChance);
        }
        send(ctx.getSource(), "Reset current chance(s) to base values.");
        return 1;
    }

    private static int chanceSet(CommandContext<CommandSourceStack> ctx, boolean elytra) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        double value = DoubleArgumentType.getDouble(ctx, "value");
        RewardState state = RewardState.get(ctx.getSource().getServer());
        if (elytra) {
            state.setCurrentElytraChance(value);
            send(ctx.getSource(), "Elytra current chance set to " + pct(state.getCurrentElytraChance()) + ".");
        } else {
            state.setCurrentDragonHeadChance(value);
            send(ctx.getSource(), "Dragon Head current chance set to " + pct(state.getCurrentDragonHeadChance()) + ".");
        }
        return 1;
    }

    private static int chanceAdd(CommandContext<CommandSourceStack> ctx, boolean elytra) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        double delta = DoubleArgumentType.getDouble(ctx, "delta");
        RewardState state = RewardState.get(ctx.getSource().getServer());
        if (elytra) {
            state.setCurrentElytraChance(state.getCurrentElytraChance() + delta);
            send(ctx.getSource(), "Elytra current chance is now " + pct(state.getCurrentElytraChance()) + ".");
        } else {
            state.setCurrentDragonHeadChance(state.getCurrentDragonHeadChance() + delta);
            send(ctx.getSource(), "Dragon Head current chance is now " + pct(state.getCurrentDragonHeadChance()) + ".");
        }
        return 1;
    }

    private static int simulate(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        int kills = IntegerArgumentType.getInteger(ctx, "kills");

        double elytraChance = RewardState.get(ctx.getSource().getServer()).getCurrentElytraChance();
        double headChance = RewardState.get(ctx.getSource().getServer()).getCurrentDragonHeadChance();
        int elytraDrops = 0;
        int headDrops = 0;

        for (int i = 0; i < kills; i++) {
            if (DragonRewardsMod.CONFIG.enableElytraDrops) {
                if (ThreadLocalRandom.current().nextDouble() < elytraChance) {
                    elytraDrops++;
                    elytraChance = DragonRewardsMod.CONFIG.elytraBaseChance;
                } else {
                    elytraChance = Math.min(DragonRewardsMod.CONFIG.elytraMaxChance, elytraChance + DragonRewardsMod.CONFIG.elytraFailureIncrement);
                }
            }

            if (DragonRewardsMod.CONFIG.enableDragonHeadDrops) {
                if (ThreadLocalRandom.current().nextDouble() < headChance) {
                    headDrops++;
                    headChance = DragonRewardsMod.CONFIG.dragonHeadBaseChance;
                } else {
                    headChance = Math.min(DragonRewardsMod.CONFIG.dragonHeadMaxChance, headChance + DragonRewardsMod.CONFIG.dragonHeadFailureIncrement);
                }
            }
        }

        send(ctx.getSource(), "Simulation over " + kills + " kills (state unchanged):");
        send(ctx.getSource(), " Elytra drops=" + elytraDrops + ", final simulated chance=" + pct(elytraChance));
        send(ctx.getSource(), " Dragon Head drops=" + headDrops + ", final simulated chance=" + pct(headChance));
        return 1;
    }

    private static int listChests(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!ensureAdmin(src)) {
            return 0;
        }
        RewardState state = RewardState.get(src.getServer());
        List<ActiveRewardChest> chests = state.getActiveChests();
        send(src, "Active reward chests: " + chests.size());
        for (int i = 0; i < chests.size(); i++) {
            ActiveRewardChest chest = chests.get(i);
            send(src, " [" + i + "] owner=" + chest.ownerName() + " pos=" + chest.chestPos().toShortString());
        }
        return 1;
    }

    private static int cleanupChests(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        DragonRewardManager.reconcilePersistentChests(ctx.getSource().getServer());
        send(ctx.getSource(), "Reconciled persistent reward chests with world state.");
        return 1;
    }

    private static int clearChests(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
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
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
        DragonRewardsMod.CONFIG.rewardClaimTimeMinutes = minutes;
        send(ctx.getSource(), "Reward claim timer set to " + minutes + " minute(s). Use /dragonrewards save to persist.");
        return 1;
    }

    private static int setChestDelay(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        DragonRewardsMod.CONFIG.rewardSpawnDelaySeconds = seconds;
        send(ctx.getSource(), "Reward chest spawn delay set to " + seconds + " second(s). Use /dragonrewards save to persist.");
        return 1;
    }

    private static int removeChestAt(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
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

    private static int spawnChest(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        ServerLevel end = getEndWorld(ctx.getSource());
        if (end == null) {
            send(ctx.getSource(), "The End is not loaded.");
            return 0;
        }

        ServerPlayer player = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player");
        boolean elytra = BoolArgumentType.getBool(ctx, "elytra");
        boolean head = BoolArgumentType.getBool(ctx, "dragon_head");

        BlockPos pos = DragonRewardManager.spawnManualRewardChest(end, player, elytra, head);
        if (pos == null) {
            send(ctx.getSource(), "Nothing spawned. At least one reward must be true.");
            return 0;
        }

        send(ctx.getSource(), "Spawned manual reward chest for " + player.getName().getString() + " at " + pos.toShortString());
        return 1;
    }

    private static int processedCount(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        RewardState state = RewardState.get(ctx.getSource().getServer());
        send(ctx.getSource(), "Processed dragon IDs tracked: " + state.getProcessedDragonCount());
        return 1;
    }

    private static int clearProcessed(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAdmin(ctx.getSource())) {
            return 0;
        }
        RewardState state = RewardState.get(ctx.getSource().getServer());
        state.clearProcessedDragons();
        send(ctx.getSource(), "Cleared processed dragon ID cache.");
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
