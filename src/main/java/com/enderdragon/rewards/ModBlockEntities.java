package com.enderdragon.rewards;

import com.enderdragon.rewards.block.entity.RewardChestBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlockEntities {
    public static BlockEntityType<RewardChestBlockEntity> REWARD_CHEST;

    public static void init() {
        REWARD_CHEST = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of(DragonRewardsMod.MOD_ID, "reward_chest"),
            FabricBlockEntityTypeBuilder.create(RewardChestBlockEntity::new, ModBlocks.REWARD_CHEST).build()
        );
    }
}
