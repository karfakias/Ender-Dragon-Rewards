package com.enderdragon.rewards;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Uuids;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record PendingRewardSpawn(UUID ownerUuid, String ownerName, List<ItemStack> rewards, long executeAtTick) {
    public static final Codec<PendingRewardSpawn> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Uuids.CODEC.fieldOf("ownerUuid").forGetter(PendingRewardSpawn::ownerUuid),
        Codec.STRING.fieldOf("ownerName").forGetter(PendingRewardSpawn::ownerName),
        ItemStack.OPTIONAL_CODEC.listOf().fieldOf("rewards").forGetter(PendingRewardSpawn::rewards),
        Codec.LONG.fieldOf("executeAtTick").forGetter(PendingRewardSpawn::executeAtTick)
    ).apply(instance, (ownerUuid, ownerName, rewards, executeAtTick) ->
        new PendingRewardSpawn(ownerUuid, ownerName, new ArrayList<>(rewards), executeAtTick)
    ));
}
