package com.airijko.endlessleveling.managers;

import java.util.Locale;

/**
 * Version source of truth for addon-managed files and bundled example content.
 */
public final class AddonVersionRegistry {

    private AddonVersionRegistry() {
    }

    public static final String CONFIG_VERSION_KEY = "config_version";

    public static final int CONFIG_YML_VERSION = 2;
    public static final int DUNGEON_GATE_YML_VERSION = 1;

    public static final int BUILTIN_AUGMENTS_VERSION = 1;
    public static final int BUILTIN_CLASSES_VERSION = 1;
    public static final int BUILTIN_RACES_VERSION = 1;
    public static final int BUILTIN_PASSIVES_VERSION = 1;

    public static final String AUGMENTS_VERSION_FILE = "augments.version";
    public static final String CLASSES_VERSION_FILE = "classes.version";
    public static final String RACES_VERSION_FILE = "races.version";
    public static final String PASSIVES_VERSION_FILE = "passives.version";

    public static Integer getResourceConfigVersion(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return null;
        }
        return switch (resourceName.trim().toLowerCase(Locale.ROOT)) {
            case "config.yml" -> CONFIG_YML_VERSION;
            case "dungeongate.yml" -> DUNGEON_GATE_YML_VERSION;
            default -> null;
        };
    }
}

