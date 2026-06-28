package com.enderdragon.rewards.mixin;

import com.enderdragon.rewards.DragonDamageTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;

@Mixin(EnderDragon.class)
public class EnderDragonEntityMixin implements DragonDamageTracker {
    @Unique
    private UUID dragonrewards$lastDamagerUuid;

    @Unique
    private String dragonrewards$lastDamagerName = "Unknown";

    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void dragonrewards$captureLastDamager(ServerLevel world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (amount <= 0.0f) {
            return;
        }

        Entity attacker = source.getEntity();
        if (attacker instanceof Player player) {
            dragonrewards$setLastDamager(player);
            return;
        }

        Entity sourceEntity = source.getDirectEntity();
        if (sourceEntity instanceof Player player) {
            dragonrewards$setLastDamager(player);
        }
    }

    @Override
    public void dragonrewards$setLastDamager(Player player) {
        this.dragonrewards$lastDamagerUuid = player.getUUID();
        this.dragonrewards$lastDamagerName = player.getName().getString();
    }

    @Override
    public UUID dragonrewards$getLastDamagerUuid() {
        return dragonrewards$lastDamagerUuid;
    }

    @Override
    public String dragonrewards$getLastDamagerName() {
        return dragonrewards$lastDamagerName;
    }
}
