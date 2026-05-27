package com.enderdragon.rewards;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class DragonRewardsText {
    private DragonRewardsText() {
    }

    public static MutableText commandLine(String message) {
        return Text.empty()
            .append(Text.literal("◆ ").formatted(Formatting.DARK_PURPLE, Formatting.BOLD))
            .append(Text.literal("Dragon Rewards").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
            .append(Text.literal(" » ").formatted(Formatting.DARK_GRAY))
            .append(Text.literal(message).formatted(Formatting.GRAY));
    }

    public static MutableText unauthorized(String ownerName) {
        String raw = DragonRewardsMod.CONFIG.messages.unauthorizedChest.replace("{player}", ownerName);
        return Text.empty()
            .append(Text.literal("✖ ").formatted(Formatting.RED, Formatting.BOLD))
            .append(Text.literal(raw).formatted(Formatting.RED));
    }

    public static MutableText outcome(String message, OutcomeType type) {
        return Text.empty()
            .append(Text.literal(type.symbol + " ").formatted(type.prefixColor, Formatting.BOLD))
            .append(Text.literal("Dragon Rewards").formatted(type.prefixColor, Formatting.BOLD))
            .append(Text.literal(" » ").formatted(Formatting.DARK_GRAY))
            .append(Text.literal(message).formatted(type.messageColor));
    }

    public static Text chestTitle(String ownerName) {
        return Text.empty()
            .append(Text.literal("✦ ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
            .append(Text.literal(ownerName + "'s Rewards").formatted(Formatting.AQUA, Formatting.BOLD));
    }

    public static Text chestTitleWithTimer(String ownerName, long remainingTicks) {
        return Text.empty()
            .append(Text.literal("✦ ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
            .append(Text.literal(ownerName + "'s Rewards").formatted(Formatting.AQUA, Formatting.BOLD))
            .append(Text.literal(" • ").formatted(Formatting.DARK_GRAY))
            .append(Text.literal(formatRemaining(remainingTicks)).formatted(Formatting.GOLD, Formatting.BOLD));
    }

    public static String formatRemaining(long remainingTicks) {
        long safe = Math.max(0L, remainingTicks);
        long totalSeconds = safe / 20L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    public enum OutcomeType {
        NOTHING("☘", Formatting.GREEN, Formatting.YELLOW),
        ELYTRA("✈", Formatting.AQUA, Formatting.AQUA),
        DRAGON_HEAD("☠", Formatting.DARK_PURPLE, Formatting.LIGHT_PURPLE),
        BOTH("✦", Formatting.GOLD, Formatting.GOLD);

        final String symbol;
        final Formatting prefixColor;
        final Formatting messageColor;

        OutcomeType(String symbol, Formatting prefixColor, Formatting messageColor) {
            this.symbol = symbol;
            this.prefixColor = prefixColor;
            this.messageColor = messageColor;
        }
    }
}
