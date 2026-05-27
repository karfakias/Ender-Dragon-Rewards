package com.enderdragon.rewards;

import net.minecraft.entity.player.PlayerEntity;

import java.util.UUID;

public interface DragonDamageTracker {
    void dragonrewards$setLastDamager(PlayerEntity player);

    UUID dragonrewards$getLastDamagerUuid();

    String dragonrewards$getLastDamagerName();
}
