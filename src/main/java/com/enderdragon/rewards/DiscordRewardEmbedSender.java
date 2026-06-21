package com.enderdragon.rewards;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public final class DiscordRewardEmbedSender {
    private static final String DMCC_LEGACY_MAIN = "com.xujiayao.discord_mc_chat.Main";
    private static final String DMCC_V3_CONFIG_MANAGER = "com.xujiayao.discord_mc_chat.config.ConfigManager";
    private static final String DMCC_V3_DISCORD_MANAGER = "com.xujiayao.discord_mc_chat.server.discord.DiscordManager";
    private static final String DMCC_V3_TELLRAW_CHANNEL_CONFIG = "broadcasts.minecraft_to_discord.source.tell_raw";
    private static final int EMBED_PURPLE = 0xA855F7;

    private DiscordRewardEmbedSender() {
    }

    public static Result send(String playerName, DragonRewardsText.OutcomeType type, boolean elytra, boolean dragonHead) {
        try {
            ChannelResolution resolution = resolveDmccChannel();
            if (!resolution.available()) {
                debug("Discord-MC-Chat classes are not available; using vanilla tellraw announcement.");
                return Result.UNAVAILABLE;
            }
            if (resolution.channel() == null) {
                DragonRewardsMod.LOGGER.warn("Discord reward embed could not resolve the DMCC Discord channel from {}.", resolution.source());
                return Result.FAILED;
            }

            Object channel = resolution.channel();
            Object embed = createEmbed(channel.getClass().getClassLoader(), playerName, type, elytra, dragonHead);
            Object action = sendEmbed(channel, embed);
            invokeCompatible(action, "queue");
            debug("Sent Discord reward embed through " + resolution.source() + ".");
            return Result.SENT;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            DragonRewardsMod.LOGGER.warn("Failed to send Discord reward embed through Discord-MC-Chat.", ex);
            return Result.FAILED;
        }
    }

    private static ChannelResolution resolveDmccChannel() throws ReflectiveOperationException {
        ChannelResolution legacy = resolveLegacyDmccChannel();
        if (legacy.available()) {
            return legacy;
        }
        return resolveV3DmccChannel();
    }

    private static ChannelResolution resolveLegacyDmccChannel() throws ReflectiveOperationException {
        try {
            Class<?> main = Class.forName(DMCC_LEGACY_MAIN);
            Object channel = getStaticField(main, "CHANNEL");
            if (channel != null) {
                return new ChannelResolution(true, channel, "Discord-MC-Chat 2.x Main.CHANNEL");
            }

            Object jda = getStaticField(main, "JDA");
            String channelId = resolveLegacyChannelId(main);
            if (jda != null && !channelId.isBlank()) {
                Object resolvedChannel = invokeCompatible(jda, "getTextChannelById", channelId);
                if (resolvedChannel != null) {
                    return new ChannelResolution(true, resolvedChannel, "Discord-MC-Chat 2.x JDA channel " + channelId);
                }
            }

            return new ChannelResolution(true, null, "Discord-MC-Chat 2.x main channel");
        } catch (ClassNotFoundException ex) {
            return ChannelResolution.unavailable();
        }
    }

    private static String resolveLegacyChannelId(Class<?> main) throws ReflectiveOperationException {
        Object config = getStaticField(main, "CONFIG");
        if (config == null) {
            return "";
        }

        Field genericField = config.getClass().getField("generic");
        Object generic = genericField.get(config);
        if (generic == null) {
            return "";
        }

        Field channelIdField = generic.getClass().getField("channelId");
        Object value = channelIdField.get(generic);
        return value instanceof String string ? string.trim() : "";
    }

    private static ChannelResolution resolveV3DmccChannel() throws ReflectiveOperationException {
        try {
            Class<?> configManager = Class.forName(DMCC_V3_CONFIG_MANAGER);
            Class<?> discordManager = Class.forName(DMCC_V3_DISCORD_MANAGER);
            String channelIdentifier = resolveV3ChannelIdentifier(configManager);
            if (channelIdentifier.isBlank()) {
                DragonRewardsMod.LOGGER.warn("Discord reward embed could not resolve DMCC channel config '{}'.", DMCC_V3_TELLRAW_CHANNEL_CONFIG);
                return new ChannelResolution(true, null, "Discord-MC-Chat v3 tellraw channel config");
            }

            Object channel = getV3TextChannel(discordManager, channelIdentifier);
            return new ChannelResolution(true, channel, "Discord-MC-Chat v3 channel " + channelIdentifier);
        } catch (ClassNotFoundException ex) {
            return ChannelResolution.unavailable();
        }
    }

    private static String resolveV3ChannelIdentifier(Class<?> configManager) throws ReflectiveOperationException {
        try {
            Method getString = configManager.getMethod("getString", String.class);
            Object value = getString.invoke(null, DMCC_V3_TELLRAW_CHANNEL_CONFIG);
            return value instanceof String string ? string.trim() : "";
        } catch (ReflectiveOperationException | RuntimeException ex) {
            DragonRewardsMod.LOGGER.warn("Failed to read DMCC channel config '{}'.", DMCC_V3_TELLRAW_CHANNEL_CONFIG, ex);
            return "";
        }
    }

    private static Object getV3TextChannel(Class<?> discordManager, String channelIdentifier) throws ReflectiveOperationException {
        Method getTextChannel = discordManager.getDeclaredMethod("getTextChannel", String.class);
        getTextChannel.setAccessible(true);
        return getTextChannel.invoke(null, channelIdentifier);
    }

    private static Object getStaticField(Class<?> owner, String fieldName) throws ReflectiveOperationException {
        Field field = owner.getField(fieldName);
        return field.get(null);
    }

    private static Object createEmbed(ClassLoader classLoader, String playerName, DragonRewardsText.OutcomeType type, boolean elytra, boolean dragonHead) throws ReflectiveOperationException {
        Class<?> embedBuilderClass = findJdaClass(classLoader, "EmbedBuilder");
        Object builder = embedBuilderClass.getConstructor().newInstance();

        invokeCompatible(builder, "setColor", EMBED_PURPLE);
        invokeCompatible(builder, "setTitle", "Dragon Reward");
        invokeCompatible(builder, "setDescription", descriptionFor(playerName));
        invokeCompatible(builder, "addField", "Reward", rewardText(elytra, dragonHead), false);
        invokeCompatible(builder, "setFooter", "Dragon Rewards", null);
        invokeCompatible(builder, "setTimestamp", OffsetDateTime.now());
        return invokeCompatible(builder, "build");
    }

    private static Object sendEmbed(Object channel, Object embed) throws ReflectiveOperationException {
        for (Method method : channel.getClass().getMethods()) {
            if (!method.getName().equals("sendMessageEmbeds")) {
                continue;
            }

            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1) {
                Object argument;
                if (parameters[0].isArray() && parameters[0].getComponentType().isAssignableFrom(embed.getClass())) {
                    argument = Array.newInstance(parameters[0].getComponentType(), 1);
                    Array.set(argument, 0, embed);
                } else if (Collection.class.isAssignableFrom(parameters[0])) {
                    argument = List.of(embed);
                } else if (parameters[0].isAssignableFrom(embed.getClass())) {
                    argument = embed;
                } else {
                    continue;
                }

                return method.invoke(channel, argument);
            }

            if (parameters.length != 2 || !parameters[1].isArray() || !parameters[0].isAssignableFrom(embed.getClass())) {
                continue;
            }

            Object emptyRemainder = Array.newInstance(parameters[1].getComponentType(), 0);
            return method.invoke(channel, embed, emptyRemainder);
        }

        throw new NoSuchMethodException("sendMessageEmbeds");
    }

    private static Class<?> findJdaClass(ClassLoader classLoader, String simpleName) throws ClassNotFoundException {
        try {
            return Class.forName("dmcc_dep.net.dv8tion.jda.api." + simpleName, true, classLoader);
        } catch (ClassNotFoundException ignored) {
            return Class.forName("net.dv8tion.jda.api." + simpleName, true, classLoader);
        }
    }

    private static Object invokeCompatible(Object target, String methodName, Object... args) throws ReflectiveOperationException {
        Method method = findCompatibleMethod(target.getClass(), methodName, args);
        if (!Modifier.isPublic(method.getModifiers())) {
            method.setAccessible(true);
        }
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw ex;
        }
    }

    private static Method findCompatibleMethod(Class<?> type, String methodName, Object[] args) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(methodName) && isCompatible(method.getParameterTypes(), args)) {
                return method;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && isCompatible(method.getParameterTypes(), args)) {
                return method;
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    private static boolean isCompatible(Class<?>[] parameterTypes, Object[] args) {
        if (parameterTypes.length != args.length) {
            return false;
        }

        for (int i = 0; i < parameterTypes.length; i++) {
            if (args[i] == null) {
                if (parameterTypes[i].isPrimitive()) {
                    return false;
                }
                continue;
            }

            Class<?> parameterType = wrapPrimitive(parameterTypes[i]);
            if (!parameterType.isAssignableFrom(args[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return Void.class;
    }

    private static String descriptionFor(String playerName) {
        return "**" + playerName + "** killed the Ender Dragon.";
    }

    private static String rewardText(boolean elytra, boolean dragonHead) {
        String improvedText = improvedRewardChanceText(elytra, dragonHead);
        String suffix = improvedText.isBlank() ? "" : "\n" + improvedText;

        if (elytra && dragonHead) {
            return "**Elytra** + **Dragon Head**" + suffix;
        }
        if (elytra) {
            return "**Elytra**" + suffix;
        }
        if (dragonHead) {
            return "**Dragon Head**" + suffix;
        }
        return "**No rare reward**" + suffix;
    }

    private static String improvedRewardChanceText(boolean elytraDropped, boolean dragonHeadDropped) {
        boolean elytraImproved = DragonRewardsMod.CONFIG.enableElytraDrops && !elytraDropped;
        boolean dragonHeadImproved = DragonRewardsMod.CONFIG.enableDragonHeadDrops && !dragonHeadDropped;

        if (elytraImproved && dragonHeadImproved) {
            return "Elytra and Dragon Head reward chances got better.";
        }
        if (elytraImproved) {
            return "Elytra reward chance got better.";
        }
        if (dragonHeadImproved) {
            return "Dragon Head reward chance got better.";
        }
        return "";
    }

    private static void debug(String message) {
        if (DragonRewardsMod.CONFIG.debugMode) {
            DragonRewardsMod.LOGGER.info("[debug] {}", message);
        }
    }

    public enum Result {
        SENT,
        FAILED,
        UNAVAILABLE
    }

    private record ChannelResolution(boolean available, Object channel, String source) {
        private static ChannelResolution unavailable() {
            return new ChannelResolution(false, null, "");
        }
    }
}
