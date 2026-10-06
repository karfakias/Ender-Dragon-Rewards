package com.enderdragon.rewards;

import com.enderdragon.rewards.config.DragonRewardsConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

public enum RewardType {
    ELYTRA("elytra", "Elytra", false),
    DRAGON_HEAD("dragon_head", "Dragon Head", false),
    EGAP("egap", "Enchanted Golden Apple", true),
    SWIFT_SNEAK("swift_sneak", "Swift Sneak", true);

    private final String id;
    private final String displayName;
    private final boolean fixed;

    RewardType(String id, String displayName, boolean fixed) {
        this.id = id;
        this.displayName = displayName;
        this.fixed = fixed;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public boolean isFixed() { return fixed; }

    public boolean isEnabled(DragonRewardsConfig config) {
        return switch (this) {
            case ELYTRA -> config.enableElytraDrops;
            case DRAGON_HEAD -> config.enableDragonHeadDrops;
            case EGAP -> config.enableEgapDrops;
            case SWIFT_SNEAK -> config.enableSwiftSneakDrops;
        };
    }

    public void setEnabled(DragonRewardsConfig config, boolean enabled) {
        switch (this) {
            case ELYTRA -> config.enableElytraDrops = enabled;
            case DRAGON_HEAD -> config.enableDragonHeadDrops = enabled;
            case EGAP -> config.enableEgapDrops = enabled;
            case SWIFT_SNEAK -> config.enableSwiftSneakDrops = enabled;
        }
    }

    public double baseChance(DragonRewardsConfig config) {
        return switch (this) {
            case ELYTRA -> config.elytraBaseChance;
            case DRAGON_HEAD -> config.dragonHeadBaseChance;
            case EGAP -> config.egapChance;
            case SWIFT_SNEAK -> config.swiftSneakChance;
        };
    }

    public double currentChance(DragonRewardsConfig config, RewardState state) {
        return switch (this) {
            case ELYTRA -> state.getCurrentElytraChance();
            case DRAGON_HEAD -> state.getCurrentDragonHeadChance();
            default -> baseChance(config);
        };
    }

    public void setChance(DragonRewardsConfig config, double chance) {
        switch (this) {
            case ELYTRA -> {
                config.elytraBaseChance = chance;
                config.elytraMaxChance = Math.max(config.elytraMaxChance, chance);
            }
            case DRAGON_HEAD -> {
                config.dragonHeadBaseChance = chance;
                config.dragonHeadMaxChance = Math.max(config.dragonHeadMaxChance, chance);
            }
            case EGAP -> config.egapChance = chance;
            case SWIFT_SNEAK -> config.swiftSneakChance = chance;
        }
    }

    public void updateState(RewardState state, double chance) {
        switch (this) {
            case ELYTRA -> state.setCurrentElytraChance(chance);
            case DRAGON_HEAD -> state.setCurrentDragonHeadChance(chance);
            default -> { } // Fixed rewards never accumulate pity or persist a separate chance.
        }
    }

    public boolean rollsDrop(DragonRewardsConfig config, double chance, double roll) {
        return isEnabled(config) && roll < chance;
    }

    public double nextChance(DragonRewardsConfig config, double current, boolean dropped) {
        if (!isEnabled(config)) {
            return current;
        }
        if (fixed || dropped) {
            return baseChance(config);
        }
        return switch (this) {
            case ELYTRA -> Math.min(config.elytraMaxChance, current + config.elytraFailureIncrement);
            case DRAGON_HEAD -> Math.min(config.dragonHeadMaxChance, current + config.dragonHeadFailureIncrement);
            default -> baseChance(config);
        };
    }

    public ItemStack createStack(HolderLookup.Provider registries, DragonRewardsConfig config) {
        return switch (this) {
            case ELYTRA -> new ItemStack(Items.ELYTRA);
            case DRAGON_HEAD -> new ItemStack(Items.DRAGON_HEAD);
            case EGAP -> new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);
            case SWIFT_SNEAK -> {
                ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
                var enchantment = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SWIFT_SNEAK);
                EnchantmentHelper.updateEnchantments(book, enchantments -> enchantments.set(enchantment, config.swiftSneakLevel));
                yield book;
            }
        };
    }
}
