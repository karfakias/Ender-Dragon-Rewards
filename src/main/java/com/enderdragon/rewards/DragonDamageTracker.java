package com.enderdragon.rewards;

import java.util.UUID;
import net.minecraft.world.entity.player.Player;

public interface DragonDamageTracker {
    void dragonrewards$setLastDamager(Player player);

    UUID dragonrewards$getLastDamagerUuid();

    String dragonrewards$getLastDamagerName();
}
