package com.enderdragon.rewards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class DragonRewardManager {
    private static final long TICKS_PER_MINUTE = 20L * 60L;
    private static final String REWARD_MARKER_TAG = "dragonrewards_reward_marker";

    private DragonRewardManager() {
    }

    public static void init() {
        ServerLivingEntityEvents.AFTER_DEATH.register(DragonRewardManager::onEntityDeath);

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient()) {
                return true;
            }
            if (isManagedRewardChest((ServerWorld) world, pos)) {
                player.sendMessage(DragonRewardsText.commandLine("Reward chests cannot be broken."), false);
                return false;
            }
            return true;
        });

        UseBlockCallback.EVENT.register(DragonRewardManager::onUseBlock);

        ServerLifecycleEvents.SERVER_STARTED.register(DragonRewardManager::reconcilePersistentChests);
        ServerTickEvents.END_SERVER_TICK.register(DragonRewardManager::onServerTick);
    }

    private static void onEntityDeath(LivingEntity entity, DamageSource damageSource) {
        if (!(entity instanceof EnderDragonEntity dragon)) {
            return;
        }
        if (!(entity.getEntityWorld() instanceof ServerWorld world) || world.getRegistryKey() != World.END) {
            return;
        }

        RewardState state = RewardState.get(world.getServer());
        UUID dragonUuid = dragon.getUuid();
        if (state.isDragonProcessed(dragonUuid)) {
            debug(world.getServer(), "Skipped duplicate dragon death event for " + dragonUuid);
            return;
        }
        state.markDragonProcessed(dragonUuid);

        OwnerData owner = resolveOwnerData(dragon, damageSource, world);

        boolean rolledElytra = false;
        boolean rolledHead = false;
        List<ItemStack> rewards = new ArrayList<>();

        if (DragonRewardsMod.CONFIG.enableElytraDrops) {
            rolledElytra = world.random.nextDouble() < state.getCurrentElytraChance();
            if (rolledElytra) {
                rewards.add(new ItemStack(Items.ELYTRA));
                state.setCurrentElytraChance(DragonRewardsMod.CONFIG.elytraBaseChance);
            } else {
                state.setCurrentElytraChance(Math.min(
                    DragonRewardsMod.CONFIG.elytraMaxChance,
                    state.getCurrentElytraChance() + DragonRewardsMod.CONFIG.elytraFailureIncrement
                ));
            }
        }

        if (DragonRewardsMod.CONFIG.enableDragonHeadDrops) {
            rolledHead = world.random.nextDouble() < state.getCurrentDragonHeadChance();
            if (rolledHead) {
                rewards.add(new ItemStack(Items.DRAGON_HEAD));
                state.setCurrentDragonHeadChance(DragonRewardsMod.CONFIG.dragonHeadBaseChance);
            } else {
                state.setCurrentDragonHeadChance(Math.min(
                    DragonRewardsMod.CONFIG.dragonHeadMaxChance,
                    state.getCurrentDragonHeadChance() + DragonRewardsMod.CONFIG.dragonHeadFailureIncrement
                ));
            }
        }

        if (!rewards.isEmpty()) {
            queueDelayedChestSpawn(world, state, owner, rewards);
        }

        broadcastOutcome(world.getServer(), owner.playerName(), rolledElytra, rolledHead);
    }

    private static void createRewardChest(ServerWorld world, RewardState state, BlockPos chestPos, OwnerData owner, List<ItemStack> rewards) {
        world.setBlockState(chestPos, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        clearVanillaChestInventory(world, chestPos);

        long expiresAtTick = world.getTime() + (DragonRewardsMod.CONFIG.rewardClaimTimeMinutes * TICKS_PER_MINUTE);
        UUID markerUuid = spawnNameMarker(world, chestPos, owner.playerName(), expiresAtTick - world.getTime());
        List<ItemStack> rewardCopies = new ArrayList<>();
        for (ItemStack stack : rewards) {
            if (!stack.isEmpty()) {
                rewardCopies.add(stack.copy());
            }
        }
        state.addChest(new ActiveRewardChest(owner.playerUuid(), owner.playerName(), chestPos, markerUuid, rewardCopies, expiresAtTick));

        debug(world.getServer(), "Spawned reward chest for " + owner.playerName() + " at " + chestPos.toShortString());
        long minutes = DragonRewardsMod.CONFIG.rewardClaimTimeMinutes;
        ownerNotifyChest(world, owner, chestPos, minutes);
    }

    private static void queueDelayedChestSpawn(ServerWorld world, RewardState state, OwnerData owner, List<ItemStack> rewards) {
        long delayTicks = DragonRewardsMod.CONFIG.rewardSpawnDelaySeconds * 20L;
        long executeAt = world.getServer().getWorld(World.OVERWORLD).getTime() + delayTicks;
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : rewards) {
            if (!stack.isEmpty()) {
                copies.add(stack.copy());
            }
        }
        state.addPendingSpawn(new PendingRewardSpawn(owner.playerUuid(), owner.playerName(), copies, executeAt));
    }

    public static BlockPos spawnManualRewardChest(ServerWorld world, ServerPlayerEntity owner, boolean includeElytra, boolean includeDragonHead) {
        if (!includeElytra && !includeDragonHead) {
            return null;
        }

        RewardState state = RewardState.get(world.getServer());
        BlockPos chestPos = findChestSpawnPosition(world, state);
        List<ItemStack> rewards = new ArrayList<>();
        if (includeElytra) {
            rewards.add(new ItemStack(Items.ELYTRA));
        }
        if (includeDragonHead) {
            rewards.add(new ItemStack(Items.DRAGON_HEAD));
        }

        OwnerData ownerData = new OwnerData(owner.getUuid(), owner.getName().getString());
        createRewardChest(world, state, chestPos, ownerData, rewards);
        return chestPos;
    }

    public static boolean removeManagedChest(ServerWorld world, BlockPos pos) {
        RewardState state = RewardState.get(world.getServer());
        Optional<ActiveRewardChest> chest = state.getActiveChests().stream()
            .filter(active -> active.chestPos().equals(pos))
            .findFirst();

        if (chest.isEmpty()) {
            return false;
        }

        removeManagedChest(world, state, chest.get());
        return true;
    }

    public static int removeAllManagedChests(ServerWorld world) {
        RewardState state = RewardState.get(world.getServer());
        List<ActiveRewardChest> snapshot = new ArrayList<>(state.getActiveChests());
        for (ActiveRewardChest chest : snapshot) {
            removeManagedChest(world, state, chest);
        }
        return snapshot.size();
    }

    public static void onRewardChestEmptied(ServerWorld world, BlockPos pos) {
        RewardState state = RewardState.get(world.getServer());
        ServerWorld resolvedRewardWorld = getRewardWorld(world.getServer());
        ServerWorld rewardWorld = resolvedRewardWorld == null ? world : resolvedRewardWorld;
        Optional<ActiveRewardChest> chest = state.getActiveChests().stream()
            .filter(active -> active.chestPos().equals(pos))
            .findFirst();

        chest.ifPresent(active -> {
            removeManagedChest(rewardWorld, state, active);
            debug(world.getServer(), "Removed emptied reward chest at " + pos.toShortString());
        });
    }

    public static void reconcilePersistentChests(MinecraftServer server) {
        ServerWorld end = server.getWorld(World.END);
        if (end == null) {
            return;
        }

        RewardState state = RewardState.get(server);
        processPendingSpawns(end, state);

        List<ActiveRewardChest> snapshot = new ArrayList<>(state.getActiveChests());
        for (ActiveRewardChest chest : snapshot) {
            BlockPos pos = chest.chestPos();
            BlockState blockState = end.getBlockState(pos);
            if (!blockState.isOf(Blocks.CHEST)) {
                removeMarker(end, chest.markerUuid(), pos);
                state.removeChestAt(pos);
                continue;
            }
            clearVanillaChestInventory(end, pos);

            Entity marker = chest.markerUuid() == null ? null : end.getEntity(chest.markerUuid());
            if (marker instanceof ArmorStandEntity armorStand) {
                tagRewardMarker(armorStand);
            } else {
                UUID newMarker = spawnNameMarker(end, pos, chest.ownerName(), chest.expiresAtTick() - end.getTime());
                state.updateChest(chest.withMarker(newMarker));
            }
        }
        cleanupOrphanRewardMarkers(end, state);
    }

    private static OwnerData resolveOwnerData(EnderDragonEntity dragon, DamageSource source, ServerWorld world) {
        UUID uuid = null;
        String name = "Unknown";

        if (dragon instanceof DragonDamageTracker tracker) {
            uuid = tracker.dragonrewards$getLastDamagerUuid();
            String trackedName = tracker.dragonrewards$getLastDamagerName();
            if (trackedName != null && !trackedName.isBlank()) {
                name = trackedName;
            }
        }

        if (uuid == null) {
            Entity attacker = source.getAttacker();
            if (attacker instanceof ServerPlayerEntity serverPlayer) {
                uuid = serverPlayer.getUuid();
                name = serverPlayer.getName().getString();
            }
        }

        if (uuid == null) {
            ServerPlayerEntity fallback = world.getPlayers().stream()
                .min((a, b) -> Double.compare(a.squaredDistanceTo(dragon), b.squaredDistanceTo(dragon)))
                .orElse(null);
            if (fallback != null) {
                uuid = fallback.getUuid();
                name = fallback.getName().getString();
            }
        }

        if (uuid == null) {
            uuid = new UUID(0L, 0L);
        }

        return new OwnerData(uuid, name);
    }

    private static void broadcastOutcome(MinecraftServer server, String playerName, boolean elytra, boolean dragonHead) {
        String template;
        DragonRewardsText.OutcomeType type;
        if (elytra && dragonHead) {
            template = DragonRewardsMod.CONFIG.messages.bothDropped;
            type = DragonRewardsText.OutcomeType.BOTH;
        } else if (elytra) {
            template = DragonRewardsMod.CONFIG.messages.onlyElytra;
            type = DragonRewardsText.OutcomeType.ELYTRA;
        } else if (dragonHead) {
            template = DragonRewardsMod.CONFIG.messages.onlyDragonHead;
            type = DragonRewardsText.OutcomeType.DRAGON_HEAD;
        } else {
            template = DragonRewardsMod.CONFIG.messages.nothingDropped;
            type = DragonRewardsText.OutcomeType.NOTHING;
        }

        String rendered = template.replace("{player}", playerName);
        broadcastOutcomeThroughTellraw(server, rendered, type);
    }

    private static void broadcastOutcomeThroughTellraw(MinecraftServer server, String message, DragonRewardsText.OutcomeType type) {
        JsonObject root = new JsonObject();
        JsonArray extra = new JsonArray();
        extra.add(tellrawSegment(type.symbol + " ", type.prefixColor, true));
        extra.add(tellrawSegment("Dragon Rewards", type.prefixColor, true));
        extra.add(tellrawSegment(" \u00BB ", Formatting.DARK_GRAY, false));
        extra.add(tellrawSegment(message, type.messageColor, false));
        root.addProperty("text", "");
        root.add("extra", extra);

        try {
            server.getCommandManager().parseAndExecute(server.getCommandSource().withSilent(), "tellraw @a " + root);
        } catch (Exception ex) {
            DragonRewardsMod.LOGGER.warn("Failed to announce reward outcome through tellraw; falling back to direct broadcast.", ex);
            server.getPlayerManager().broadcast(DragonRewardsText.outcome(message, type), false);
        }
    }

    private static JsonObject tellrawSegment(String text, Formatting color, boolean bold) {
        JsonObject segment = new JsonObject();
        segment.addProperty("text", text);
        segment.addProperty("color", color.getName());
        if (bold) {
            segment.addProperty("bold", true);
        }
        return segment;
    }

    private static BlockPos findChestSpawnPosition(ServerWorld world, RewardState state) {
        int centerX = DragonRewardsMod.CONFIG.chestSpawnCenterX;
        int centerZ = DragonRewardsMod.CONFIG.chestSpawnCenterZ;
        int fixedY = DragonRewardsMod.CONFIG.chestSpawnFixedY;
        Set<BlockPos> occupied = new HashSet<>();
        for (ActiveRewardChest active : state.getActiveChests()) {
            occupied.add(active.chestPos());
        }

        int radius = Math.max(1, DragonRewardsMod.CONFIG.chestSpawnRadius);
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (ring != 0 && Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }

                    BlockPos candidate = new BlockPos(centerX + dx, fixedY, centerZ + dz);
                    if (!occupied.contains(candidate) && isValidChestSpot(world, candidate)) {
                        return candidate;
                    }
                }
            }
        }

        // Guaranteed fallback within configured area, force-place at fixed Y.
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (ring != 0 && Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos fallback = new BlockPos(centerX + dx, fixedY, centerZ + dz);
                    if (!occupied.contains(fallback)) {
                        return fallback;
                    }
                }
            }
        }
        return new BlockPos(centerX, fixedY, centerZ);
    }

    private static boolean isValidChestSpot(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isAir()
            && world.getBlockState(pos.up()).isAir()
            && world.getBlockState(pos.down()).isSolidBlock(world, pos.down());
    }

    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        if (world.isClient() || hand != Hand.MAIN_HAND || !(world instanceof ServerWorld serverWorld)) {
            return ActionResult.PASS;
        }

        BlockPos pos = hitResult.getBlockPos();
        RewardState state = RewardState.get(serverWorld.getServer());
        ActiveRewardChest chest = state.getChestAt(pos);
        if (chest == null) {
            return ActionResult.PASS;
        }

        if (!player.getUuid().equals(chest.ownerUuid())) {
            player.sendMessage(DragonRewardsText.unauthorized(chest.ownerName()), false);
            return ActionResult.FAIL;
        }

        openRewardScreen(serverWorld, (ServerPlayerEntity) player, chest);
        return ActionResult.SUCCESS;
    }

    private static void openRewardScreen(ServerWorld world, ServerPlayerEntity player, ActiveRewardChest chest) {
        SimpleInventory inventory = new SimpleInventory(27);
        int slot = 0;
        for (ItemStack stack : chest.rewards()) {
            if (slot >= inventory.size()) {
                break;
            }
            inventory.setStack(slot++, stack.copy());
        }

        SimpleNamedScreenHandlerFactory factory = new SimpleNamedScreenHandlerFactory((syncId, playerInventory, opener) ->
            new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, inventory, 3) {
                @Override
                public void onClosed(PlayerEntity closedBy) {
                    super.onClosed(closedBy);
                    updateRewardsFromInventory(world, chest.chestPos(), inventory);
                }

                @Override
                public boolean canUse(PlayerEntity user) {
                    return user.getUuid().equals(chest.ownerUuid());
                }
            },
            DragonRewardsText.chestTitleWithTimer(chest.ownerName(), chest.expiresAtTick() - world.getTime())
        );

        player.openHandledScreen(factory);
    }

    private static void updateRewardsFromInventory(ServerWorld world, BlockPos chestPos, SimpleInventory inventory) {
        RewardState state = RewardState.get(world.getServer());
        ActiveRewardChest existing = state.getChestAt(chestPos);
        if (existing == null) {
            return;
        }

        List<ItemStack> remaining = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                remaining.add(stack.copy());
            }
        }

        if (remaining.isEmpty()) {
            ServerWorld rewardWorld = getRewardWorld(world.getServer());
            if (rewardWorld == null) {
                rewardWorld = world;
            }
            removeManagedChest(rewardWorld, state, existing);
        } else {
            state.updateChest(existing.withRewards(remaining));
            clearVanillaChestInventory(world, chestPos);
        }
    }

    private static boolean isManagedRewardChest(ServerWorld world, BlockPos pos) {
        RewardState state = RewardState.get(world.getServer());
        return state.getChestAt(pos) != null;
    }

    private static void clearVanillaChestInventory(ServerWorld world, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof net.minecraft.block.entity.ChestBlockEntity chestBlockEntity)) {
            return;
        }
        for (int i = 0; i < chestBlockEntity.size(); i++) {
            chestBlockEntity.setStack(i, ItemStack.EMPTY);
        }
        chestBlockEntity.markDirty();
    }

    private static UUID spawnNameMarker(ServerWorld world, BlockPos chestPos, String ownerName, long remainingTicks) {
        ArmorStandEntity marker = EntityType.ARMOR_STAND.create(world, SpawnReason.EVENT);
        if (marker == null) {
            return null;
        }

        marker.setPosition(chestPos.getX() + 0.5, chestPos.getY() + 1.35, chestPos.getZ() + 0.5);
        marker.setInvisible(true);
        marker.setNoGravity(true);
        marker.setCustomNameVisible(true);
        marker.setCustomName(DragonRewardsText.chestTitleWithTimer(ownerName, remainingTicks));
        marker.setInvulnerable(true);
        tagRewardMarker(marker);

        world.spawnEntity(marker);
        return marker.getUuid();
    }

    private static void onServerTick(MinecraftServer server) {
        if ((server.getTicks() % 20) != 0) {
            return;
        }
        ServerWorld end = server.getWorld(World.END);
        if (end == null) {
            return;
        }

        RewardState state = RewardState.get(server);
        processPendingSpawns(end, state);

        List<ActiveRewardChest> snapshot = new ArrayList<>(state.getActiveChests());
        for (ActiveRewardChest chest : snapshot) {
            long remaining = chest.expiresAtTick() - end.getTime();
            if (remaining <= 0) {
                removeManagedChest(end, state, chest);
                continue;
            }

            if (!end.getBlockState(chest.chestPos()).isOf(Blocks.CHEST)) {
                end.setBlockState(chest.chestPos(), Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
                clearVanillaChestInventory(end, chest.chestPos());
            }

            Entity marker = chest.markerUuid() == null ? null : end.getEntity(chest.markerUuid());
            if (marker instanceof ArmorStandEntity armorStand) {
                tagRewardMarker(armorStand);
                armorStand.setCustomName(DragonRewardsText.chestTitleWithTimer(chest.ownerName(), remaining));
            } else {
                UUID newMarker = spawnNameMarker(end, chest.chestPos(), chest.ownerName(), remaining);
                state.updateChest(chest.withMarker(newMarker));
            }
        }
        if ((server.getTicks() % (20 * 20)) == 0) {
            cleanupOrphanRewardMarkers(end, state);
        }
    }

    private static void processPendingSpawns(ServerWorld endWorld, RewardState state) {
        long now = endWorld.getServer().getWorld(World.OVERWORLD).getTime();
        List<PendingRewardSpawn> snapshot = new ArrayList<>(state.getPendingSpawns());
        for (PendingRewardSpawn pending : snapshot) {
            if (pending.executeAtTick() > now) {
                continue;
            }

            BlockPos chestPos = findChestSpawnPosition(endWorld, state);
            OwnerData owner = new OwnerData(pending.ownerUuid(), pending.ownerName());
            createRewardChest(endWorld, state, chestPos, owner, pending.rewards());
            state.removePendingSpawn(pending);
        }
    }

    private static void ownerNotifyChest(ServerWorld world, OwnerData owner, BlockPos chestPos, long minutes) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(owner.playerUuid());
        if (player == null) {
            return;
        }
        String message = "Your rewards chest spawned at X=" + chestPos.getX() + ", Y=" + chestPos.getY() + ", Z=" + chestPos.getZ()
            + ". You have " + minutes + " minutes to receive your rewards.";
        player.sendMessage(DragonRewardsText.commandLine(message), false);
    }

    private static void removeManagedChest(ServerWorld world, RewardState state, ActiveRewardChest chest) {
        removeMarker(world, chest.markerUuid(), chest.chestPos());
        removeRewardBlock(world, chest.chestPos());
        state.removeChestAt(chest.chestPos());
    }

    private static void removeRewardBlock(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isOf(Blocks.CHEST) || state.isOf(ModBlocks.REWARD_CHEST)) {
            world.removeBlock(pos, false);
        }
    }

    private static void removeMarker(ServerWorld world, UUID markerUuid, BlockPos chestPos) {
        if (markerUuid != null) {
            Entity entity = world.getEntity(markerUuid);
            if (entity != null) {
                entity.discard();
                return;
            }
        }

        if (chestPos != null) {
            removeRewardMarkersNear(world, chestPos);
        }
    }

    private static void removeRewardMarkersNear(ServerWorld world, BlockPos chestPos) {
        Box markerBox = new Box(
            chestPos.getX() - 0.75D,
            chestPos.getY(),
            chestPos.getZ() - 0.75D,
            chestPos.getX() + 1.75D,
            chestPos.getY() + 3.25D,
            chestPos.getZ() + 1.75D
        );
        for (ArmorStandEntity marker : world.getEntitiesByClass(ArmorStandEntity.class, markerBox, DragonRewardManager::isRewardMarker)) {
            marker.discard();
        }
    }

    private static void cleanupOrphanRewardMarkers(ServerWorld world, RewardState state) {
        Set<UUID> activeMarkerIds = new HashSet<>();
        for (ActiveRewardChest chest : state.getActiveChests()) {
            if (chest.markerUuid() != null) {
                activeMarkerIds.add(chest.markerUuid());
            }
        }

        int radius = Math.max(1, DragonRewardsMod.CONFIG.chestSpawnRadius) + 3;
        int centerX = DragonRewardsMod.CONFIG.chestSpawnCenterX;
        int centerZ = DragonRewardsMod.CONFIG.chestSpawnCenterZ;
        int fixedY = DragonRewardsMod.CONFIG.chestSpawnFixedY;
        Box markerArea = new Box(
            centerX - radius,
            fixedY,
            centerZ - radius,
            centerX + radius + 1,
            fixedY + 5,
            centerZ + radius + 1
        );

        for (ArmorStandEntity marker : world.getEntitiesByClass(ArmorStandEntity.class, markerArea, DragonRewardManager::isRewardMarker)) {
            if (activeMarkerIds.contains(marker.getUuid())) {
                tagRewardMarker(marker);
            } else {
                marker.discard();
            }
        }
    }

    private static boolean isRewardMarker(ArmorStandEntity marker) {
        if (marker.getCommandTags().contains(REWARD_MARKER_TAG)) {
            return true;
        }
        Text customName = marker.getCustomName();
        return customName != null && customName.getString().contains("'s Rewards");
    }

    private static void tagRewardMarker(ArmorStandEntity marker) {
        marker.addCommandTag(REWARD_MARKER_TAG);
    }

    private static ServerWorld getRewardWorld(MinecraftServer server) {
        return server.getWorld(World.END);
    }

    private static void debug(MinecraftServer server, String message) {
        if (DragonRewardsMod.CONFIG.debugMode) {
            DragonRewardsMod.LOGGER.info("[debug] {}", message);
        }
    }

    private record OwnerData(UUID playerUuid, String playerName) {
    }
}
