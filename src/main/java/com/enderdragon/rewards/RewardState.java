package com.enderdragon.rewards;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class RewardState extends SavedData {
    public static final String KEY = "dragonrewards_state";

    public static final Codec<RewardState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.DOUBLE.fieldOf("CurrentElytraChance").forGetter(state -> state.currentElytraChance),
        Codec.DOUBLE.fieldOf("CurrentDragonHeadChance").forGetter(state -> state.currentDragonHeadChance),
        ActiveRewardChest.CODEC.listOf().optionalFieldOf("ActiveChests", List.of()).forGetter(state -> new ArrayList<>(state.activeChests)),
        UUIDUtil.AUTHLIB_CODEC.listOf().optionalFieldOf("ProcessedDragonIds", List.of()).forGetter(state -> new ArrayList<>(state.processedDragonIds)),
        PendingRewardSpawn.CODEC.listOf().optionalFieldOf("PendingSpawns", List.of()).forGetter(state -> new ArrayList<>(state.pendingSpawns))
    ).apply(instance, RewardState::fromCodec));

    private static final SavedDataType<RewardState> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(DragonRewardsMod.MOD_ID, KEY),
        RewardState::new,
        CODEC,
        null
    );

    private double currentElytraChance;
    private double currentDragonHeadChance;

    private final List<ActiveRewardChest> activeChests = new ArrayList<>();
    private final Set<UUID> processedDragonIds = new LinkedHashSet<>();
    private final List<PendingRewardSpawn> pendingSpawns = new ArrayList<>();

    public RewardState() {
        this.currentElytraChance = DragonRewardsMod.CONFIG.elytraBaseChance;
        this.currentDragonHeadChance = DragonRewardsMod.CONFIG.dragonHeadBaseChance;
    }

    private static RewardState fromCodec(double currentElytraChance, double currentDragonHeadChance, List<ActiveRewardChest> activeChests, List<UUID> processedDragonIds, List<PendingRewardSpawn> pendingSpawns) {
        RewardState state = new RewardState();
        state.currentElytraChance = currentElytraChance;
        state.currentDragonHeadChance = currentDragonHeadChance;
        state.activeChests.addAll(activeChests);
        state.processedDragonIds.addAll(processedDragonIds);
        state.pendingSpawns.addAll(pendingSpawns);
        state.sanitize();
        return state;
    }

    public static RewardState get(MinecraftServer server) {
        SavedDataStorage manager = server.getLevel(Level.OVERWORLD).getDataStorage();
        return manager.computeIfAbsent(TYPE);
    }

    public double getCurrentElytraChance() {
        return currentElytraChance;
    }

    public void setCurrentElytraChance(double currentElytraChance) {
        this.currentElytraChance = currentElytraChance;
        sanitize();
        setDirty();
    }

    public double getCurrentDragonHeadChance() {
        return currentDragonHeadChance;
    }

    public void setCurrentDragonHeadChance(double currentDragonHeadChance) {
        this.currentDragonHeadChance = currentDragonHeadChance;
        sanitize();
        setDirty();
    }

    public List<ActiveRewardChest> getActiveChests() {
        return activeChests;
    }

    public void addChest(ActiveRewardChest chest) {
        this.activeChests.add(chest);
        setDirty();
    }

    public void updateChest(ActiveRewardChest updated) {
        for (int i = 0; i < activeChests.size(); i++) {
            if (activeChests.get(i).chestPos().equals(updated.chestPos())) {
                activeChests.set(i, updated);
                setDirty();
                return;
            }
        }
    }

    public ActiveRewardChest getChestAt(BlockPos pos) {
        for (ActiveRewardChest chest : activeChests) {
            if (chest.chestPos().equals(pos)) {
                return chest;
            }
        }
        return null;
    }

    public void removeChestAt(BlockPos pos) {
        activeChests.removeIf(chest -> chest.chestPos().equals(pos));
        setDirty();
    }

    public boolean isDragonProcessed(UUID dragonUuid) {
        return processedDragonIds.contains(dragonUuid);
    }

    public void markDragonProcessed(UUID dragonUuid) {
        processedDragonIds.add(dragonUuid);
        while (processedDragonIds.size() > 256) {
            UUID oldest = processedDragonIds.iterator().next();
            processedDragonIds.remove(oldest);
        }
        setDirty();
    }

    public int getProcessedDragonCount() {
        return processedDragonIds.size();
    }

    public void clearProcessedDragons() {
        processedDragonIds.clear();
        setDirty();
    }

    public List<PendingRewardSpawn> getPendingSpawns() {
        return pendingSpawns;
    }

    public void addPendingSpawn(PendingRewardSpawn pending) {
        pendingSpawns.add(pending);
        setDirty();
    }

    public void removePendingSpawn(PendingRewardSpawn pending) {
        pendingSpawns.remove(pending);
        setDirty();
    }

    public void sanitize() {
        currentElytraChance = clamp(currentElytraChance, DragonRewardsMod.CONFIG.elytraBaseChance, DragonRewardsMod.CONFIG.elytraMaxChance);
        currentDragonHeadChance = clamp(currentDragonHeadChance, DragonRewardsMod.CONFIG.dragonHeadBaseChance, DragonRewardsMod.CONFIG.dragonHeadMaxChance);
    }

    private static double clamp(double value, double min, double max) {
        if (max < min) {
            max = min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
