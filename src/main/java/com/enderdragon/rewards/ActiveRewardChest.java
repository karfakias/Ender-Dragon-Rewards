package com.enderdragon.rewards;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

public record ActiveRewardChest(UUID ownerUuid, String ownerName, BlockPos chestPos, UUID markerUuid, List<ItemStack> rewards, long expiresAtTick) {
    public static final Codec<ActiveRewardChest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        UUIDUtil.AUTHLIB_CODEC.fieldOf("ownerUuid").forGetter(ActiveRewardChest::ownerUuid),
        Codec.STRING.fieldOf("ownerName").forGetter(ActiveRewardChest::ownerName),
        BlockPos.CODEC.fieldOf("chestPos").forGetter(ActiveRewardChest::chestPos),
        UUIDUtil.AUTHLIB_CODEC.optionalFieldOf("markerUuid").forGetter(chest -> Optional.ofNullable(chest.markerUuid())),
        ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("rewards", List.of()).forGetter(ActiveRewardChest::rewards),
        Codec.LONG.optionalFieldOf("expiresAtTick", 0L).forGetter(ActiveRewardChest::expiresAtTick)
    ).apply(instance, (ownerUuid, ownerName, chestPos, markerUuid, rewards, expiresAtTick) -> new ActiveRewardChest(ownerUuid, ownerName, chestPos, markerUuid.orElse(null), new ArrayList<>(rewards), expiresAtTick)));

    public ActiveRewardChest withMarker(UUID newMarker) {
        return new ActiveRewardChest(ownerUuid, ownerName, chestPos, newMarker, new ArrayList<>(rewards), expiresAtTick);
    }

    public ActiveRewardChest withRewards(List<ItemStack> newRewards) {
        return new ActiveRewardChest(ownerUuid, ownerName, chestPos, markerUuid, new ArrayList<>(newRewards), expiresAtTick);
    }
}
