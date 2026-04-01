package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.enums.GateRankTier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import java.util.logging.Level;

/**
 * Minimal file bootstrap matching Endless Leveling's resource-first startup flow.
 */
public final class AddonFilesManager {

    private static final String PLUGIN_FOLDER_NAME = "EndlessLevelingAddon";
    private static final String CORE_PLUGIN_FOLDER_NAME = "EndlessLeveling";
    private static final String CORE_WORLD_SETTINGS_FOLDER_NAME = "world-settings";
    private static final String GATE_WORLD_SETTINGS_RESOURCE = "world-settings/el-gate-dungeons.json";
    private static final String GATE_WORLD_SETTINGS_FILE_NAME = "zz-el-gate-dungeons.json";
    private static final String LEGACY_GATE_WORLD_SETTINGS_FILE_NAME = "el-gate-dungeons.json";
    private static final String GATE_WORLD_OVERRIDES_PATH = "World_Overrides";
    private static final String CANONICAL_GATE_WORLD_OVERRIDE_KEY = "el_gate_*";
        private static final String[] MAJOR_DUNGEON_CONTENT_MARKERS = {
            "majordungeons",
            "mj_instance"
        };
        private static final String[] ENDGAME_CONTENT_MARKERS = {
            "endgameandqol",
            "endgame&qol",
            "config_endgame",
            "endgame_qol"
        };
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern MANIFEST_VERSION_PATTERN = Pattern.compile("\"Version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final JavaPlugin plugin;
    private final File pluginFolder;
    private final File racesFolder;
    private final File classesFolder;
    private final File augmentsFolder;
    private final File passivesFolder;
    private final File configFile;
    private final File dungeonGateFile;
    private final File wavesFolder;
    private final File conquerorExampleAugmentFile;
    private final File berzerkerExamplePassiveFile;
    private final File humanExampleRaceFile;
    private final File adventurerExampleClassFile;
    private final Object archiveLock = new Object();

    private AddonContentOptions contentOptions;
    private DungeonGateOptions dungeonGateOptions;
    private Path currentArchiveSession;

    public AddonFilesManager(JavaPlugin plugin) {
        this.plugin = plugin;

        if (PluginManager.MODS_PATH == null) {
            throw new IllegalStateException("Mods path is not initialized for EndlessLevelingAddon");
        }

        this.pluginFolder = PluginManager.MODS_PATH.resolve(PLUGIN_FOLDER_NAME).toFile();
        this.racesFolder = new File(pluginFolder, "races");
        this.classesFolder = new File(pluginFolder, "classes");
        this.augmentsFolder = new File(pluginFolder, "augments");
        this.passivesFolder = new File(pluginFolder, "passives");
        this.configFile = new File(pluginFolder, "config.yml");
        this.dungeonGateFile = new File(pluginFolder, "dungeongate.yml");
        this.wavesFolder = new File(pluginFolder, "waves");
        this.conquerorExampleAugmentFile = new File(augmentsFolder, "conqueror_example.yml");
        this.berzerkerExamplePassiveFile = new File(passivesFolder, "berzerker_example.yml");
        this.humanExampleRaceFile = new File(racesFolder, "human_example.yml");
        this.adventurerExampleClassFile = new File(classesFolder, "adventurer_exmaple.yml");

        initialize();
    }

    private void initialize() {
        createFolders();
        initYamlFile("config.yml");
        initYamlFile("dungeongate.yml");
        syncCoreWorldSettingsBundle();
        this.contentOptions = loadContentOptions();
        AddonLoggingManager.configure(plugin, this.contentOptions.enableLogging, this.contentOptions.loggingBaseLevel);
        this.dungeonGateOptions = loadDungeonGateOptions();
        syncConfigIfNeeded();
        syncDungeonGateIfNeeded();

        syncDirectoryIfNeeded(
                "races",
                racesFolder,
                AddonVersionRegistry.RACES_VERSION_FILE,
                AddonVersionRegistry.BUILTIN_RACES_VERSION,
                contentOptions.mergeRacesWithCore,
                contentOptions.examplesEnabled);

        syncDirectoryIfNeeded(
                "classes",
                classesFolder,
                AddonVersionRegistry.CLASSES_VERSION_FILE,
                AddonVersionRegistry.BUILTIN_CLASSES_VERSION,
                contentOptions.mergeClassesWithCore,
                contentOptions.examplesEnabled);

        syncDirectoryIfNeeded(
                "augments",
                augmentsFolder,
                AddonVersionRegistry.AUGMENTS_VERSION_FILE,
                AddonVersionRegistry.BUILTIN_AUGMENTS_VERSION,
                contentOptions.mergeAugmentsWithCore,
                contentOptions.examplesEnabled);

        syncDirectoryIfNeeded(
                "passives",
                passivesFolder,
                AddonVersionRegistry.PASSIVES_VERSION_FILE,
                AddonVersionRegistry.BUILTIN_PASSIVES_VERSION,
                contentOptions.mergePassivesWithCore,
                contentOptions.examplesEnabled);

        seedWavesDirectory();
    }

    private void syncConfigIfNeeded() {
        int storedVersion = readConfigVersion(configFile);
        int targetVersion = AddonVersionRegistry.CONFIG_YML_VERSION;
        if (storedVersion == targetVersion) {
            return;
        }

        AddonContentOptions migratedOptions = contentOptions == null ? AddonContentOptions.defaults() : contentOptions;

        archiveFileIfExists(configFile, "config.yml", "config_version:" + storedVersion);
        writeNormalizedConfig(migratedOptions, targetVersion);

        log(Level.INFO, "Migrated config.yml to version %d and normalized schema", targetVersion);

        try {
            ensureConfigVersionMarkerOnCreate("config.yml", configFile);
        } catch (IOException exception) {
            log(Level.WARNING, "Failed to append config version marker: %s", exception.getMessage());
        }

        this.contentOptions = loadContentOptions();
    }

    private void syncDungeonGateIfNeeded() {
        int storedVersion = readConfigVersion(dungeonGateFile);
        int targetVersion = AddonVersionRegistry.DUNGEON_GATE_YML_VERSION;
        if (storedVersion == targetVersion) {
            return;
        }

        DungeonGateOptions migratedOptions = dungeonGateOptions == null ? DungeonGateOptions.defaults() : dungeonGateOptions;

        archiveFileIfExists(dungeonGateFile, "dungeongate.yml", "config_version:" + storedVersion);
        writeDungeonGateConfig(migratedOptions, targetVersion);

        log(Level.INFO, "Migrated dungeongate.yml to version %d and normalized schema", targetVersion);

        try {
            ensureConfigVersionMarkerOnCreate("dungeongate.yml", dungeonGateFile);
        } catch (IOException exception) {
            log(Level.WARNING, "Failed to append dungeongate config version marker: %s", exception.getMessage());
        }

        this.dungeonGateOptions = loadDungeonGateOptions();
    }

    private void writeDungeonGateConfig(DungeonGateOptions options, int targetVersion) {
        StringBuilder text = new StringBuilder();
        text.append("# EndlessLevelingAddon - Dungeon Gate configuration\n\n");
        text.append("# Master switch. Set to false to completely disable dungeon gate spawning.\n");
        text.append("enabled: ").append(options.enabled).append("\n\n");

        text.append("# -----------------------------------------------------------------------\n");
        text.append("# Spawning\n");
        text.append("# -----------------------------------------------------------------------\n\n");

        text.append("# How frequently (in minutes) a new dungeon gate can naturally spawn.\n");
        text.append("# Each spawn cycle picks a random value between min and max (inclusive).\n");
        text.append("# Minimum enforced value: 1.\n");
        text.append("spawn_interval_minutes_min: ").append(options.spawnIntervalMinutesMin).append("\n");
        text.append("spawn_interval_minutes_max: ").append(options.spawnIntervalMinutesMax).append("\n\n");

        text.append("# Maximum number of dungeon gate instances that may be active at the same time.\n");
        text.append("# Set to -1 for no limit.\n");
        text.append("max_concurrent_spawns: ").append(options.maxConcurrentSpawns).append("\n\n");

        text.append("# World names allowed to summon/spawn dungeon portals.\n");
        text.append("# Natural spawning and manual gate summon commands both respect this list.\n");
        text.append("portal_world_whitelist:\n");
        for (String worldName : options.portalWorldWhitelist) {
            text.append("  - ").append(worldName).append("\n");
        }
        text.append("\n");

        text.append("# Announce in global chat when a dungeon gate spawns.\n");
        text.append("announce_on_spawn: ").append(options.announceOnSpawn).append("\n\n");

        text.append("# Announce in global chat when a dungeon gate despawns.\n");
        text.append("announce_on_despawn: ").append(options.announceOnDespawn).append("\n\n");

        text.append("# -----------------------------------------------------------------------\n");
        text.append("# Gate lifetime\n");
        text.append("# -----------------------------------------------------------------------\n\n");

        text.append("# How long (in minutes) an open gate stays active before it closes on its own.\n");
        text.append("# Set to -1 to never auto-close.\n");
        text.append("gate_duration_minutes: ").append(options.gateDurationMinutes).append("\n\n");

        text.append("# -----------------------------------------------------------------------\n");
        text.append("# Entry restrictions\n");
        text.append("# -----------------------------------------------------------------------\n\n");

        text.append("# Whether players who die inside a dungeon instance are allowed to re-enter it.\n");
        text.append("# false = locked out for that run (recommended for challenge content).\n");
        text.append("allow_reentry_after_death: ").append(options.allowReentryAfterDeath).append("\n\n");

        text.append("# Maximum number of players allowed inside one gate instance at a time.\n");
        text.append("# Set to -1 for no limit.\n");
        text.append("max_players_per_instance: ").append(options.maxPlayersPerInstance).append("\n\n");

        text.append("# Minimum player level required to enter a gate.\n");
        text.append("# Set to 1 to allow everyone.\n");
        text.append("min_level_required: ").append(options.minLevelRequired).append("\n\n");

        text.append("# -----------------------------------------------------------------------\n");
        text.append("# Dynamic level targeting\n");
        text.append("# -----------------------------------------------------------------------\n\n");

        text.append("# Gate rank and level are linked to the global online player range.\n");
        text.append("# Lowest and highest online player levels define the scaling span.\n");
        text.append("# Rolled rank chooses a point in that span (E near low end, S near high end).\n\n");

        text.append("# Minimum rank floors measured from the selected rank anchor level.\n");
        text.append("# E floor keeps low ranks from spawning near level 1.\n");
        text.append("# S floor guarantees high-end gates stay dangerous even on fresh servers.\n");
        text.append("# All ranks between E and S are evenly distributed between these floors.\n");
        text.append("# Minimum enforced value: 0.\n");
        text.append("rank_floor_e_min_offset: ").append(options.rankFloorEMinOffset).append("\n");
        text.append("rank_floor_s_min_offset: ").append(options.rankFloorSMinOffset).append("\n\n");

        text.append("# If true, E..S absolute floor offsets are compressed to the current highest\n");
        text.append("# player level band when the server's top level is below S floor.\n");
        text.append("adaptive_rank_floor_scaling_enabled: ").append(options.adaptiveRankFloorScalingEnabled).append("\n\n");

        text.append("# S-rank pity boost: when many top-level players are online and S has not spawned\n");
        text.append("# for a long time, temporarily boost S rank roll weight.\n");
        text.append("s_rank_pity_enabled: ").append(options.sRankPityEnabled).append("\n");
        text.append("s_rank_pity_min_hours_since_last_spawn: ").append(options.sRankPityMinHoursSinceLastSpawn).append("\n");
        text.append("s_rank_pity_top_player_count_min: ").append(options.sRankPityTopPlayerCountMin).append("\n");
        text.append("s_rank_pity_top_level_delta: ").append(options.sRankPityTopLevelDelta).append("\n");
        text.append("s_rank_pity_s_weight_multiplier: ").append(options.sRankPitySWeightMultiplier).append("\n\n");

        text.append("# Low-level rank bias: if the anchor player is below the E floor, bias gate rank\n");
        text.append("# toward nearby tier floors so low-level players get more appropriate gates.\n");
        text.append("low_level_rank_bias_enabled: ").append(options.lowLevelRankBiasEnabled).append("\n");
        text.append("low_level_rank_bias_window_levels: ").append(options.lowLevelRankBiasWindowLevels).append("\n");
        text.append("low_level_rank_bias_strength_percent: ").append(options.lowLevelRankBiasStrengthPercent).append("\n\n");

        text.append("# S-rank random offset range added on top of the highest player level.\n");
        text.append("# Example: highest=100 and rolled offset=0 => normal mob minimum starts at 100.\n");
        text.append("# Minimum enforced value: 0.\n");
        text.append("level_offset_min: ").append(options.levelOffsetMin).append("\n");
        text.append("level_offset_max: ").append(options.levelOffsetMax).append("\n\n");

        text.append("# Normal mob level spread size for the gate (inclusive range).\n");
        text.append("# Example: 20 with anchor 80 => normal mobs are 60-80.\n");
        text.append("# Minimum enforced value: 0.\n");
        text.append("normal_mob_level_range: ").append(options.normalMobLevelRange).append("\n\n");

        text.append("# Boss bonus added on top of the highest normal mob level.\n");
        text.append("# Example: normal 60-80 with boss bonus 10 => boss is level 90.\n");
        text.append("# Minimum enforced value: 0.\n");
        text.append("boss_level_bonus: ").append(options.bossLevelBonus).append("\n\n");

        text.append("# Wave mobs per rank used by /gate wave start <rank>.\n");
        text.append("# Default progression: E=5 and each tier doubles (D=10, C=20, B=40, A=80, S=160).\n");
        text.append("# Minimum enforced value: 1.\n");
        text.append("wave_mob_count_by_rank:\n");
        text.append("  E: ").append(options.waveMobCountE).append("\n");
        text.append("  D: ").append(options.waveMobCountD).append("\n");
        text.append("  C: ").append(options.waveMobCountC).append("\n");
        text.append("  B: ").append(options.waveMobCountB).append("\n");
        text.append("  A: ").append(options.waveMobCountA).append("\n");
        text.append("  S: ").append(options.waveMobCountS).append("\n\n");

        text.append("# Seconds to wait after clearing a wave before the next wave starts.\n");
        text.append("# Global setting for all ranks. Minimum enforced value: 1 second.\n");
        text.append("wave_interval_seconds: ").append(options.waveIntervalSecondsE).append("\n\n");

        text.append("# Minutes before a natural dungeon break wave opens after the global announcement.\n");
        text.append("# Configurable per rank. Minimum enforced value: 1.\n");
        text.append("# Defaults: E/D/C/B = 1 min, A = 5 min, S = 10 min.\n");
        text.append("natural_wave_open_delay_minutes:\n");
        text.append("  E: ").append(options.naturalWaveOpenDelayMinutesE).append("\n");
        text.append("  D: ").append(options.naturalWaveOpenDelayMinutesD).append("\n");
        text.append("  C: ").append(options.naturalWaveOpenDelayMinutesC).append("\n");
        text.append("  B: ").append(options.naturalWaveOpenDelayMinutesB).append("\n");
        text.append("  A: ").append(options.naturalWaveOpenDelayMinutesA).append("\n");
        text.append("  S: ").append(options.naturalWaveOpenDelayMinutesS).append("\n\n");

        text.append("# Global natural wave scheduler interval in minutes.\n");
        text.append("# A random value between min and max (inclusive) is used for each cycle.\n");
        text.append("# Minimum enforced value: 1.\n");
        text.append("natural_wave_spawn_interval_minutes_min: ")
            .append(options.naturalWaveSpawnIntervalMinutesMin)
            .append("\n");
        text.append("natural_wave_spawn_interval_minutes_max: ")
            .append(options.naturalWaveSpawnIntervalMinutesMax)
            .append("\n\n");

        text.append("# Maximum number of independently spawned natural waves active/pending at once.\n");
        text.append("# Set to -1 for no limit.\n");
        text.append("natural_wave_max_concurrent_spawns: ").append(options.naturalWaveMaxConcurrentSpawns).append("\n\n");

        text.append("# Which players are considered when determining the highest-level player ceiling.\n");
        text.append("# online  = only players currently online (high-water mark: remembered until restart).\n");
        text.append("# overall = all players ever seen (online and offline data).\n");
        text.append("level_player_scope: ").append(options.levelPlayerScope.toLowerCase(Locale.ROOT)).append("\n\n");

        text.append("# Which mob level is used as the anchor point when placing a rank in the band.\n");
        text.append("# lowest_mob  = the lowest normal mob level determines rank position.\n");
        text.append("# highest_mob = the highest normal mob level determines rank position (default).\n");
        text.append("# boss        = the boss mob level determines rank position.\n");
        text.append("rank_anchor_mode: ").append(options.rankAnchorMode.toLowerCase(Locale.ROOT).replace('_', '_')).append("\n\n");

        text.append("# -----------------------------------------------------------------------\n");
        text.append("# Rank weighting\n");
        text.append("# -----------------------------------------------------------------------\n\n");

        text.append("# Weighted chance used when rolling a gate rank.\n");
        text.append("# Values are relative weights (not required to sum to 100).\n");
        text.append("# Effective chance = tier_weight / total_weight.\n");
        text.append("rank_weights:\n");
        text.append("  S: ").append(options.rankWeightS).append("\n");
        text.append("  A: ").append(options.rankWeightA).append("\n");
        text.append("  B: ").append(options.rankWeightB).append("\n");
        text.append("  C: ").append(options.rankWeightC).append("\n");
        text.append("  D: ").append(options.rankWeightD).append("\n");
        text.append("  E: ").append(options.rankWeightE).append("\n\n");

        text.append(AddonVersionRegistry.CONFIG_VERSION_KEY).append(": ").append(targetVersion).append("\n");

        try {
            Files.writeString(dungeonGateFile.toPath(), text.toString(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to migrate dungeongate.yml", exception);
        }
    }

    private void syncDirectoryIfNeeded(String resourceRoot,
            File destination,
            String versionFileName,
            int targetVersion,
            boolean mergeEnabled,
            boolean allowSeed) {
        if (!mergeEnabled || destination == null) {
            return;
        }

        int storedVersion = readDirectoryVersion(destination, versionFileName);
        if (storedVersion >= 0 && storedVersion < targetVersion) {
            exportResourceDirectory(resourceRoot, destination, false);
            writeDirectoryVersion(destination, versionFileName, targetVersion);
                log(Level.INFO, "Migrated %s from version %d to %d (non-destructive)",
                    resourceRoot,
                    storedVersion,
                    targetVersion);
            return;
        }

        if (!allowSeed) {
            return;
        }

        if (seedResourceDirectoryIfEmpty(resourceRoot, destination)) {
            writeDirectoryVersion(destination, versionFileName, targetVersion);
        }
    }

    @SuppressWarnings("unchecked")
    private AddonContentOptions loadContentOptions() {
        AddonContentOptions defaults = AddonContentOptions.defaults();
        if (!configFile.isFile()) {
            return defaults;
        }

        try (InputStream in = Files.newInputStream(configFile.toPath())) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> rootRaw)) {
                return defaults;
            }

            Map<String, Object> root = (Map<String, Object>) rootRaw;
            Map<String, Object> merge = asMap(root.get("core_content_merge"));
            Map<String, Object> sync = asMap(root.get("sync"));
            Map<String, Object> examples = asMap(root.get("examples"));

            boolean mergeRaces = readBoolean(
                    merge.get("races"),
                    readBoolean(root.get("enable_builtin_races"), readBoolean(root.get("enable_races"), true)));
            boolean mergeClasses = readBoolean(
                    merge.get("classes"),
                    readBoolean(root.get("enable_builtin_classes"), readBoolean(root.get("enable_classes"), true)));
            boolean mergeAugments = readBoolean(
                    merge.get("augments"),
                    readBoolean(root.get("enable_builtin_augments"), readBoolean(root.get("enable_augments"), true)));
            boolean mergePassives = readBoolean(
                    merge.get("passives"),
                    readBoolean(root.get("enable_builtin_passives"), readBoolean(root.get("enable_passives"), true)));

            boolean legacySeedExamples = readBoolean(sync.get("seed_addon_examples_on_startup"),
                    readBoolean(sync.get("seed_defaults_on_startup"), true));

            boolean enableExamples = readBoolean(
                    examples.get("enabled"),
                    readBoolean(root.get("enable_examples"), legacySeedExamples));
            boolean enableExampleCommand = readBoolean(
                    examples.get("command"),
                    readBoolean(root.get("enable_example_command"), true));
            boolean enableExampleEvents = readBoolean(
                    examples.get("events"),
                    readBoolean(root.get("enable_example_events"), true));

                boolean enableLogging = readBoolean(root.get("enable_logging"), false);
                Map<String, Object> logging = asMap(root.get("logging"));
                Level loggingBaseLevel = parseLogLevel(logging.get("base_level"), Level.WARNING);

            return new AddonContentOptions(
                    mergeRaces,
                    mergeClasses,
                    mergeAugments,
                    mergePassives,
                    enableExamples,
                    enableExampleCommand,
                    enableExampleEvents,
                    enableLogging,
                    loggingBaseLevel);
        } catch (IOException ignored) {
            return defaults;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private static boolean readBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str && !str.isBlank()) {
            return Boolean.parseBoolean(str.trim());
        }
        return fallback;
    }

    private void createFolders() {
        try {
            Files.createDirectories(pluginFolder.toPath());
            Files.createDirectories(racesFolder.toPath());
            Files.createDirectories(classesFolder.toPath());
            Files.createDirectories(augmentsFolder.toPath());
            Files.createDirectories(passivesFolder.toPath());
            Files.createDirectories(wavesFolder.toPath());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create EndlessLevelingAddon folders", exception);
        }
    }

    public File initYamlFile(String resourceName) {
        File yamlFile = new File(pluginFolder, resourceName);
        if (yamlFile.exists()) {
            return yamlFile;
        }

        try (InputStream resourceStream = plugin.getClassLoader().getResourceAsStream(resourceName)) {
            if (resourceStream == null) {
                throw new FileNotFoundException("Resource " + resourceName + " not found in addon JAR");
            }
            try (OutputStream out = new FileOutputStream(yamlFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = resourceStream.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
            }
            ensureConfigVersionMarkerOnCreate(resourceName, yamlFile);
            log(Level.INFO, "YAML file %s created at %s", resourceName, yamlFile.getAbsolutePath());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create YAML file: " + resourceName, exception);
        }
        return yamlFile;
    }

    private void ensureConfigVersionMarkerOnCreate(String resourceName, File yamlFile) throws IOException {
        Integer version = AddonVersionRegistry.getResourceConfigVersion(resourceName);
        if (version == null || yamlFile == null || !yamlFile.exists()) {
            return;
        }

        String content = Files.readString(yamlFile.toPath());
        if (content.contains(AddonVersionRegistry.CONFIG_VERSION_KEY + ":")) {
            return;
        }

        String lineBreak = content.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder suffix = new StringBuilder();
        if (!content.endsWith("\n") && !content.endsWith("\r")) {
            suffix.append(lineBreak);
        }
        suffix.append(lineBreak)
                .append("# DON'T EDIT THIS LINE BELOW")
                .append(lineBreak)
                .append(AddonVersionRegistry.CONFIG_VERSION_KEY)
                .append(": ")
                .append(version)
                .append(lineBreak);
        Files.writeString(yamlFile.toPath(), suffix.toString(), StandardOpenOption.APPEND);
    }

    private int readConfigVersion(File yamlFile) {
        if (yamlFile == null || !yamlFile.exists()) {
            return -1;
        }
        try {
            String content = Files.readString(yamlFile.toPath());
            Pattern pattern = Pattern.compile(AddonVersionRegistry.CONFIG_VERSION_KEY + "\\s*:\\s*(\\d+)");
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void writeNormalizedConfig(AddonContentOptions options, int targetVersion) {
        StringBuilder text = new StringBuilder();
        text.append("# EndlessLevelingAddon configuration\n\n");
        text.append("# True = merge Endless Leveling Core defaults with your custom content.\n");
        text.append("# False = fresh start for that system (Core defaults are not merged in by this addon workflow).\n");
        text.append("core_content_merge:\n");
        text.append("  races: ").append(options.mergeRacesWithCore).append("\n");
        text.append("  classes: ").append(options.mergeClassesWithCore).append("\n");
        text.append("  augments: ").append(options.mergeAugmentsWithCore).append("\n");
        text.append("  passives: ").append(options.mergePassivesWithCore).append("\n\n");

        text.append("# Addon updates are always migration-only and never force overwrite your files.\n\n");

        text.append("# Master logging toggle: true enables addon INFO/FINE logging.\n");
        text.append("enable_logging: ").append(options.enableLogging).append("\n\n");

        text.append("logging:\n");
        text.append("  # Base level used when enable_logging is true.\n");
        text.append("  # Supported: SEVERE, WARNING, INFO, FINE\n");
        text.append("  # When enable_logging is false, this is clamped to WARNING/SEVERE.\n");
        text.append("  base_level: ").append(options.loggingBaseLevel.getName()).append("\n\n");

        text.append("examples:\n");
        text.append("  # Master switch for all Java example features in this addon.\n");
        text.append("  enabled: ").append(options.examplesEnabled).append("\n\n");

        text.append("  # Toggle /example command registration + execution.\n");
        text.append("  command: ").append(options.exampleCommandEnabled).append("\n\n");

        text.append("  # Toggle example event hooks (e.g. player ready welcome message).\n");
        text.append("  events: ").append(options.exampleEventsEnabled).append("\n\n");

        text.append(AddonVersionRegistry.CONFIG_VERSION_KEY).append(": ").append(targetVersion).append("\n");

        try {
            Files.writeString(configFile.toPath(), text.toString(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to migrate config.yml", exception);
        }
    }

    private void copyResourceToFile(String resourcePath, File targetFile, boolean overwriteExisting) {
        try (InputStream resourceStream = plugin.getClassLoader().getResourceAsStream(resourcePath)) {
            if (resourceStream == null) {
                log(Level.WARNING, "Resource %s not found in addon JAR", resourcePath);
                return;
            }
            File parent = targetFile.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            if (!overwriteExisting && targetFile.exists()) {
                return;
            }
            Files.copy(resourceStream, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to copy resource: " + resourcePath, exception);
        }
    }

    private void syncCoreWorldSettingsBundle() {
        if (PluginManager.MODS_PATH == null) {
            return;
        }

        File coreWorldSettingsFolder = PluginManager.MODS_PATH
                .resolve(CORE_PLUGIN_FOLDER_NAME)
                .resolve(CORE_WORLD_SETTINGS_FOLDER_NAME)
                .toFile();

        if (!coreWorldSettingsFolder.exists() && !coreWorldSettingsFolder.mkdirs()) {
                log(Level.WARNING, "Unable to create core world-settings folder at %s",
                    coreWorldSettingsFolder.getAbsolutePath());
            return;
        }

        File legacyTargetFile = new File(coreWorldSettingsFolder, LEGACY_GATE_WORLD_SETTINGS_FILE_NAME);
        if (legacyTargetFile.exists() && !legacyTargetFile.getName().equals(GATE_WORLD_SETTINGS_FILE_NAME)) {
            try {
                Files.deleteIfExists(legacyTargetFile.toPath());
            } catch (IOException exception) {
                log(Level.WARNING, "Unable to remove legacy gate world-settings bundle at %s: %s",
                        legacyTargetFile.getAbsolutePath(),
                        exception.getMessage());
            }
        }

        File targetFile = new File(coreWorldSettingsFolder, GATE_WORLD_SETTINGS_FILE_NAME);
        syncGateWorldSettingsBundle(targetFile.toPath());
        log(Level.INFO, "Synced addon gate world-settings bundle to %s", targetFile.getAbsolutePath());

        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api != null) {
            api.reloadWorldSettings();
            log(Level.INFO, "Triggered core world-settings reload after sync");
        }
    }

    private void syncGateWorldSettingsBundle(Path targetPath) {
        try {
            Map<String, Object> bundled = readBundledJsonAsMap(GATE_WORLD_SETTINGS_RESOURCE);
            if (bundled == null) {
                return;
            }

            Map<String, Object> canonicalBundled = canonicalizeGateWorldSettings(bundled);
            if (!Files.exists(targetPath)) {
                writeJsonMap(targetPath, canonicalBundled);
                return;
            }

            Map<String, Object> current = readJsonFileAsMap(targetPath);
            if (current == null) {
                writeJsonMap(targetPath, canonicalBundled);
                return;
            }

            Map<String, Object> canonicalCurrent = canonicalizeGateWorldSettings(current);
            Map<String, Object> merged = deepMergeDefaultsWithCurrent(canonicalBundled, canonicalCurrent);
            if (!Objects.equals(current, merged)) {
                archivePathIfExists(targetPath, "world-settings/" + GATE_WORLD_SETTINGS_FILE_NAME,
                        "normalized-gate-world-settings");
                writeJsonMap(targetPath, merged);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sync addon gate world-settings bundle", exception);
        }
    }

    private Map<String, Object> readBundledJsonAsMap(String resourcePath) {
        try (InputStream in = plugin.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                log(Level.WARNING, "Bundled world-settings resource missing: %s", resourcePath);
                return null;
            }
            try (Reader reader = new InputStreamReader(in)) {
                return GSON.fromJson(reader, new TypeToken<LinkedHashMap<String, Object>>() {
                }.getType());
            }
        } catch (Exception exception) {
            log(Level.WARNING, "Failed to read bundled world-settings resource %s: %s",
                    resourcePath,
                    exception.getMessage());
            return null;
        }
    }

    private Map<String, Object> readJsonFileAsMap(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, new TypeToken<LinkedHashMap<String, Object>>() {
            }.getType());
        } catch (Exception exception) {
            log(Level.WARNING, "Failed to parse JSON file %s: %s", path, exception.getMessage());
            return null;
        }
    }

    private void writeJsonMap(Path targetPath, Map<String, Object> content) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String output = GSON.toJson(content);
        if (!output.endsWith("\n")) {
            output = output + "\n";
        }
        Files.writeString(targetPath, output);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> canonicalizeGateWorldSettings(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return source;
        }

        Object worldOverridesRaw = source.get(GATE_WORLD_OVERRIDES_PATH);
        if (!(worldOverridesRaw instanceof Map<?, ?> worldOverrides)) {
            return source;
        }

        Map<String, Object> mergedGateOverride = new LinkedHashMap<>();
        Map<String, Object> passthroughOverrides = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : worldOverrides.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> valueMap)) {
                passthroughOverrides.put(key, entry.getValue());
                continue;
            }
            if (isLegacyGateWorldOverrideKey(key)) {
                mergedGateOverride = deepMergeDefaultsWithCurrent(mergedGateOverride,
                        (Map<String, Object>) valueMap);
                continue;
            }
            passthroughOverrides.put(key, entry.getValue());
        }

        Object existingCanonical = passthroughOverrides.remove(CANONICAL_GATE_WORLD_OVERRIDE_KEY);
        if (existingCanonical instanceof Map<?, ?> existingCanonicalMap) {
            mergedGateOverride = deepMergeDefaultsWithCurrent(mergedGateOverride,
                    (Map<String, Object>) existingCanonicalMap);
        }

        if (mergedGateOverride.isEmpty()) {
            return source;
        }

        Map<String, Object> normalizedOverrides = new LinkedHashMap<>();
        normalizedOverrides.put(CANONICAL_GATE_WORLD_OVERRIDE_KEY, mergedGateOverride);
        normalizedOverrides.putAll(passthroughOverrides);

        Map<String, Object> normalizedRoot = new LinkedHashMap<>(source);
        normalizedRoot.put(GATE_WORLD_OVERRIDES_PATH, normalizedOverrides);
        return normalizedRoot;
    }

    private boolean isLegacyGateWorldOverrideKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lowered = key.toLowerCase(Locale.ROOT);
        return lowered.contains("el_mj_instance_")
                || lowered.contains("el_endgame_")
                || lowered.contains("el_gate_")
                || CANONICAL_GATE_WORLD_OVERRIDE_KEY.equalsIgnoreCase(lowered);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMergeDefaultsWithCurrent(Map<String, Object> defaults, Map<String, Object> current) {
        Map<String, Object> merged = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            Object defaultValue = entry.getValue();
            Object currentValue = current.get(key);

            if (defaultValue instanceof Map<?, ?> defaultMap && currentValue instanceof Map<?, ?> currentMap) {
                merged.put(key, deepMergeDefaultsWithCurrent((Map<String, Object>) defaultMap,
                        (Map<String, Object>) currentMap));
            } else if (current.containsKey(key)) {
                merged.put(key, currentValue);
            } else {
                merged.put(key, defaultValue);
            }
        }

        for (Map.Entry<String, Object> entry : current.entrySet()) {
            if (!merged.containsKey(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }

        return merged;
    }

    public Path archiveFileIfExists(File sourceFile, String archiveRelativePath, String priorVersionTag) {
        if (sourceFile == null) {
            return null;
        }
        return archivePathIfExists(sourceFile.toPath(), archiveRelativePath, priorVersionTag);
    }

    public Path archivePathIfExists(Path sourcePath, String archiveRelativePath, String priorVersionTag) {
        if (sourcePath == null || !Files.exists(sourcePath)) {
            return null;
        }
        synchronized (archiveLock) {
            Path archiveRoot = getOrCreateArchiveSession();
            Path targetPath = archiveRelativePath == null || archiveRelativePath.isBlank()
                    ? archiveRoot.resolve(sourcePath.getFileName().toString())
                    : archiveRoot.resolve(archiveRelativePath);
            try {
                copyRecursively(sourcePath, targetPath);
                appendArchiveIndexLine(archiveRoot, sourcePath, targetPath, priorVersionTag);
                log(Level.INFO, "Archived %s to %s", sourcePath, targetPath);
                return targetPath;
            } catch (IOException e) {
                log(Level.WARNING, "Failed to archive %s: %s", sourcePath, e.getMessage());
                return null;
            }
        }
    }

    private Path getOrCreateArchiveSession() {
        if (currentArchiveSession != null) {
            return currentArchiveSession;
        }
        String timestamp = LocalDateTime.now().format(ARCHIVE_TIMESTAMP_FORMAT);
        String pluginVersion = sanitizeForPath(resolvePluginVersion());
        Path sessionPath = pluginFolder.toPath().resolve("old").resolve(timestamp + "_v" + pluginVersion);
        try {
            Files.createDirectories(sessionPath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create centralized backup folder", e);
        }
        currentArchiveSession = sessionPath;
        return currentArchiveSession;
    }

    private void appendArchiveIndexLine(Path archiveRoot, Path sourcePath, Path targetPath, String priorVersionTag)
            throws IOException {
        String versionText = (priorVersionTag == null || priorVersionTag.isBlank()) ? "unknown" : priorVersionTag;
        Path indexPath = archiveRoot.resolve("index.txt");
        String line = String.format("%s | from=%s | prior=%s%n", targetPath.getFileName(), sourcePath, versionText);
        Files.writeString(indexPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void copyRecursively(Path sourcePath, Path destinationPath) throws IOException {
        if (Files.isDirectory(sourcePath)) {
            try (Stream<Path> stream = Files.walk(sourcePath)) {
                stream.forEach(path -> {
                    Path relative = sourcePath.relativize(path);
                    Path target = destinationPath.resolve(relative.toString());
                    try {
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(target);
                        } else {
                            Path parent = target.getParent();
                            if (parent != null) {
                                Files.createDirectories(parent);
                            }
                            Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException io) {
                    throw io;
                }
                throw e;
            }
            return;
        }
        Path parent = destinationPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private String resolvePluginVersion() {
        try (InputStream in = plugin.getClassLoader().getResourceAsStream("manifest.json")) {
            if (in != null) {
                String json = new String(in.readAllBytes());
                Matcher matcher = MANIFEST_VERSION_PATTERN.matcher(json);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }
        } catch (IOException ignored) {
        }

        Package pkg = plugin.getClass().getPackage();
        if (pkg != null) {
            String implementationVersion = pkg.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.isBlank()) {
                return implementationVersion.trim();
            }
        }
        return "unknown";
    }

    private String sanitizeForPath(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public void exportResourceDirectory(String resourceRoot, File destination, boolean overwriteExisting) {
        try {
            Files.createDirectories(destination.toPath());
            CodeSource codeSource = plugin.getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                log(Level.WARNING, "Unable to locate code source while exporting %s", resourceRoot);
                return;
            }

            Path sourcePath = Paths.get(codeSource.getLocation().toURI());
            if (Files.isDirectory(sourcePath)) {
                Path resourcePath = sourcePath.resolve(resourceRoot);
                if (!Files.exists(resourcePath)) {
                    log(Level.WARNING, "Resource directory %s not found under %s", resourceRoot, sourcePath);
                    return;
                }
                copyDirectory(resourcePath, destination.toPath(), overwriteExisting);
                return;
            }

            try (InputStream fileInput = Files.newInputStream(sourcePath);
                    JarInputStream jarStream = new JarInputStream(fileInput)) {
                String prefix = resourceRoot.endsWith("/") ? resourceRoot : resourceRoot + "/";
                JarEntry entry;
                while ((entry = jarStream.getNextJarEntry()) != null) {
                    try {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        String name = entry.getName();
                        if (!name.startsWith(prefix)) {
                            continue;
                        }

                        Path relativePath = Paths.get(name.substring(prefix.length()));
                        Path targetPath = destination.toPath().resolve(relativePath.toString());
                        if (!overwriteExisting && Files.exists(targetPath)) {
                            continue;
                        }

                        Path parent = targetPath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(jarStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        jarStream.closeEntry();
                    }
                }
            }
        } catch (Exception e) {
            log(Level.WARNING, "Failed to export resource directory %s: %s", resourceRoot, e.getMessage());
        }
    }

    private boolean seedResourceDirectoryIfEmpty(String resourceRoot, File destination) {
        if (destination == null || hasYamlFiles(destination.toPath())) {
            return false;
        }
        exportResourceDirectory(resourceRoot, destination, false);
        return hasYamlFiles(destination.toPath());
    }

    private boolean hasYamlFiles(Path folder) {
        if (folder == null || !Files.exists(folder)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.toString().toLowerCase())
                    .anyMatch(path -> path.endsWith(".yml") || path.endsWith(".yaml"));
        } catch (IOException e) {
            log(Level.WARNING, "Failed to inspect %s for YAML files: %s", folder, e.getMessage());
            return false;
        }
    }

    private void copyDirectory(Path source, Path destination, boolean overwriteExisting) throws IOException {
        if (!Files.exists(source)) {
            log(Level.WARNING, "Source directory %s does not exist when exporting resources.", source);
            return;
        }
        try (Stream<Path> stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile).forEach(path -> copyFile(path, source, destination, overwriteExisting));
        }
    }

    private void copyFile(Path file, Path sourceRoot, Path destinationRoot, boolean overwriteExisting) {
        Path relative = sourceRoot.relativize(file);
        Path target = destinationRoot.resolve(relative.toString());
        if (!overwriteExisting && Files.exists(target)) {
            return;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log(Level.WARNING, "Failed to copy resource %s: %s", relative, e.getMessage());
        }
    }

    private int readDirectoryVersion(File folder, String versionFileName) {
        Path versionPath = folder.toPath().resolve(versionFileName);
        if (!Files.exists(versionPath)) {
            return -1;
        }
        try {
            return Integer.parseInt(Files.readString(versionPath).trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void writeDirectoryVersion(File folder, String versionFileName, int version) {
        Path versionPath = folder.toPath().resolve(versionFileName);
        try {
            Files.writeString(versionPath, Integer.toString(version));
        } catch (IOException e) {
            log(Level.WARNING, "Failed to write %s: %s", versionFileName, e.getMessage());
        }
    }

    public File getPluginFolder() {
        return pluginFolder;
    }

    public File getConfigFile() {
        return configFile;
    }

    public File getAugmentsFolder() {
        return augmentsFolder;
    }

    public File getRacesFolder() {
        return racesFolder;
    }

    public File getClassesFolder() {
        return classesFolder;
    }

    public File getPassivesFolder() {
        return passivesFolder;
    }

    public File getConquerorExampleAugmentFile() {
        return conquerorExampleAugmentFile;
    }

    public File getBerzerkerExamplePassiveFile() {
        return berzerkerExamplePassiveFile;
    }

    public File getHumanExampleRaceFile() {
        return humanExampleRaceFile;
    }

    public File getAdventurerExampleClassFile() {
        return adventurerExampleClassFile;
    }

    public boolean shouldMergeRacesWithCore() {
        return contentOptions == null || contentOptions.mergeRacesWithCore;
    }

    public boolean shouldMergeClassesWithCore() {
        return contentOptions == null || contentOptions.mergeClassesWithCore;
    }

    public boolean shouldMergeAugmentsWithCore() {
        return contentOptions == null || contentOptions.mergeAugmentsWithCore;
    }

    public boolean shouldMergePassivesWithCore() {
        return contentOptions == null || contentOptions.mergePassivesWithCore;
    }

    public boolean shouldEnableExamples() {
        return contentOptions == null || contentOptions.examplesEnabled;
    }

    public boolean shouldEnableExampleCommand() {
        return contentOptions == null || contentOptions.exampleCommandEnabled;
    }

    public boolean shouldEnableExampleEvents() {
        return contentOptions == null || contentOptions.exampleEventsEnabled;
    }

    public boolean allowDungeonReentryAfterDeath() {
        return dungeonGateOptions != null && dungeonGateOptions.allowReentryAfterDeath;
    }

    public boolean isDungeonGateEnabled() {
        return dungeonGateOptions == null || dungeonGateOptions.enabled;
    }

    public boolean isMajorDungeonContentAvailable() {
        return detectContentMarker(MAJOR_DUNGEON_CONTENT_MARKERS);
    }

    public boolean isEndgameContentAvailable() {
        return detectContentMarker(ENDGAME_CONTENT_MARKERS);
    }

    public boolean hasAnyDungeonDependencyContent() {
        return isMajorDungeonContentAvailable() || isEndgameContentAvailable();
    }

    public boolean isPortalBlockSupported(@Nullable String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        if (blockId.startsWith("EL_MajorDungeonPortal_")) {
            return isMajorDungeonContentAvailable();
        }
        if (blockId.startsWith("EL_EndgamePortal_")) {
            return isEndgameContentAvailable();
        }
        return true;
    }

    private boolean detectContentMarker(@Nonnull String[] markers) {
        if (PluginManager.MODS_PATH == null) {
            return false;
        }

        File[] entries = PluginManager.MODS_PATH.toFile().listFiles();
        if (entries == null || entries.length == 0) {
            return false;
        }

        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            for (String marker : markers) {
                if (name.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    public int getDungeonMaxConcurrentSpawns() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().maxConcurrentSpawns : dungeonGateOptions.maxConcurrentSpawns;
    }

    @Nonnull
    public List<String> getDungeonPortalWorldWhitelist() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().portalWorldWhitelist
                : dungeonGateOptions.portalWorldWhitelist;
    }

    public int getDungeonSpawnIntervalMinutes() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().spawnIntervalMinutesMax
                : dungeonGateOptions.spawnIntervalMinutesMax;
    }

    public int getDungeonSpawnIntervalMinutesMin() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().spawnIntervalMinutesMin
                : dungeonGateOptions.spawnIntervalMinutesMin;
    }

    public int getDungeonSpawnIntervalMinutesMax() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().spawnIntervalMinutesMax
                : dungeonGateOptions.spawnIntervalMinutesMax;
    }

    public int getDungeonDurationMinutes() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().gateDurationMinutes : dungeonGateOptions.gateDurationMinutes;
    }

    public boolean isDungeonAnnounceOnSpawn() {
        return dungeonGateOptions == null || dungeonGateOptions.announceOnSpawn;
    }

    public boolean isDungeonAnnounceOnDespawn() {
        return dungeonGateOptions == null || dungeonGateOptions.announceOnDespawn;
    }

    public int getDungeonMaxPlayersPerInstance() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().maxPlayersPerInstance : dungeonGateOptions.maxPlayersPerInstance;
    }

    public int getDungeonMinLevelRequired() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().minLevelRequired : dungeonGateOptions.minLevelRequired;
    }

    @Nonnull
    public String getDungeonLevelReferenceMode() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().levelReferenceMode
                : dungeonGateOptions.levelReferenceMode;
    }

    public int getDungeonLevelOffset() {
        return getDungeonLevelOffsetMax();
    }

    public int getDungeonLevelOffsetMin() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().levelOffsetMin
                : dungeonGateOptions.levelOffsetMin;
    }

    public int getDungeonLevelOffsetMax() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().levelOffsetMax
                : dungeonGateOptions.levelOffsetMax;
    }

    public int getDungeonRankFloorEMinOffset() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().rankFloorEMinOffset
                : dungeonGateOptions.rankFloorEMinOffset;
    }

    public int getDungeonRankFloorSMinOffset() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().rankFloorSMinOffset
                : dungeonGateOptions.rankFloorSMinOffset;
    }

    public boolean isDungeonAdaptiveRankFloorScalingEnabled() {
        return dungeonGateOptions == null || dungeonGateOptions.adaptiveRankFloorScalingEnabled;
    }

    public boolean isDungeonSRankPityEnabled() {
        return dungeonGateOptions == null || dungeonGateOptions.sRankPityEnabled;
    }

    public int getDungeonSRankPityMinHoursSinceLastSpawn() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().sRankPityMinHoursSinceLastSpawn
                : dungeonGateOptions.sRankPityMinHoursSinceLastSpawn;
    }

    public int getDungeonSRankPityTopPlayerCountMin() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().sRankPityTopPlayerCountMin
                : dungeonGateOptions.sRankPityTopPlayerCountMin;
    }

    public int getDungeonSRankPityTopLevelDelta() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().sRankPityTopLevelDelta
                : dungeonGateOptions.sRankPityTopLevelDelta;
    }

    public int getDungeonSRankPitySWeightMultiplier() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().sRankPitySWeightMultiplier
                : dungeonGateOptions.sRankPitySWeightMultiplier;
    }

    public boolean isDungeonLowLevelRankBiasEnabled() {
        return dungeonGateOptions == null || dungeonGateOptions.lowLevelRankBiasEnabled;
    }

    public int getDungeonLowLevelRankBiasWindowLevels() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().lowLevelRankBiasWindowLevels
                : dungeonGateOptions.lowLevelRankBiasWindowLevels;
    }

    public int getDungeonLowLevelRankBiasStrengthPercent() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().lowLevelRankBiasStrengthPercent
                : dungeonGateOptions.lowLevelRankBiasStrengthPercent;
    }

    public int getDungeonNormalMobLevelRange() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().normalMobLevelRange
                : dungeonGateOptions.normalMobLevelRange;
    }

    public int getDungeonBossLevelBonus() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().bossLevelBonus
                : dungeonGateOptions.bossLevelBonus;
    }

    /**
     * Seeds default wave pool JSON files into the waves/ folder for any rank that
     * does not yet have a config file. Existing files are never overwritten.
     */
    private void seedWavesDirectory() {
        for (GateRankTier rank : GateRankTier.values()) {
            String fileName = "wave_" + rank.letter().toLowerCase(Locale.ROOT) + ".json";
            File target = new File(wavesFolder, fileName);
            copyResourceToFile("waves/" + fileName, target, true);
        }
    }

    /**
     * Loads the wave pool configuration for the given gate rank from the waves/ directory.
     * Returns {@code null} if the file is absent, empty, or cannot be parsed.
     */
    @Nullable
    public WavePoolConfig loadWavePoolConfig(@Nonnull GateRankTier rankTier) {
        String fileName = "wave_" + rankTier.letter().toLowerCase(Locale.ROOT) + ".json";
        File waveFile = new File(wavesFolder, fileName);
        if (!waveFile.isFile()) {
            return null;
        }

        try {
            String content = Files.readString(waveFile.toPath());
            JsonObject root = GSON.fromJson(content, JsonObject.class);
            if (root == null) {
                return null;
            }

            List<WavePoolConfig.Pool> pools = new ArrayList<>();
            if (root.has("pools") && root.get("pools").isJsonArray()) {
                for (JsonElement poolElem : root.getAsJsonArray("pools")) {
                    if (!poolElem.isJsonObject()) {
                        continue;
                    }
                    JsonObject poolObj = poolElem.getAsJsonObject();
                    String id = poolObj.has("id") ? poolObj.get("id").getAsString() : "pool";
                    List<String> mobs = new ArrayList<>();
                    if (poolObj.has("mobs") && poolObj.get("mobs").isJsonArray()) {
                        for (JsonElement mobElem : poolObj.getAsJsonArray("mobs")) {
                            String mobId = mobElem.getAsString().trim();
                            if (!mobId.isBlank()) {
                                mobs.add(mobId);
                            }
                        }
                    }
                    if (!mobs.isEmpty()) {
                        pools.add(new WavePoolConfig.Pool(id, mobs));
                    }
                }
            }

            List<String> bossPool = new ArrayList<>();
            if (root.has("boss_pool") && root.get("boss_pool").isJsonArray()) {
                for (JsonElement bossElem : root.getAsJsonArray("boss_pool")) {
                    String bossId = bossElem.getAsString().trim();
                    if (!bossId.isBlank()) {
                        bossPool.add(bossId);
                    }
                }
            }

            WavePoolConfig config = new WavePoolConfig(pools, bossPool);
            return config.isEmpty() ? null : config;
        } catch (Exception exception) {
            log(Level.WARNING, "Failed to load wave config %s: %s", fileName, exception.getMessage());
            return null;
        }
    }

    public int getDungeonWaveMobCountForRank(@Nonnull GateRankTier rankTier) {
        DungeonGateOptions options = dungeonGateOptions == null ? DungeonGateOptions.defaults() : dungeonGateOptions;
        return switch (rankTier) {
            case E -> options.waveMobCountE;
            case D -> options.waveMobCountD;
            case C -> options.waveMobCountC;
            case B -> options.waveMobCountB;
            case A -> options.waveMobCountA;
            case S -> options.waveMobCountS;
        };
    }

    public int getDungeonWaveIntervalSecondsForRank(@Nonnull GateRankTier rankTier) {
        DungeonGateOptions options = dungeonGateOptions == null ? DungeonGateOptions.defaults() : dungeonGateOptions;
        return options.waveIntervalSecondsE;
    }

    public int getDungeonNaturalWaveOpenDelayMinutesForRank(@Nonnull GateRankTier rankTier) {
        DungeonGateOptions options = dungeonGateOptions == null ? DungeonGateOptions.defaults() : dungeonGateOptions;
        return switch (rankTier) {
            case E -> options.naturalWaveOpenDelayMinutesE;
            case D -> options.naturalWaveOpenDelayMinutesD;
            case C -> options.naturalWaveOpenDelayMinutesC;
            case B -> options.naturalWaveOpenDelayMinutesB;
            case A -> options.naturalWaveOpenDelayMinutesA;
            case S -> options.naturalWaveOpenDelayMinutesS;
        };
    }

    public int getDungeonNaturalWaveSpawnIntervalSecondsForRank(@Nonnull GateRankTier rankTier) {
        DungeonGateOptions options = dungeonGateOptions == null ? DungeonGateOptions.defaults() : dungeonGateOptions;
        return switch (rankTier) {
            case E -> options.naturalWaveSpawnIntervalSecondsE;
            case D -> options.naturalWaveSpawnIntervalSecondsD;
            case C -> options.naturalWaveSpawnIntervalSecondsC;
            case B -> options.naturalWaveSpawnIntervalSecondsB;
            case A -> options.naturalWaveSpawnIntervalSecondsA;
            case S -> options.naturalWaveSpawnIntervalSecondsS;
        };
    }

    public int getDungeonNaturalWaveSpawnIntervalMinutesMin() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().naturalWaveSpawnIntervalMinutesMin
                : dungeonGateOptions.naturalWaveSpawnIntervalMinutesMin;
    }

    public int getDungeonNaturalWaveSpawnIntervalMinutesMax() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().naturalWaveSpawnIntervalMinutesMax
                : dungeonGateOptions.naturalWaveSpawnIntervalMinutesMax;
    }

    public int getDungeonNaturalWaveMaxConcurrentSpawns() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().naturalWaveMaxConcurrentSpawns
                : dungeonGateOptions.naturalWaveMaxConcurrentSpawns;
    }

    @Nonnull
    public String getDungeonLevelReferenceScope() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().levelReferenceScope
                : dungeonGateOptions.levelReferenceScope;
    }

    @Nonnull
    public String getDungeonLevelPlayerScope() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().levelPlayerScope
                : dungeonGateOptions.levelPlayerScope;
    }

    @Nonnull
    public String getDungeonRankAnchorMode() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().rankAnchorMode
                : dungeonGateOptions.rankAnchorMode;
    }

    public int getDungeonLevelReferenceScopePercent() {
        return dungeonGateOptions == null
                ? DungeonGateOptions.defaults().scopePercent
                : dungeonGateOptions.scopePercent;
    }

    public int getDungeonRankWeightS() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().rankWeightS : dungeonGateOptions.rankWeightS;
    }

    public int getDungeonRankWeightA() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().rankWeightA : dungeonGateOptions.rankWeightA;
    }

    public int getDungeonRankWeightB() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().rankWeightB : dungeonGateOptions.rankWeightB;
    }

    public int getDungeonRankWeightC() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().rankWeightC : dungeonGateOptions.rankWeightC;
    }

    public int getDungeonRankWeightD() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().rankWeightD : dungeonGateOptions.rankWeightD;
    }

    public int getDungeonRankWeightE() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().rankWeightE : dungeonGateOptions.rankWeightE;
    }

    public void refreshContentOptions() {
        this.contentOptions = loadContentOptions();
        AddonLoggingManager.configure(plugin, this.contentOptions.enableLogging, this.contentOptions.loggingBaseLevel);
    }

    public void refreshDungeonGateOptions() {
        this.dungeonGateOptions = loadDungeonGateOptions();
    }

    @SuppressWarnings("unchecked")
    private DungeonGateOptions loadDungeonGateOptions() {
        DungeonGateOptions defaults = DungeonGateOptions.defaults();
        if (!dungeonGateFile.isFile()) {
            return defaults;
        }

        try (InputStream in = Files.newInputStream(dungeonGateFile.toPath())) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> rootRaw)) {
                return defaults;
            }

            Map<String, Object> root = (Map<String, Object>) rootRaw;

            boolean enabled = readBoolean(root.get("enabled"), defaults.enabled);
            boolean allowReentry = readBoolean(root.get("allow_reentry_after_death"), defaults.allowReentryAfterDeath);
            boolean announceOnSpawn = readBoolean(root.get("announce_on_spawn"), defaults.announceOnSpawn);
            boolean announceOnDespawn = readBoolean(root.get("announce_on_despawn"), defaults.announceOnDespawn);

            int maxSpawns = defaults.maxConcurrentSpawns;
            if (root.get("max_concurrent_spawns") instanceof Number n) {
                maxSpawns = n.intValue();
            }

            List<String> portalWorldWhitelist = normalizeWorldWhitelist(root.get("portal_world_whitelist"),
                    defaults.portalWorldWhitelist);

            int spawnIntervalMin = defaults.spawnIntervalMinutesMin;
            int spawnIntervalMax = defaults.spawnIntervalMinutesMax;
            if (root.get("spawn_interval_minutes_min") instanceof Number n) {
                spawnIntervalMin = Math.max(1, n.intValue());
            }
            if (root.get("spawn_interval_minutes_max") instanceof Number n) {
                spawnIntervalMax = Math.max(1, n.intValue());
            }

            // Backward compatibility for legacy single interval key.
            if (root.get("spawn_interval_minutes") instanceof Number n) {
                int legacyInterval = Math.max(1, n.intValue());
                if (!(root.get("spawn_interval_minutes_min") instanceof Number)) {
                    spawnIntervalMin = legacyInterval;
                }
                if (!(root.get("spawn_interval_minutes_max") instanceof Number)) {
                    spawnIntervalMax = legacyInterval;
                }
            }

            if (spawnIntervalMax < spawnIntervalMin) {
                spawnIntervalMax = spawnIntervalMin;
            }

            int gateDuration = defaults.gateDurationMinutes;
            if (root.get("gate_duration_minutes") instanceof Number n) {
                int raw = n.intValue();
                gateDuration = raw < 0 ? -1 : Math.max(1, raw);
            }

            int maxPlayers = defaults.maxPlayersPerInstance;
            if (root.get("max_players_per_instance") instanceof Number n) {
                maxPlayers = n.intValue();
            }

            int minLevel = defaults.minLevelRequired;
            if (root.get("min_level_required") instanceof Number n) {
                minLevel = Math.max(1, n.intValue());
            }

            String levelReferenceMode = defaults.levelReferenceMode;
            Object levelReferenceModeRaw = root.get("level_reference_mode");
            if (levelReferenceModeRaw instanceof String modeRaw && !modeRaw.isBlank()) {
                levelReferenceMode = normalizeLevelReferenceMode(modeRaw, defaults.levelReferenceMode);
            }

            String levelReferenceScope = defaults.levelReferenceScope;
            Object levelReferenceScopeRaw = root.get("level_reference_scope");
            if (levelReferenceScopeRaw instanceof String scopeRaw && !scopeRaw.isBlank()) {
                levelReferenceScope = normalizeLevelReferenceScope(scopeRaw, defaults.levelReferenceScope);
            }

            // Backward compatibility: legacy UPPER_AVERAGE maps to AVERAGE + UPPER scope.
            if (levelReferenceModeRaw instanceof String modeRaw
                    && "UPPER_AVERAGE".equalsIgnoreCase(modeRaw.trim())) {
                levelReferenceMode = "AVERAGE";
                levelReferenceScope = "UPPER";
            }

            int levelOffsetMin = defaults.levelOffsetMin;
            int levelOffsetMax = defaults.levelOffsetMax;
            if (root.get("level_offset_min") instanceof Number n) {
                levelOffsetMin = Math.max(0, n.intValue());
            }
            if (root.get("level_offset_max") instanceof Number n) {
                levelOffsetMax = Math.max(0, n.intValue());
            }

            // Backward compatibility for legacy single offset key.
            if (root.get("level_offset") instanceof Number n) {
                int legacyOffset = Math.max(0, n.intValue());
                if (!(root.get("level_offset_min") instanceof Number)) {
                    levelOffsetMin = legacyOffset;
                }
                if (!(root.get("level_offset_max") instanceof Number)) {
                    levelOffsetMax = legacyOffset;
                }
            }

            if (levelOffsetMax < levelOffsetMin) {
                levelOffsetMax = levelOffsetMin;
            }

            int rankFloorEMinOffset = defaults.rankFloorEMinOffset;
            if (root.get("rank_floor_e_min_offset") instanceof Number n) {
                rankFloorEMinOffset = Math.max(0, n.intValue());
            }

            int rankFloorSMinOffset = defaults.rankFloorSMinOffset;
            if (root.get("rank_floor_s_min_offset") instanceof Number n) {
                rankFloorSMinOffset = Math.max(0, n.intValue());
            }

            if (rankFloorSMinOffset < rankFloorEMinOffset) {
                rankFloorSMinOffset = rankFloorEMinOffset;
            }

            boolean adaptiveRankFloorScalingEnabled = readBoolean(
                    root.get("adaptive_rank_floor_scaling_enabled"),
                    defaults.adaptiveRankFloorScalingEnabled);

            boolean sRankPityEnabled = readBoolean(root.get("s_rank_pity_enabled"), defaults.sRankPityEnabled);

            int sRankPityMinHoursSinceLastSpawn = defaults.sRankPityMinHoursSinceLastSpawn;
            if (root.get("s_rank_pity_min_hours_since_last_spawn") instanceof Number n) {
                sRankPityMinHoursSinceLastSpawn = Math.max(0, n.intValue());
            }

            int sRankPityTopPlayerCountMin = defaults.sRankPityTopPlayerCountMin;
            if (root.get("s_rank_pity_top_player_count_min") instanceof Number n) {
                sRankPityTopPlayerCountMin = Math.max(1, n.intValue());
            }

            int sRankPityTopLevelDelta = defaults.sRankPityTopLevelDelta;
            if (root.get("s_rank_pity_top_level_delta") instanceof Number n) {
                sRankPityTopLevelDelta = Math.max(0, n.intValue());
            }

            int sRankPitySWeightMultiplier = defaults.sRankPitySWeightMultiplier;
            if (root.get("s_rank_pity_s_weight_multiplier") instanceof Number n) {
                sRankPitySWeightMultiplier = Math.max(1, n.intValue());
            }

            boolean lowLevelRankBiasEnabled = readBoolean(root.get("low_level_rank_bias_enabled"), defaults.lowLevelRankBiasEnabled);

            int lowLevelRankBiasWindowLevels = defaults.lowLevelRankBiasWindowLevels;
            if (root.get("low_level_rank_bias_window_levels") instanceof Number n) {
                lowLevelRankBiasWindowLevels = Math.max(1, n.intValue());
            }

            int lowLevelRankBiasStrengthPercent = defaults.lowLevelRankBiasStrengthPercent;
            if (root.get("low_level_rank_bias_strength_percent") instanceof Number n) {
                lowLevelRankBiasStrengthPercent = Math.max(0, Math.min(100, n.intValue()));
            }

            int normalMobLevelRange = defaults.normalMobLevelRange;
            if (root.get("normal_mob_level_range") instanceof Number n) {
                normalMobLevelRange = Math.max(0, n.intValue());
            }

            int bossLevelBonus = defaults.bossLevelBonus;
            if (root.get("boss_level_bonus") instanceof Number n) {
                bossLevelBonus = Math.max(0, n.intValue());
            }

            Map<String, Object> waveMobCountByRank = asMap(root.get("wave_mob_count_by_rank"));
            int waveMobCountE = readPositiveInt(waveMobCountByRank.get("E"), defaults.waveMobCountE);
            int waveMobCountD = readPositiveInt(waveMobCountByRank.get("D"), defaults.waveMobCountD);
            int waveMobCountC = readPositiveInt(waveMobCountByRank.get("C"), defaults.waveMobCountC);
            int waveMobCountB = readPositiveInt(waveMobCountByRank.get("B"), defaults.waveMobCountB);
            int waveMobCountA = readPositiveInt(waveMobCountByRank.get("A"), defaults.waveMobCountA);
            int waveMobCountS = readPositiveInt(waveMobCountByRank.get("S"), defaults.waveMobCountS);

            if (root.containsKey("wave_mob_count_e")) {
                waveMobCountE = readPositiveInt(root.get("wave_mob_count_e"), waveMobCountE);
            }
            if (root.containsKey("wave_mob_count_d")) {
                waveMobCountD = readPositiveInt(root.get("wave_mob_count_d"), waveMobCountD);
            }
            if (root.containsKey("wave_mob_count_c")) {
                waveMobCountC = readPositiveInt(root.get("wave_mob_count_c"), waveMobCountC);
            }
            if (root.containsKey("wave_mob_count_b")) {
                waveMobCountB = readPositiveInt(root.get("wave_mob_count_b"), waveMobCountB);
            }
            if (root.containsKey("wave_mob_count_a")) {
                waveMobCountA = readPositiveInt(root.get("wave_mob_count_a"), waveMobCountA);
            }
            if (root.containsKey("wave_mob_count_s")) {
                waveMobCountS = readPositiveInt(root.get("wave_mob_count_s"), waveMobCountS);
            }

            int sharedWaveIntervalSeconds = defaults.waveIntervalSecondsE;
            if (root.get("wave_interval_seconds") instanceof Number n) {
                sharedWaveIntervalSeconds = Math.max(1, n.intValue());
            }
            int waveIntervalSecondsE = sharedWaveIntervalSeconds;
            int waveIntervalSecondsD = sharedWaveIntervalSeconds;
            int waveIntervalSecondsC = sharedWaveIntervalSeconds;
            int waveIntervalSecondsB = sharedWaveIntervalSeconds;
            int waveIntervalSecondsA = sharedWaveIntervalSeconds;
            int waveIntervalSecondsS = sharedWaveIntervalSeconds;

            Map<String, Object> naturalWaveOpenDelay = asMap(root.get("natural_wave_open_delay_minutes"));
            int naturalWaveOpenDelayMinutesE = readPositiveInt(naturalWaveOpenDelay.get("E"), defaults.naturalWaveOpenDelayMinutesE);
            int naturalWaveOpenDelayMinutesD = readPositiveInt(naturalWaveOpenDelay.get("D"), defaults.naturalWaveOpenDelayMinutesD);
            int naturalWaveOpenDelayMinutesC = readPositiveInt(naturalWaveOpenDelay.get("C"), defaults.naturalWaveOpenDelayMinutesC);
            int naturalWaveOpenDelayMinutesB = readPositiveInt(naturalWaveOpenDelay.get("B"), defaults.naturalWaveOpenDelayMinutesB);
            int naturalWaveOpenDelayMinutesA = readPositiveInt(naturalWaveOpenDelay.get("A"), defaults.naturalWaveOpenDelayMinutesA);
            int naturalWaveOpenDelayMinutesS = readPositiveInt(naturalWaveOpenDelay.get("S"), defaults.naturalWaveOpenDelayMinutesS);

                int naturalWaveSpawnIntervalSecondsE = defaults.naturalWaveSpawnIntervalSecondsE;
                int naturalWaveSpawnIntervalSecondsD = defaults.naturalWaveSpawnIntervalSecondsD;
                int naturalWaveSpawnIntervalSecondsC = defaults.naturalWaveSpawnIntervalSecondsC;
                int naturalWaveSpawnIntervalSecondsB = defaults.naturalWaveSpawnIntervalSecondsB;
                int naturalWaveSpawnIntervalSecondsA = defaults.naturalWaveSpawnIntervalSecondsA;
                int naturalWaveSpawnIntervalSecondsS = defaults.naturalWaveSpawnIntervalSecondsS;

                int naturalWaveSpawnIntervalMinutesMin = defaults.naturalWaveSpawnIntervalMinutesMin;
                int naturalWaveSpawnIntervalMinutesMax = defaults.naturalWaveSpawnIntervalMinutesMax;

                if (root.get("natural_wave_spawn_interval_minutes_min") instanceof Number n) {
                naturalWaveSpawnIntervalMinutesMin = Math.max(1, n.intValue());
                }
                if (root.get("natural_wave_spawn_interval_minutes_max") instanceof Number n) {
                naturalWaveSpawnIntervalMinutesMax = Math.max(1, n.intValue());
                }

                // Legacy fallback: natural_wave_spawn_interval_seconds as map or scalar.
                if (!(root.get("natural_wave_spawn_interval_minutes_min") instanceof Number)
                        || !(root.get("natural_wave_spawn_interval_minutes_max") instanceof Number)) {
                int legacyMinSeconds = Integer.MAX_VALUE;
                int legacyMaxSeconds = Integer.MIN_VALUE;
                Map<String, Object> naturalWaveSpawnIntervalSeconds = asMap(root.get("natural_wave_spawn_interval_seconds"));
                if (!naturalWaveSpawnIntervalSeconds.isEmpty()) {
                    naturalWaveSpawnIntervalSecondsE = readPositiveInt(
                        naturalWaveSpawnIntervalSeconds.get("E"), naturalWaveSpawnIntervalSecondsE);
                    naturalWaveSpawnIntervalSecondsD = readPositiveInt(
                        naturalWaveSpawnIntervalSeconds.get("D"), naturalWaveSpawnIntervalSecondsD);
                    naturalWaveSpawnIntervalSecondsC = readPositiveInt(
                        naturalWaveSpawnIntervalSeconds.get("C"), naturalWaveSpawnIntervalSecondsC);
                    naturalWaveSpawnIntervalSecondsB = readPositiveInt(
                        naturalWaveSpawnIntervalSeconds.get("B"), naturalWaveSpawnIntervalSecondsB);
                    naturalWaveSpawnIntervalSecondsA = readPositiveInt(
                        naturalWaveSpawnIntervalSeconds.get("A"), naturalWaveSpawnIntervalSecondsA);
                    naturalWaveSpawnIntervalSecondsS = readPositiveInt(
                        naturalWaveSpawnIntervalSeconds.get("S"), naturalWaveSpawnIntervalSecondsS);

                    for (Object value : naturalWaveSpawnIntervalSeconds.values()) {
                        int parsed = readPositiveInt(value, -1);
                        if (parsed <= 0) {
                            continue;
                        }
                        legacyMinSeconds = Math.min(legacyMinSeconds, parsed);
                        legacyMaxSeconds = Math.max(legacyMaxSeconds, parsed);
                    }
                } else if (root.get("natural_wave_spawn_interval_seconds") instanceof Number n) {
                    int sharedNaturalIntervalSeconds = Math.max(1, n.intValue());
                    naturalWaveSpawnIntervalSecondsE = sharedNaturalIntervalSeconds;
                    naturalWaveSpawnIntervalSecondsD = sharedNaturalIntervalSeconds;
                    naturalWaveSpawnIntervalSecondsC = sharedNaturalIntervalSeconds;
                    naturalWaveSpawnIntervalSecondsB = sharedNaturalIntervalSeconds;
                    naturalWaveSpawnIntervalSecondsA = sharedNaturalIntervalSeconds;
                    naturalWaveSpawnIntervalSecondsS = sharedNaturalIntervalSeconds;
                    legacyMinSeconds = sharedNaturalIntervalSeconds;
                    legacyMaxSeconds = sharedNaturalIntervalSeconds;
                }

                if (legacyMinSeconds != Integer.MAX_VALUE && legacyMaxSeconds != Integer.MIN_VALUE) {
                    if (!(root.get("natural_wave_spawn_interval_minutes_min") instanceof Number)) {
                        naturalWaveSpawnIntervalMinutesMin = Math.max(1, (int) Math.ceil(legacyMinSeconds / 60.0D));
                    }
                    if (!(root.get("natural_wave_spawn_interval_minutes_max") instanceof Number)) {
                        naturalWaveSpawnIntervalMinutesMax = Math.max(1, (int) Math.ceil(legacyMaxSeconds / 60.0D));
                    }
                }
                }

                naturalWaveSpawnIntervalMinutesMax = Math.max(
                    naturalWaveSpawnIntervalMinutesMin,
                    naturalWaveSpawnIntervalMinutesMax);

                int naturalWaveMaxConcurrentSpawns = defaults.naturalWaveMaxConcurrentSpawns;
                if (root.get("natural_wave_max_concurrent_spawns") instanceof Number n) {
                int raw = n.intValue();
                naturalWaveMaxConcurrentSpawns = raw < 0 ? -1 : Math.max(1, raw);
                }

            String levelPlayerScope = defaults.levelPlayerScope;
            if (root.get("level_player_scope") instanceof String scopeRaw && !scopeRaw.isBlank()) {
                levelPlayerScope = normalizeLevelPlayerScope(scopeRaw, defaults.levelPlayerScope);
            }

            String rankAnchorMode = defaults.rankAnchorMode;
            if (root.get("rank_anchor_mode") instanceof String modeRaw && !modeRaw.isBlank()) {
                rankAnchorMode = normalizeRankAnchorMode(modeRaw, defaults.rankAnchorMode);
            }

            int scopePercent = defaults.scopePercent;
            if (root.get("scope_percent") instanceof Number n) {
                scopePercent = clampScopePercent(n.intValue());
            } else if (root.get("upper_top_percent") instanceof Number n) {
                scopePercent = clampScopePercent(n.intValue());
            } else if (root.get("upper_average_top_percent") instanceof Number n) {
                scopePercent = clampScopePercent(n.intValue());
            }

            Map<String, Object> rankWeights = asMap(root.get("rank_weights"));
            int rankWeightS = readRankWeight(rankWeights.get("S"), defaults.rankWeightS);
            int rankWeightA = readRankWeight(rankWeights.get("A"), defaults.rankWeightA);
            int rankWeightB = readRankWeight(rankWeights.get("B"), defaults.rankWeightB);
            int rankWeightC = readRankWeight(rankWeights.get("C"), defaults.rankWeightC);
            int rankWeightD = readRankWeight(rankWeights.get("D"), defaults.rankWeightD);
            int rankWeightE = readRankWeight(rankWeights.get("E"), defaults.rankWeightE);

            // Backward compatibility for flat keys.
            if (root.containsKey("rank_weight_s")) {
                rankWeightS = readRankWeight(root.get("rank_weight_s"), rankWeightS);
            }
            if (root.containsKey("rank_weight_a")) {
                rankWeightA = readRankWeight(root.get("rank_weight_a"), rankWeightA);
            }
            if (root.containsKey("rank_weight_b")) {
                rankWeightB = readRankWeight(root.get("rank_weight_b"), rankWeightB);
            }
            if (root.containsKey("rank_weight_c")) {
                rankWeightC = readRankWeight(root.get("rank_weight_c"), rankWeightC);
            }
            if (root.containsKey("rank_weight_d")) {
                rankWeightD = readRankWeight(root.get("rank_weight_d"), rankWeightD);
            }
            if (root.containsKey("rank_weight_e")) {
                rankWeightE = readRankWeight(root.get("rank_weight_e"), rankWeightE);
            }

                return new DungeonGateOptions(enabled, allowReentry, announceOnSpawn, announceOnDespawn,
                    maxSpawns, spawnIntervalMin, spawnIntervalMax, gateDuration, maxPlayers, minLevel,
                    portalWorldWhitelist,
                    levelReferenceMode, levelReferenceScope, levelOffsetMin, levelOffsetMax,
                    rankFloorEMinOffset, rankFloorSMinOffset,
                    adaptiveRankFloorScalingEnabled,
                    sRankPityEnabled, sRankPityMinHoursSinceLastSpawn, sRankPityTopPlayerCountMin,
                    sRankPityTopLevelDelta, sRankPitySWeightMultiplier,
                    lowLevelRankBiasEnabled, lowLevelRankBiasWindowLevels, lowLevelRankBiasStrengthPercent,
                    normalMobLevelRange, bossLevelBonus, scopePercent, levelPlayerScope, rankAnchorMode,
                    rankWeightS, rankWeightA, rankWeightB, rankWeightC, rankWeightD, rankWeightE,
                    waveMobCountE, waveMobCountD, waveMobCountC, waveMobCountB, waveMobCountA, waveMobCountS,
                    waveIntervalSecondsE, waveIntervalSecondsD, waveIntervalSecondsC,
                    waveIntervalSecondsB, waveIntervalSecondsA, waveIntervalSecondsS,
                    naturalWaveOpenDelayMinutesE, naturalWaveOpenDelayMinutesD, naturalWaveOpenDelayMinutesC,
                    naturalWaveOpenDelayMinutesB, naturalWaveOpenDelayMinutesA, naturalWaveOpenDelayMinutesS,
                    naturalWaveSpawnIntervalMinutesMin, naturalWaveSpawnIntervalMinutesMax,
                    naturalWaveSpawnIntervalSecondsE, naturalWaveSpawnIntervalSecondsD,
                    naturalWaveSpawnIntervalSecondsC, naturalWaveSpawnIntervalSecondsB,
                    naturalWaveSpawnIntervalSecondsA, naturalWaveSpawnIntervalSecondsS,
                    naturalWaveMaxConcurrentSpawns);
        } catch (IOException ignored) {
            return defaults;
        }
    }

    @Nonnull
    private static String normalizeLevelReferenceMode(@Nonnull String rawMode, @Nonnull String fallback) {
        String normalized = rawMode.trim().toUpperCase(Locale.ROOT);
        if ("HIGHEST".equals(normalized)
                || "MEDIAN".equals(normalized)
                || "AVERAGE".equals(normalized)) {
            return normalized;
        }
        return fallback;
    }

    @Nonnull
    private static String normalizeLevelReferenceScope(@Nonnull String rawScope, @Nonnull String fallback) {
        String normalized = rawScope.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized) || "UPPER".equals(normalized) || "LOWER".equals(normalized)) {
            return normalized;
        }
        return fallback;
    }

    @Nonnull
    private static String normalizeLevelPlayerScope(@Nonnull String rawScope, @Nonnull String fallback) {
        String normalized = rawScope.trim().toUpperCase(Locale.ROOT);
        if ("ONLINE".equals(normalized) || "OVERALL".equals(normalized)) {
            return normalized;
        }
        return fallback;
    }

    @Nonnull
    private static String normalizeRankAnchorMode(@Nonnull String raw, @Nonnull String fallback) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("LOWEST_MOB".equals(normalized) || "HIGHEST_MOB".equals(normalized) || "BOSS".equals(normalized)) {
            return normalized;
        }
        return fallback;
    }

    private static int clampScopePercent(int value) {
        return Math.max(1, Math.min(100, value));
    }

    @Nonnull
    private static List<String> normalizeWorldWhitelist(Object rawValue, @Nonnull List<String> fallback) {
        if (!(rawValue instanceof List<?> rawList)) {
            return fallback;
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (Object entry : rawList) {
            if (entry == null) {
                continue;
            }
            String text = String.valueOf(entry).trim();
            if (!text.isEmpty()) {
                normalized.add(text);
            }
        }

        if (normalized.isEmpty()) {
            return fallback;
        }
        return List.copyOf(normalized);
    }

    private static int readRankWeight(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int readPositiveInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @Nonnull
    private static Level parseLogLevel(Object raw, @Nonnull Level fallback) {
        if (raw == null) {
            return fallback;
        }

        String text = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        return switch (text) {
            case "SEVERE", "ERROR" -> Level.SEVERE;
            case "WARNING", "WARN" -> Level.WARNING;
            case "INFO" -> Level.INFO;
            case "FINE", "DEBUG" -> Level.FINE;
            default -> fallback;
        };
    }

    private static void log(@Nonnull Level level, @Nonnull String message, Object... args) {
        AddonLoggingManager.log(LOGGER, level, message, args);
    }

    private static final class DungeonGateOptions {
        private final boolean enabled;
        private final boolean allowReentryAfterDeath;
        private final boolean announceOnSpawn;
        private final boolean announceOnDespawn;
        private final int maxConcurrentSpawns;
        private final int spawnIntervalMinutesMin;
        private final int spawnIntervalMinutesMax;
        private final int gateDurationMinutes;
        private final int maxPlayersPerInstance;
        private final int minLevelRequired;
        private final List<String> portalWorldWhitelist;
        private final String levelReferenceMode;
        private final String levelReferenceScope;
        private final int levelOffsetMin;
        private final int levelOffsetMax;
        private final int rankFloorEMinOffset;
        private final int rankFloorSMinOffset;
        private final boolean adaptiveRankFloorScalingEnabled;
        private final boolean sRankPityEnabled;
        private final int sRankPityMinHoursSinceLastSpawn;
        private final int sRankPityTopPlayerCountMin;
        private final int sRankPityTopLevelDelta;
        private final int sRankPitySWeightMultiplier;
        private final boolean lowLevelRankBiasEnabled;
        private final int lowLevelRankBiasWindowLevels;
        private final int lowLevelRankBiasStrengthPercent;
        private final int normalMobLevelRange;
        private final int bossLevelBonus;
        private final int scopePercent;
        private final String levelPlayerScope;
        private final String rankAnchorMode;
        private final int rankWeightS;
        private final int rankWeightA;
        private final int rankWeightB;
        private final int rankWeightC;
        private final int rankWeightD;
        private final int rankWeightE;
        private final int waveMobCountE;
        private final int waveMobCountD;
        private final int waveMobCountC;
        private final int waveMobCountB;
        private final int waveMobCountA;
        private final int waveMobCountS;
        private final int waveIntervalSecondsE;
        private final int waveIntervalSecondsD;
        private final int waveIntervalSecondsC;
        private final int waveIntervalSecondsB;
        private final int waveIntervalSecondsA;
        private final int waveIntervalSecondsS;
        private final int naturalWaveOpenDelayMinutesE;
        private final int naturalWaveOpenDelayMinutesD;
        private final int naturalWaveOpenDelayMinutesC;
        private final int naturalWaveOpenDelayMinutesB;
        private final int naturalWaveOpenDelayMinutesA;
        private final int naturalWaveOpenDelayMinutesS;
        private final int naturalWaveSpawnIntervalMinutesMin;
        private final int naturalWaveSpawnIntervalMinutesMax;
        private final int naturalWaveSpawnIntervalSecondsE;
        private final int naturalWaveSpawnIntervalSecondsD;
        private final int naturalWaveSpawnIntervalSecondsC;
        private final int naturalWaveSpawnIntervalSecondsB;
        private final int naturalWaveSpawnIntervalSecondsA;
        private final int naturalWaveSpawnIntervalSecondsS;
        private final int naturalWaveMaxConcurrentSpawns;

        private DungeonGateOptions(boolean enabled,
                boolean allowReentryAfterDeath,
                boolean announceOnSpawn,
            boolean announceOnDespawn,
                int maxConcurrentSpawns,
                int spawnIntervalMinutesMin,
                int spawnIntervalMinutesMax,
                int gateDurationMinutes,
                int maxPlayersPerInstance,
                int minLevelRequired,
                @Nonnull List<String> portalWorldWhitelist,
                @Nonnull String levelReferenceMode,
                @Nonnull String levelReferenceScope,
                int levelOffsetMin,
                int levelOffsetMax,
                int rankFloorEMinOffset,
                int rankFloorSMinOffset,
                boolean adaptiveRankFloorScalingEnabled,
                boolean sRankPityEnabled,
                int sRankPityMinHoursSinceLastSpawn,
                int sRankPityTopPlayerCountMin,
                int sRankPityTopLevelDelta,
                int sRankPitySWeightMultiplier,
                boolean lowLevelRankBiasEnabled,
                int lowLevelRankBiasWindowLevels,
                int lowLevelRankBiasStrengthPercent,
                int normalMobLevelRange,
                int bossLevelBonus,
                int scopePercent,
                @Nonnull String levelPlayerScope,
                @Nonnull String rankAnchorMode,
                int rankWeightS,
                int rankWeightA,
                int rankWeightB,
                int rankWeightC,
                int rankWeightD,
                int rankWeightE,
                int waveMobCountE,
                int waveMobCountD,
                int waveMobCountC,
                int waveMobCountB,
                int waveMobCountA,
                int waveMobCountS,
                int waveIntervalSecondsE,
                int waveIntervalSecondsD,
                int waveIntervalSecondsC,
                int waveIntervalSecondsB,
                int waveIntervalSecondsA,
                int waveIntervalSecondsS,
                int naturalWaveOpenDelayMinutesE,
                int naturalWaveOpenDelayMinutesD,
                int naturalWaveOpenDelayMinutesC,
                int naturalWaveOpenDelayMinutesB,
                int naturalWaveOpenDelayMinutesA,
                int naturalWaveOpenDelayMinutesS,
                int naturalWaveSpawnIntervalMinutesMin,
                int naturalWaveSpawnIntervalMinutesMax,
                int naturalWaveSpawnIntervalSecondsE,
                int naturalWaveSpawnIntervalSecondsD,
                int naturalWaveSpawnIntervalSecondsC,
                int naturalWaveSpawnIntervalSecondsB,
                int naturalWaveSpawnIntervalSecondsA,
                int naturalWaveSpawnIntervalSecondsS,
                int naturalWaveMaxConcurrentSpawns) {
            this.enabled = enabled;
            this.allowReentryAfterDeath = allowReentryAfterDeath;
            this.announceOnSpawn = announceOnSpawn;
            this.announceOnDespawn = announceOnDespawn;
            this.maxConcurrentSpawns = maxConcurrentSpawns;
            this.spawnIntervalMinutesMin = Math.max(1, spawnIntervalMinutesMin);
            this.spawnIntervalMinutesMax = Math.max(this.spawnIntervalMinutesMin, spawnIntervalMinutesMax);
            this.gateDurationMinutes = gateDurationMinutes;
            this.maxPlayersPerInstance = maxPlayersPerInstance;
            this.minLevelRequired = minLevelRequired;
            this.portalWorldWhitelist = List.copyOf(portalWorldWhitelist);
            this.levelReferenceMode = levelReferenceMode;
            this.levelReferenceScope = levelReferenceScope;
            this.levelOffsetMin = Math.max(0, levelOffsetMin);
            this.levelOffsetMax = Math.max(this.levelOffsetMin, levelOffsetMax);
            this.rankFloorEMinOffset = Math.max(0, rankFloorEMinOffset);
            this.rankFloorSMinOffset = Math.max(this.rankFloorEMinOffset, rankFloorSMinOffset);
            this.adaptiveRankFloorScalingEnabled = adaptiveRankFloorScalingEnabled;
            this.sRankPityEnabled = sRankPityEnabled;
            this.sRankPityMinHoursSinceLastSpawn = Math.max(0, sRankPityMinHoursSinceLastSpawn);
            this.sRankPityTopPlayerCountMin = Math.max(1, sRankPityTopPlayerCountMin);
            this.sRankPityTopLevelDelta = Math.max(0, sRankPityTopLevelDelta);
            this.sRankPitySWeightMultiplier = Math.max(1, sRankPitySWeightMultiplier);
            this.lowLevelRankBiasEnabled = lowLevelRankBiasEnabled;
            this.lowLevelRankBiasWindowLevels = Math.max(1, lowLevelRankBiasWindowLevels);
            this.lowLevelRankBiasStrengthPercent = Math.max(0, Math.min(100, lowLevelRankBiasStrengthPercent));
            this.normalMobLevelRange = Math.max(0, normalMobLevelRange);
            this.bossLevelBonus = Math.max(0, bossLevelBonus);
            this.scopePercent = clampScopePercent(scopePercent);
            this.levelPlayerScope = levelPlayerScope;
            this.rankAnchorMode = rankAnchorMode;
            this.rankWeightS = Math.max(0, rankWeightS);
            this.rankWeightA = Math.max(0, rankWeightA);
            this.rankWeightB = Math.max(0, rankWeightB);
            this.rankWeightC = Math.max(0, rankWeightC);
            this.rankWeightD = Math.max(0, rankWeightD);
            this.rankWeightE = Math.max(0, rankWeightE);
            this.waveMobCountE = Math.max(1, waveMobCountE);
            this.waveMobCountD = Math.max(1, waveMobCountD);
            this.waveMobCountC = Math.max(1, waveMobCountC);
            this.waveMobCountB = Math.max(1, waveMobCountB);
            this.waveMobCountA = Math.max(1, waveMobCountA);
            this.waveMobCountS = Math.max(1, waveMobCountS);
            this.waveIntervalSecondsE = Math.max(1, waveIntervalSecondsE);
            this.waveIntervalSecondsD = Math.max(1, waveIntervalSecondsD);
            this.waveIntervalSecondsC = Math.max(1, waveIntervalSecondsC);
            this.waveIntervalSecondsB = Math.max(1, waveIntervalSecondsB);
            this.waveIntervalSecondsA = Math.max(1, waveIntervalSecondsA);
            this.waveIntervalSecondsS = Math.max(1, waveIntervalSecondsS);
            this.naturalWaveOpenDelayMinutesE = Math.max(1, naturalWaveOpenDelayMinutesE);
            this.naturalWaveOpenDelayMinutesD = Math.max(1, naturalWaveOpenDelayMinutesD);
            this.naturalWaveOpenDelayMinutesC = Math.max(1, naturalWaveOpenDelayMinutesC);
            this.naturalWaveOpenDelayMinutesB = Math.max(1, naturalWaveOpenDelayMinutesB);
            this.naturalWaveOpenDelayMinutesA = Math.max(1, naturalWaveOpenDelayMinutesA);
            this.naturalWaveOpenDelayMinutesS = Math.max(1, naturalWaveOpenDelayMinutesS);
                this.naturalWaveSpawnIntervalMinutesMin = Math.max(1, naturalWaveSpawnIntervalMinutesMin);
                this.naturalWaveSpawnIntervalMinutesMax = Math.max(
                    this.naturalWaveSpawnIntervalMinutesMin,
                    naturalWaveSpawnIntervalMinutesMax);
                this.naturalWaveSpawnIntervalSecondsE = Math.max(1, naturalWaveSpawnIntervalSecondsE);
                this.naturalWaveSpawnIntervalSecondsD = Math.max(1, naturalWaveSpawnIntervalSecondsD);
                this.naturalWaveSpawnIntervalSecondsC = Math.max(1, naturalWaveSpawnIntervalSecondsC);
                this.naturalWaveSpawnIntervalSecondsB = Math.max(1, naturalWaveSpawnIntervalSecondsB);
                this.naturalWaveSpawnIntervalSecondsA = Math.max(1, naturalWaveSpawnIntervalSecondsA);
                this.naturalWaveSpawnIntervalSecondsS = Math.max(1, naturalWaveSpawnIntervalSecondsS);
                this.naturalWaveMaxConcurrentSpawns = naturalWaveMaxConcurrentSpawns < 0
                    ? -1
                    : Math.max(1, naturalWaveMaxConcurrentSpawns);
        }

        private static DungeonGateOptions defaults() {
                return new DungeonGateOptions(true, false, true, true, 3, 30, 30, 30, -1, 1,
                    List.of("world", "default"),
                    "AVERAGE", "UPPER", 0, 30, 10, 110,
                    true,
                    true, 12, 3, 10, 8,
                    true, 80, 80,
                    20, 10, 25, "ONLINE", "HIGHEST_MOB",
                    1, 6, 13, 30, 25, 25,
                    5, 10, 20, 40, 80, 160,
                    5, 5, 5, 5, 5, 5,
                    1, 1, 1, 1, 5, 10,
                    15, 30,
                    15, 30, 45, 60, 120, 300,
                    3);
        }
    }

    private static final class AddonContentOptions {
        private final boolean mergeRacesWithCore;
        private final boolean mergeClassesWithCore;
        private final boolean mergeAugmentsWithCore;
        private final boolean mergePassivesWithCore;
        private final boolean examplesEnabled;
        private final boolean exampleCommandEnabled;
        private final boolean exampleEventsEnabled;
        private final boolean enableLogging;
        private final Level loggingBaseLevel;

        private AddonContentOptions(boolean mergeRacesWithCore,
                boolean mergeClassesWithCore,
                boolean mergeAugmentsWithCore,
                boolean mergePassivesWithCore,
                boolean examplesEnabled,
                boolean exampleCommandEnabled,
                boolean exampleEventsEnabled,
                boolean enableLogging,
                @Nonnull Level loggingBaseLevel) {
            this.mergeRacesWithCore = mergeRacesWithCore;
            this.mergeClassesWithCore = mergeClassesWithCore;
            this.mergeAugmentsWithCore = mergeAugmentsWithCore;
            this.mergePassivesWithCore = mergePassivesWithCore;
            this.examplesEnabled = examplesEnabled;
            this.exampleCommandEnabled = exampleCommandEnabled;
            this.exampleEventsEnabled = exampleEventsEnabled;
            this.enableLogging = enableLogging;
            this.loggingBaseLevel = loggingBaseLevel;
        }

        private static AddonContentOptions defaults() {
            return new AddonContentOptions(true, true, true, true, true, true, true, false, Level.WARNING);
        }
    }
}

