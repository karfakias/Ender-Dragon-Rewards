package com.enderdragon.rewards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
            if (world.isClientSide()) {
                return true;
            }
            if (isManagedRewardChest((ServerLevel) world, pos)) {
                ((ServerPlayer) player).sendSystemMessage(DragonRewardsText.commandLine("Reward chests cannot be broken."), false);
                return false;
            }
            return true;
        });

        UseBlockCallback.EVENT.register(DragonRewardManager::onUseBlock);

        ServerLifecycleEvents.SERVER_STARTED.register(DragonRewardManager::reconcilePersistentChests);
        ServerTickEvents.END_SERVER_TICK.register(DragonRewardManager::onServerTick);
    }

    private static void onEntityDeath(LivingEntity entity, DamageSource damageSource) {
        if (entity instanceof EnderDragon dragon) {
            onDragonDeath(dragon, damageSource);
        }
    }

    public static void onDragonDeath(EnderDragon dragon, DamageSource damageSource) {
        if (!(dragon.level() instanceof ServerLevel world) || world.dimension() != Level.END) {
            return;
        }

        RewardState state = RewardState.get(world.getServer());
        UUID dragonUuid = dragon.getUUID();
        if (state.isDragonProcessed(dragonUuid)) {
            debug(world.getServer(), "Skipped duplicate dragon death event for " + dragonUuid);
            return;
        }
        OwnerData owner = resolveOwnerData(dragon, damageSource, world);

        List<ItemStack> rewards = new ArrayList<>();
        List<RewardType> dropped = new ArrayList<>();
        java.util.Map<RewardType, Double> nextChances = new java.util.EnumMap<>(RewardType.class);
        for (RewardType reward : RewardType.values()) {
            double chance = reward.currentChance(DragonRewardsMod.CONFIG, state);
            boolean success = reward.rollsDrop(DragonRewardsMod.CONFIG, chance, world.getRandom().nextDouble());
            if (success) {
                rewards.add(reward.createStack(world.registryAccess(), DragonRewardsMod.CONFIG));
                dropped.add(reward);
            }
            nextChances.put(reward, reward.nextChance(DragonRewardsMod.CONFIG, chance, success));
        }

        if (!rewards.isEmpty()) {
            queueDelayedChestSpawn(world, state, owner, rewards);
        }

        // Commit only after owner resolution, item creation and queuing succeed. A failed attempt can retry.
        nextChances.forEach((reward, chance) -> reward.updateState(state, chance));
        state.markDragonProcessed(dragonUuid);
        DragonRewardsMod.LOGGER.info("Processed dragon {} for {}: {}. Pending reward chests: {}.",
            dragonUuid, owner.playerName(), dropped.isEmpty() ? "no drops" : dropped,
            state.getPendingSpawns().size());

        broadcastOutcome(world.getServer(), owner.playerName(), dropped);
    }

    private static void createRewardChest(ServerLevel world, RewardState state, BlockPos chestPos, OwnerData owner, List<ItemStack> rewards) {
        world.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        clearVanillaChestInventory(world, chestPos);

        long expiresAtTick = world.getGameTime() + (DragonRewardsMod.CONFIG.rewardClaimTimeMinutes * TICKS_PER_MINUTE);
        UUID markerUuid = spawnNameMarker(world, chestPos, owner.playerName(), expiresAtTick - world.getGameTime());
        List<ItemStack> rewardCopies = new ArrayList<>();
        for (ItemStack stack : rewards) {
            if (!stack.isEmpty()) {
                rewardCopies.add(stack.copy());
            }
        }
        state.addChest(new ActiveRewardChest(owner.playerUuid(), owner.playerName(), chestPos, markerUuid, rewardCopies, expiresAtTick));

        DragonRewardsMod.LOGGER.info("Spawned reward chest for {} at {} in The End.", owner.playerName(), chestPos.toShortString());
        long minutes = DragonRewardsMod.CONFIG.rewardClaimTimeMinutes;
        ownerNotifyChest(world, owner, chestPos, minutes);
    }

    private static void queueDelayedChestSpawn(ServerLevel world, RewardState state, OwnerData owner, List<ItemStack> rewards) {
        long delayTicks = DragonRewardsMod.CONFIG.rewardSpawnDelaySeconds * 20L;
        long executeAt = world.getServer().getLevel(Level.OVERWORLD).getGameTime() + delayTicks;
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : rewards) {
            if (!stack.isEmpty()) {
                copies.add(stack.copy());
            }
        }
        state.addPendingSpawn(new PendingRewardSpawn(owner.playerUuid(), owner.playerName(), copies, executeAt));
    }

    public static BlockPos spawnManualRewardChest(ServerLevel world, ServerPlayer owner, List<RewardType> selectedRewards) {
        if (selectedRewards.isEmpty()) {
            return null;
        }

        List<ItemStack> rewards = selectedRewards.stream()
            .map(reward -> reward.createStack(world.registryAccess(), DragonRewardsMod.CONFIG))
            .toList();
        RewardState state = RewardState.get(world.getServer());
        BlockPos chestPos = findChestSpawnPosition(world, state);
        OwnerData ownerData = new OwnerData(owner.getUUID(), owner.getName().getString());
        createRewardChest(world, state, chestPos, ownerData, rewards);
        return chestPos;
    }

    public static boolean removeManagedChest(ServerLevel world, BlockPos pos) {
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

    public static int removeAllManagedChests(ServerLevel world) {
        RewardState state = RewardState.get(world.getServer());
        List<ActiveRewardChest> snapshot = new ArrayList<>(state.getActiveChests());
        for (ActiveRewardChest chest : snapshot) {
            removeManagedChest(world, state, chest);
        }
        return snapshot.size();
    }

    public static void onRewardChestEmptied(ServerLevel world, BlockPos pos) {
        RewardState state = RewardState.get(world.getServer());
        ServerLevel resolvedRewardWorld = getRewardWorld(world.getServer());
        ServerLevel rewardWorld = resolvedRewardWorld == null ? world : resolvedRewardWorld;
        Optional<ActiveRewardChest> chest = state.getActiveChests().stream()
            .filter(active -> active.chestPos().equals(pos))
            .findFirst();

        chest.ifPresent(active -> {
            removeManagedChest(rewardWorld, state, active);
            debug(world.getServer(), "Removed emptied reward chest at " + pos.toShortString());
        });
    }

    public static void reconcilePersistentChests(MinecraftServer server) {
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) {
            return;
        }

        RewardState state = RewardState.get(server);
        processPendingSpawns(end, state);

        List<ActiveRewardChest> snapshot = new ArrayList<>(state.getActiveChests());
        for (ActiveRewardChest chest : snapshot) {
            BlockPos pos = chest.chestPos();
            BlockState blockState = end.getBlockState(pos);
            if (!blockState.is(Blocks.CHEST)) {
                removeMarker(end, chest.markerUuid(), pos);
                state.removeChestAt(pos);
                continue;
            }
            clearVanillaChestInventory(end, pos);

            Entity marker = chest.markerUuid() == null ? null : end.getEntity(chest.markerUuid());
            if (marker instanceof ArmorStand armorStand) {
                tagRewardMarker(armorStand);
            } else {
                UUID newMarker = spawnNameMarker(end, pos, chest.ownerName(), chest.expiresAtTick() - end.getGameTime());
                state.updateChest(chest.withMarker(newMarker));
            }
        }
        cleanupOrphanRewardMarkers(end, state);
    }

    private static OwnerData resolveOwnerData(EnderDragon dragon, DamageSource source, ServerLevel world) {
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
            Entity attacker = source == null ? null : source.getEntity();
            if (attacker instanceof ServerPlayer serverPlayer) {
                uuid = serverPlayer.getUUID();
                name = serverPlayer.getName().getString();
            }
        }

        if (uuid == null) {
            ServerPlayer fallback = world.players().stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(dragon), b.distanceToSqr(dragon)))
                .orElse(null);
            if (fallback != null) {
                uuid = fallback.getUUID();
                name = fallback.getName().getString();
            }
        }

        if (uuid == null) {
            uuid = new UUID(0L, 0L);
        }

        return new OwnerData(uuid, name);
    }

    private static void broadcastOutcome(MinecraftServer server, String playerName, List<RewardType> dropped) {
        String template = dropped.isEmpty()
            ? DragonRewardsMod.CONFIG.messages.nothingDropped
            : DragonRewardsMod.CONFIG.messages.rewardsDropped;
        DragonRewardsText.OutcomeType type;
        if (dropped.isEmpty()) {
            type = DragonRewardsText.OutcomeType.NOTHING;
        } else if (dropped.size() == 1 && dropped.contains(RewardType.ELYTRA)) {
            type = DragonRewardsText.OutcomeType.ELYTRA;
        } else if (dropped.size() == 1 && dropped.contains(RewardType.DRAGON_HEAD)) {
            type = DragonRewardsText.OutcomeType.DRAGON_HEAD;
        } else {
            type = DragonRewardsText.OutcomeType.BOTH;
        }

        String rewardNames = dropped.stream().map(RewardType::displayName).collect(java.util.stream.Collectors.joining(", "));
        String rendered = template.replace("{player}", playerName).replace("{rewards}", rewardNames);
        DiscordRewardEmbedSender.Result embedResult = DiscordRewardEmbedSender.send(playerName, dropped);
        if (embedResult == DiscordRewardEmbedSender.Result.UNAVAILABLE) {
            broadcastOutcomeThroughTellraw(server, rendered, type);
            return;
        }

        server.getPlayerList().broadcastSystemMessage(DragonRewardsText.outcome(rendered, type), false);
    }

    private static void broadcastOutcomeThroughTellraw(MinecraftServer server, String message, DragonRewardsText.OutcomeType type) {
        JsonObject root = new JsonObject();
        JsonArray extra = new JsonArray();
        extra.add(tellrawSegment(type.symbol + " ", type.prefixColor, true));
        extra.add(tellrawSegment("Dragon Rewards", type.prefixColor, true));
        extra.add(tellrawSegment(" \u00BB ", ChatFormatting.DARK_GRAY, false));
        extra.add(tellrawSegment(message, type.messageColor, false));
        root.addProperty("text", "");
        root.add("extra", extra);

        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "tellraw @a " + root);
        } catch (Exception ex) {
            DragonRewardsMod.LOGGER.warn("Failed to announce reward outcome through tellraw; falling back to direct broadcast.", ex);
            server.getPlayerList().broadcastSystemMessage(DragonRewardsText.outcome(message, type), false);
        }
    }

    private static JsonObject tellrawSegment(String text, ChatFormatting color, boolean bold) {
        JsonObject segment = new JsonObject();
        segment.addProperty("text", text);
        segment.addProperty("color", color.name().toLowerCase(Locale.ROOT));
        if (bold) {
            segment.addProperty("bold", true);
        }
        return segment;
    }

    private static BlockPos findChestSpawnPosition(ServerLevel world, RewardState state) {
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

        // If every valid spot is blocked, use the first free slot in the search area.
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

    private static boolean isValidChestSpot(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos).isAir()
            && world.getBlockState(pos.above()).isAir()
            && world.getBlockState(pos.below()).isRedstoneConductor(world, pos.below());
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hitResult) {
        if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(world instanceof ServerLevel serverWorld)) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hitResult.getBlockPos();
        RewardState state = RewardState.get(serverWorld.getServer());
        ActiveRewardChest chest = state.getChestAt(pos);
        if (chest == null) {
            return InteractionResult.PASS;
        }

        if (!player.getUUID().equals(chest.ownerUuid())) {
            ((ServerPlayer) player).sendSystemMessage(DragonRewardsText.unauthorized(chest.ownerName()), false);
            return InteractionResult.FAIL;
        }

        openRewardScreen(serverWorld, (ServerPlayer) player, chest);
        return InteractionResult.SUCCESS;
    }

    private static void openRewardScreen(ServerLevel world, ServerPlayer player, ActiveRewardChest chest) {
        SimpleContainer inventory = new SimpleContainer(27);
        int slot = 0;
        for (ItemStack stack : chest.rewards()) {
            if (slot >= inventory.getContainerSize()) {
                break;
            }
            inventory.setItem(slot++, stack.copy());
        }

        SimpleMenuProvider factory = new SimpleMenuProvider((syncId, playerInventory, opener) ->
            new ChestMenu(MenuType.GENERIC_9x3, syncId, playerInventory, inventory, 3) {
                @Override
                public void removed(Player closedBy) {
                    super.removed(closedBy);
                    updateRewardsFromInventory(world, chest.chestPos(), inventory);
                }

                @Override
                public boolean stillValid(Player user) {
                    return user.getUUID().equals(chest.ownerUuid());
                }
            },
            DragonRewardsText.chestTitleWithTimer(chest.ownerName(), chest.expiresAtTick() - world.getGameTime())
        );

        player.openMenu(factory);
    }

    private static void updateRewardsFromInventory(ServerLevel world, BlockPos chestPos, SimpleContainer inventory) {
        RewardState state = RewardState.get(world.getServer());
        ActiveRewardChest existing = state.getChestAt(chestPos);
        if (existing == null) {
            return;
        }

        List<ItemStack> remaining = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                remaining.add(stack.copy());
            }
        }

        if (remaining.isEmpty()) {
            ServerLevel rewardWorld = getRewardWorld(world.getServer());
            if (rewardWorld == null) {
                rewardWorld = world;
            }
            removeManagedChest(rewardWorld, state, existing);
        } else {
            state.updateChest(existing.withRewards(remaining));
            clearVanillaChestInventory(world, chestPos);
        }
    }

    private static boolean isManagedRewardChest(ServerLevel world, BlockPos pos) {
        RewardState state = RewardState.get(world.getServer());
        return state.getChestAt(pos) != null;
    }

    private static void clearVanillaChestInventory(ServerLevel world, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chestBlockEntity)) {
            return;
        }
        for (int i = 0; i < chestBlockEntity.getContainerSize(); i++) {
            chestBlockEntity.setItem(i, ItemStack.EMPTY);
        }
        chestBlockEntity.setChanged();
    }

    private static UUID spawnNameMarker(ServerLevel world, BlockPos chestPos, String ownerName, long remainingTicks) {
        ArmorStand marker = new ArmorStand(world, chestPos.getX() + 0.5, chestPos.getY() + 1.35, chestPos.getZ() + 0.5);

        marker.setInvisible(true);
        marker.setNoGravity(true);
        marker.setCustomNameVisible(true);
        marker.setCustomName(DragonRewardsText.chestTitleWithTimer(ownerName, remainingTicks));
        marker.setPermanentlyInvulnerable(true);
        tagRewardMarker(marker);

        world.addFreshEntity(marker);
        return marker.getUUID();
    }

    private static void onServerTick(MinecraftServer server) {
        if ((server.getTickCount() % 20) != 0) {
            return;
        }
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) {
            return;
        }

        RewardState state = RewardState.get(server);
        processPendingSpawns(end, state);

        List<ActiveRewardChest> snapshot = new ArrayList<>(state.getActiveChests());
        for (ActiveRewardChest chest : snapshot) {
            long remaining = chest.expiresAtTick() - end.getGameTime();
            if (remaining <= 0) {
                removeManagedChest(end, state, chest);
                continue;
            }

            if (!end.getBlockState(chest.chestPos()).is(Blocks.CHEST)) {
                end.setBlock(chest.chestPos(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
                clearVanillaChestInventory(end, chest.chestPos());
            }

            Entity marker = chest.markerUuid() == null ? null : end.getEntity(chest.markerUuid());
            if (marker instanceof ArmorStand armorStand) {
                tagRewardMarker(armorStand);
                armorStand.setCustomName(DragonRewardsText.chestTitleWithTimer(chest.ownerName(), remaining));
            } else {
                UUID newMarker = spawnNameMarker(end, chest.chestPos(), chest.ownerName(), remaining);
                state.updateChest(chest.withMarker(newMarker));
            }
        }
        if ((server.getTickCount() % (20 * 20)) == 0) {
            cleanupOrphanRewardMarkers(end, state);
        }
    }

    private static void processPendingSpawns(ServerLevel endWorld, RewardState state) {
        long now = endWorld.getServer().getLevel(Level.OVERWORLD).getGameTime();
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

    private static void ownerNotifyChest(ServerLevel world, OwnerData owner, BlockPos chestPos, long minutes) {
        ServerPlayer player = world.getServer().getPlayerList().getPlayer(owner.playerUuid());
        if (player == null) {
            return;
        }
        String message = "Your rewards chest spawned at X=" + chestPos.getX() + ", Y=" + chestPos.getY() + ", Z=" + chestPos.getZ()
            + ". You have " + minutes + " minutes to receive your rewards.";
        player.sendSystemMessage(DragonRewardsText.commandLine(message), false);
    }

    private static void removeManagedChest(ServerLevel world, RewardState state, ActiveRewardChest chest) {
        removeMarker(world, chest.markerUuid(), chest.chestPos());
        removeRewardBlock(world, chest.chestPos());
        state.removeChestAt(chest.chestPos());
    }

    private static void removeRewardBlock(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.is(Blocks.CHEST) || state.is(ModBlocks.REWARD_CHEST)) {
            world.removeBlock(pos, false);
        }
    }

    private static void removeMarker(ServerLevel world, UUID markerUuid, BlockPos chestPos) {
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

    private static void removeRewardMarkersNear(ServerLevel world, BlockPos chestPos) {
        AABB markerBox = new AABB(
            chestPos.getX() - 0.75D,
            chestPos.getY(),
            chestPos.getZ() - 0.75D,
            chestPos.getX() + 1.75D,
            chestPos.getY() + 3.25D,
            chestPos.getZ() + 1.75D
        );
        for (ArmorStand marker : world.getEntitiesOfClass(ArmorStand.class, markerBox, DragonRewardManager::isRewardMarker)) {
            marker.discard();
        }
    }

    private static void cleanupOrphanRewardMarkers(ServerLevel world, RewardState state) {
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
        AABB markerArea = new AABB(
            centerX - radius,
            fixedY,
            centerZ - radius,
            centerX + radius + 1,
            fixedY + 5,
            centerZ + radius + 1
        );

        for (ArmorStand marker : world.getEntitiesOfClass(ArmorStand.class, markerArea, DragonRewardManager::isRewardMarker)) {
            if (activeMarkerIds.contains(marker.getUUID())) {
                tagRewardMarker(marker);
            } else {
                marker.discard();
            }
        }
    }

    private static boolean isRewardMarker(ArmorStand marker) {
        if (marker.entityTags().contains(REWARD_MARKER_TAG)) {
            return true;
        }
        Component customName = marker.getCustomName();
        return customName != null && customName.getString().contains("'s Rewards");
    }

    private static void tagRewardMarker(ArmorStand marker) {
        marker.addTag(REWARD_MARKER_TAG);
    }

    private static ServerLevel getRewardWorld(MinecraftServer server) {
        return server.getLevel(Level.END);
    }

    private static void debug(MinecraftServer server, String message) {
        if (DragonRewardsMod.CONFIG.debugMode) {
            DragonRewardsMod.LOGGER.info("[debug] {}", message);
        }
    }

    private record OwnerData(UUID playerUuid, String playerName) {
    }
}
