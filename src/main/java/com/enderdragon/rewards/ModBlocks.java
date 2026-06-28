package com.enderdragon.rewards;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class ModBlocks {
    private ModBlocks() {
    }

    // Server-only mode uses a vanilla chest to avoid any client-side content requirement.
    public static final Block REWARD_CHEST = Blocks.CHEST;

    public static void init() {
        // No-op.
    }
}
