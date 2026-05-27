package com.enderdragon.rewards.block.entity;

import com.enderdragon.rewards.DragonRewardManager;
import com.enderdragon.rewards.DragonRewardsMod;
import com.enderdragon.rewards.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.UUID;

public class RewardChestBlockEntity extends BlockEntity implements Inventory, SidedInventory, net.minecraft.screen.NamedScreenHandlerFactory {
    private static final int[] NO_SLOTS = new int[0];
    private final DefaultedList<ItemStack> inventory = DefaultedList.ofSize(27, ItemStack.EMPTY);

    private UUID ownerUuid;
    private String ownerName = "Unknown";

    public RewardChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REWARD_CHEST, pos, state);
    }

    public void setOwner(UUID ownerUuid, String ownerName) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        markDirty();
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public boolean canOpen(PlayerEntity player) {
        return ownerUuid != null && ownerUuid.equals(player.getUuid());
    }

    public Text getUnauthorizedText() {
        String raw = DragonRewardsMod.CONFIG.messages.unauthorizedChest.replace("{player}", ownerName);
        return Text.literal(raw);
    }

    @Override
    public Text getDisplayName() {
        return Text.literal(ownerName + "'s Rewards");
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        if (!canOpen(player)) {
            return null;
        }
        return GenericContainerScreenHandler.createGeneric9x3(syncId, playerInventory, this);
    }

    @Override
    public int size() {
        return inventory.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getStack(int slot) {
        return inventory.get(slot);
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack stack = Inventories.splitStack(inventory, slot, amount);
        if (!stack.isEmpty()) {
            afterInventoryChanged();
        }
        return stack;
    }

    @Override
    public ItemStack removeStack(int slot) {
        ItemStack stack = Inventories.removeStack(inventory, slot);
        afterInventoryChanged();
        return stack;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        inventory.set(slot, stack);
        if (stack.getCount() > getMaxCountPerStack()) {
            stack.setCount(getMaxCountPerStack());
        }
        afterInventoryChanged();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return canOpen(player);
    }

    @Override
    public void clear() {
        inventory.clear();
        afterInventoryChanged();
    }

    @Override
    public int[] getAvailableSlots(Direction side) {
        return NO_SLOTS;
    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, Direction dir) {
        return false;
    }

    @Override
    public boolean canExtract(int slot, ItemStack stack, Direction dir) {
        return false;
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        Inventories.writeData(view, inventory);
        if (ownerUuid != null) {
            view.put("OwnerUuid", Uuids.CODEC, ownerUuid);
        }
        view.putString("OwnerName", ownerName);
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        Inventories.readData(view, inventory);
        ownerUuid = view.read("OwnerUuid", Uuids.CODEC).orElse(null);
        ownerName = view.getString("OwnerName", "Unknown");
    }

    private void afterInventoryChanged() {
        markDirty();
        if (world instanceof ServerWorld serverWorld && isEmpty()) {
            DragonRewardManager.onRewardChestEmptied(serverWorld, pos);
        }
    }
}
