package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.commands.ExampleCommand;
import com.airijko.endlessleveling.listeners.ExampleEvent;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import java.util.Locale;

/**
 * Centralized runtime toggles for all addon example features.
 */
public final class ExampleFeatureManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final ExampleFeatureManager INSTANCE = new ExampleFeatureManager();

    private volatile boolean examplesEnabled = true;
    private volatile boolean exampleCommandEnabled = true;
    private volatile boolean exampleEventsEnabled = true;

    private ExampleFeatureManager() {
    }

    public static ExampleFeatureManager get() {
        return INSTANCE;
    }

    public void configure(boolean examplesEnabled, boolean exampleCommandEnabled, boolean exampleEventsEnabled) {
        this.examplesEnabled = examplesEnabled;
        this.exampleCommandEnabled = exampleCommandEnabled;
        this.exampleEventsEnabled = exampleEventsEnabled;
    }

    public boolean isExampleCommandEnabled() {
        return examplesEnabled && exampleCommandEnabled;
    }

    public boolean isExampleEventsEnabled() {
        return examplesEnabled && exampleEventsEnabled;
    }

    public boolean shouldRegisterContent(String fileName, String id) {
        if (examplesEnabled) {
            return true;
        }
        return !isExampleMarker(fileName) && !isExampleMarker(id);
    }

    public boolean isExamplesEnabled() {
        return examplesEnabled;
    }

    private boolean isExampleMarker(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.equals("example")
                || normalized.startsWith("example_")
                || normalized.endsWith("_example")
                || normalized.contains("_example_");
    }

    public void registerExamples(JavaPlugin plugin) {
        if (isExampleCommandEnabled()) {
            plugin.getCommandRegistry().registerCommand(new ExampleCommand("example", "An example command"));
            LOGGER.atInfo().log("Example command enabled.");
        } else {
            LOGGER.atInfo().log("Example command disabled by config.");
        }

        if (isExampleEventsEnabled()) {
            plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, ExampleEvent::onPlayerReady);
            LOGGER.atInfo().log("Example events enabled.");
        } else {
            LOGGER.atInfo().log("Example events disabled by config.");
        }
    }
}
