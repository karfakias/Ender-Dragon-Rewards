package com.enderdragon.rewards;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class ModBlocks {
    private ModBlocks() {
    }

    // Keep this as a vanilla chest so players do not need a client-side mod.
    public static final Block REWARD_CHEST = Blocks.CHEST;

    public static void init() {
    }
}
