package com.airijko.endlessleveling.managers;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Centralized runtime logging controls for the addon.
 */
public final class AddonLoggingManager {

    private static volatile boolean enableLogging = false;
    private static volatile Level baseLevel = Level.WARNING;
    private static volatile JavaPlugin plugin;

    private AddonLoggingManager() {
    }

    public static void configure(@Nullable JavaPlugin owner,
                                 boolean enableLoggingFlag,
                                 @Nullable Level configuredBaseLevel) {
        plugin = owner;
        enableLogging = enableLoggingFlag;
        baseLevel = configuredBaseLevel == null ? Level.WARNING : configuredBaseLevel;
    }

    public static boolean shouldLog(@Nonnull Level level) {
        Level effectiveBase = effectiveBaseLevel();
        return level.intValue() >= effectiveBase.intValue();
    }

    @Nonnull
    public static Level effectiveBaseLevel() {
        if (enableLogging) {
            return baseLevel;
        }

        // When logging is disabled, suppress INFO/FINE and clamp to WARNING/SEVERE.
        return baseLevel.intValue() >= Level.SEVERE.intValue() ? Level.SEVERE : Level.WARNING;
    }

    public static void log(@Nonnull HytaleLogger logger,
                           @Nonnull Level level,
                           @Nonnull String message,
                           Object... args) {
        if (!shouldLog(level)) {
            return;
        }

        String formatted = formatMessage(message, args);
        if (level.intValue() >= Level.SEVERE.intValue()) {
            logger.atSevere().log(formatted);
            return;
        }
        if (level.intValue() >= Level.WARNING.intValue()) {
            logger.atWarning().log(formatted);
            return;
        }
        if (level.intValue() >= Level.INFO.intValue()) {
            logger.atInfo().log(formatted);
            return;
        }
        logger.atFine().log(formatted);
    }

    public static void log(@Nullable JavaPlugin owner,
                           @Nonnull Level level,
                           @Nonnull String message,
                           Object... args) {
        if (!shouldLog(level)) {
            return;
        }

        JavaPlugin target = owner != null ? owner : plugin;
        if (target == null) {
            return;
        }

        target.getLogger().at(level).log(formatMessage(message, args));
    }

    public static void log(@Nullable JavaPlugin owner,
                           @Nonnull Level level,
                           @Nullable Throwable cause,
                           @Nonnull String message,
                           Object... args) {
        if (!shouldLog(level)) {
            return;
        }

        JavaPlugin target = owner != null ? owner : plugin;
        if (target == null) {
            return;
        }

        String formatted = formatMessage(message, args);
        if (cause == null) {
            target.getLogger().at(level).log(formatted);
            return;
        }

        target.getLogger().at(level).withCause(cause).log(formatted);
    }

    @Nonnull
    private static String formatMessage(@Nonnull String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        return String.format(Locale.ROOT, message, args);
    }
}