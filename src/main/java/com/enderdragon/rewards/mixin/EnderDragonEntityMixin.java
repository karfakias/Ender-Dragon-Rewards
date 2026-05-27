package com.enderdragon.rewards.mixin;

import com.enderdragon.rewards.DragonDamageTracker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(EnderDragonEntity.class)
public class EnderDragonEntityMixin implements DragonDamageTracker {
    @Unique
    private UUID dragonrewards$lastDamagerUuid;

    @Unique
    private String dragonrewards$lastDamagerName = "Unknown";

    @Inject(method = "damage", at = @At("HEAD"))
    private void dragonrewards$captureLastDamager(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (amount <= 0.0f) {
            return;
        }

        Entity attacker = source.getAttacker();
        if (attacker instanceof PlayerEntity player) {
            dragonrewards$setLastDamager(player);
            return;
        }

        Entity sourceEntity = source.getSource();
        if (sourceEntity instanceof PlayerEntity player) {
            dragonrewards$setLastDamager(player);
        }
    }

    @Override
    public void dragonrewards$setLastDamager(PlayerEntity player) {
        this.dragonrewards$lastDamagerUuid = player.getUuid();
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
