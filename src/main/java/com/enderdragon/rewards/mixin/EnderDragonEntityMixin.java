package com.enderdragon.rewards.mixin;

import com.enderdragon.rewards.DragonDamageTracker;
import com.enderdragon.rewards.DragonRewardManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    @Unique
    private boolean dragonrewards$deathChecked;

    // Hits on dragon parts bypass EnderDragon.hurtServer but all accepted damage reaches reallyHurt.
    @Inject(method = "reallyHurt", at = @At("HEAD"))
    private void dragonrewards$captureLastDamager(ServerLevel world, DamageSource source, float amount, CallbackInfo ci) {
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

    // Keep a dragon-specific fallback for death paths that bypass Fabric's generic death callback.
    @Inject(method = "tickDeath", at = @At("HEAD"))
    private void dragonrewards$checkDeathRewards(CallbackInfo ci) {
        EnderDragon dragon = (EnderDragon) (Object) this;
        if (!dragonrewards$deathChecked && dragon.level() instanceof ServerLevel) {
            DragonRewardManager.onDragonDeath(dragon, dragon.getLastDamageSource());
            dragonrewards$deathChecked = true;
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
