package com.enderdragon.rewards.block;

import com.enderdragon.rewards.block.entity.RewardChestBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class RewardChestBlock extends BlockWithEntity {
    public static final MapCodec<RewardChestBlock> CODEC = createCodec(RewardChestBlock::new);

    public RewardChestBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof RewardChestBlockEntity rewardChest)) {
            return ActionResult.PASS;
        }

        if (!rewardChest.canOpen(player)) {
            Text text = rewardChest.getUnauthorizedText();
            if (text != null) {
                player.sendMessage(text, false);
            }
            return ActionResult.CONSUME;
        }

        NamedScreenHandlerFactory factory = rewardChest;
        player.openHandledScreen(factory);
        return ActionResult.CONSUME;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new RewardChestBlockEntity(pos, state);
    }
}
