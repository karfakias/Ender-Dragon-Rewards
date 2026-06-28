package com.enderdragon.rewards;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class DragonRewardsText {
    private DragonRewardsText() {
    }

    public static MutableComponent commandLine(String message) {
        return Component.empty()
            .append(Component.literal("◆ ").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD))
            .append(Component.literal("Dragon Rewards").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD))
            .append(Component.literal(" » ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(message).withStyle(ChatFormatting.GRAY));
    }

    public static MutableComponent unauthorized(String ownerName) {
        String raw = DragonRewardsMod.CONFIG.messages.unauthorizedChest.replace("{player}", ownerName);
        return Component.empty()
            .append(Component.literal("✖ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
            .append(Component.literal(raw).withStyle(ChatFormatting.RED));
    }

    public static MutableComponent outcome(String message, OutcomeType type) {
        return Component.empty()
            .append(Component.literal(type.symbol + " ").withStyle(type.prefixColor, ChatFormatting.BOLD))
            .append(Component.literal("Dragon Rewards").withStyle(type.prefixColor, ChatFormatting.BOLD))
            .append(Component.literal(" » ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(message).withStyle(type.messageColor));
    }

    public static Component chestTitle(String ownerName) {
        return Component.empty()
            .append(Component.literal("✦ ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD))
            .append(Component.literal(ownerName + "'s Rewards").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
    }

    public static Component chestTitleWithTimer(String ownerName, long remainingTicks) {
        return Component.empty()
            .append(Component.literal("✦ ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD))
            .append(Component.literal(ownerName + "'s Rewards").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
            .append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(formatRemaining(remainingTicks)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    }

    public static String formatRemaining(long remainingTicks) {
        long safe = Math.max(0L, remainingTicks);
        long totalSeconds = safe / 20L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    public enum OutcomeType {
        NOTHING("☘", ChatFormatting.GREEN, ChatFormatting.YELLOW),
        ELYTRA("✈", ChatFormatting.AQUA, ChatFormatting.AQUA),
        DRAGON_HEAD("☠", ChatFormatting.DARK_PURPLE, ChatFormatting.LIGHT_PURPLE),
        BOTH("✦", ChatFormatting.GOLD, ChatFormatting.GOLD);

        final String symbol;
        final ChatFormatting prefixColor;
        final ChatFormatting messageColor;

        OutcomeType(String symbol, ChatFormatting prefixColor, ChatFormatting messageColor) {
            this.symbol = symbol;
            this.prefixColor = prefixColor;
            this.messageColor = messageColor;
        }
    }
}
