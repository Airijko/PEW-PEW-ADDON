package com.airijko.endlessleveling.managers;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Minimal file bootstrap matching Endless Leveling's resource-first startup flow.
 */
public final class AddonFilesManager {

    private static final String PLUGIN_FOLDER_NAME = "EndlessLevelingAddon";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern MANIFEST_VERSION_PATTERN = Pattern.compile("\"Version\"\\s*:\\s*\"([^\"]+)\"");
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final JavaPlugin plugin;
    private final File pluginFolder;
    private final File racesFolder;
    private final File classesFolder;
    private final File augmentsFolder;
    private final File passivesFolder;
    private final File configFile;
    private final File dungeonGateFile;
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
        this.contentOptions = loadContentOptions();
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

        LOGGER.atInfo().log("Migrated config.yml to version %d and normalized schema", targetVersion);

        try {
            ensureConfigVersionMarkerOnCreate("config.yml", configFile);
        } catch (IOException exception) {
            LOGGER.atWarning().log("Failed to append config version marker: %s", exception.getMessage());
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

        LOGGER.atInfo().log("Migrated dungeongate.yml to version %d and normalized schema", targetVersion);

        try {
            ensureConfigVersionMarkerOnCreate("dungeongate.yml", dungeonGateFile);
        } catch (IOException exception) {
            LOGGER.atWarning().log("Failed to append dungeongate config version marker: %s", exception.getMessage());
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
        text.append("# Minimum enforced value: 1.\n");
        text.append("spawn_interval_minutes: ").append(options.spawnIntervalMinutes).append("\n\n");

        text.append("# Maximum number of dungeon gate instances that may be active at the same time.\n");
        text.append("# Set to -1 for no limit.\n");
        text.append("max_concurrent_spawns: ").append(options.maxConcurrentSpawns).append("\n\n");

        text.append("# Announce in global chat when a dungeon gate spawns.\n");
        text.append("announce_on_spawn: ").append(options.announceOnSpawn).append("\n\n");

        text.append("# -----------------------------------------------------------------------\n");
        text.append("# Gate lifetime\n");
        text.append("# -----------------------------------------------------------------------\n\n");

        text.append("# How long (in minutes) an open gate stays active before it closes on its own.\n");
        text.append("# Set to -1 to never auto-close.\n");
        text.append("gate_duration_minutes: ").append(options.gateDurationMinutes).append("\n\n");

        text.append("# Close the gate early if all players have left the instance.\n");
        text.append("despawn_when_empty: ").append(options.despawnWhenEmpty).append("\n\n");

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
            LOGGER.atInfo().log("Migrated %s from version %d to %d (non-destructive)",
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

            return new AddonContentOptions(
                    mergeRaces,
                    mergeClasses,
                    mergeAugments,
                    mergePassives,
                    enableExamples,
                    enableExampleCommand,
                    enableExampleEvents);
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
            LOGGER.atInfo().log("YAML file %s created at %s", resourceName, yamlFile.getAbsolutePath());
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
                LOGGER.atWarning().log("Resource %s not found in addon JAR", resourcePath);
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
                LOGGER.atInfo().log("Archived %s to %s", sourcePath, targetPath);
                return targetPath;
            } catch (IOException e) {
                LOGGER.atWarning().log("Failed to archive %s: %s", sourcePath, e.getMessage());
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
                LOGGER.atWarning().log("Unable to locate code source while exporting %s", resourceRoot);
                return;
            }

            Path sourcePath = Paths.get(codeSource.getLocation().toURI());
            if (Files.isDirectory(sourcePath)) {
                Path resourcePath = sourcePath.resolve(resourceRoot);
                if (!Files.exists(resourcePath)) {
                    LOGGER.atWarning().log("Resource directory %s not found under %s", resourceRoot, sourcePath);
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
            LOGGER.atWarning().log("Failed to export resource directory %s: %s", resourceRoot, e.getMessage());
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
            LOGGER.atWarning().log("Failed to inspect %s for YAML files: %s", folder, e.getMessage());
            return false;
        }
    }

    private void copyDirectory(Path source, Path destination, boolean overwriteExisting) throws IOException {
        if (!Files.exists(source)) {
            LOGGER.atWarning().log("Source directory %s does not exist when exporting resources.", source);
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
            LOGGER.atWarning().log("Failed to copy resource %s: %s", relative, e.getMessage());
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
            LOGGER.atWarning().log("Failed to write %s: %s", versionFileName, e.getMessage());
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

    public int getDungeonMaxConcurrentSpawns() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().maxConcurrentSpawns : dungeonGateOptions.maxConcurrentSpawns;
    }

    public int getDungeonSpawnIntervalMinutes() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().spawnIntervalMinutes : dungeonGateOptions.spawnIntervalMinutes;
    }

    public int getDungeonDurationMinutes() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().gateDurationMinutes : dungeonGateOptions.gateDurationMinutes;
    }

    public boolean isDungeonAnnounceOnSpawn() {
        return dungeonGateOptions == null || dungeonGateOptions.announceOnSpawn;
    }

    public boolean isDungeonDespawnWhenEmpty() {
        return dungeonGateOptions != null && dungeonGateOptions.despawnWhenEmpty;
    }

    public int getDungeonMaxPlayersPerInstance() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().maxPlayersPerInstance : dungeonGateOptions.maxPlayersPerInstance;
    }

    public int getDungeonMinLevelRequired() {
        return dungeonGateOptions == null ? DungeonGateOptions.defaults().minLevelRequired : dungeonGateOptions.minLevelRequired;
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
            boolean despawnWhenEmpty = readBoolean(root.get("despawn_when_empty"), defaults.despawnWhenEmpty);

            int maxSpawns = defaults.maxConcurrentSpawns;
            if (root.get("max_concurrent_spawns") instanceof Number n) {
                maxSpawns = n.intValue();
            }

            int spawnInterval = defaults.spawnIntervalMinutes;
            if (root.get("spawn_interval_minutes") instanceof Number n) {
                spawnInterval = Math.max(1, n.intValue());
            }

            int gateDuration = defaults.gateDurationMinutes;
            if (root.get("gate_duration_minutes") instanceof Number n) {
                gateDuration = n.intValue();
            }

            int maxPlayers = defaults.maxPlayersPerInstance;
            if (root.get("max_players_per_instance") instanceof Number n) {
                maxPlayers = n.intValue();
            }

            int minLevel = defaults.minLevelRequired;
            if (root.get("min_level_required") instanceof Number n) {
                minLevel = Math.max(1, n.intValue());
            }

            return new DungeonGateOptions(enabled, allowReentry, announceOnSpawn, despawnWhenEmpty,
                    maxSpawns, spawnInterval, gateDuration, maxPlayers, minLevel);
        } catch (IOException ignored) {
            return defaults;
        }
    }

    private static final class DungeonGateOptions {
        private final boolean enabled;
        private final boolean allowReentryAfterDeath;
        private final boolean announceOnSpawn;
        private final boolean despawnWhenEmpty;
        private final int maxConcurrentSpawns;
        private final int spawnIntervalMinutes;
        private final int gateDurationMinutes;
        private final int maxPlayersPerInstance;
        private final int minLevelRequired;

        private DungeonGateOptions(boolean enabled,
                boolean allowReentryAfterDeath,
                boolean announceOnSpawn,
                boolean despawnWhenEmpty,
                int maxConcurrentSpawns,
                int spawnIntervalMinutes,
                int gateDurationMinutes,
                int maxPlayersPerInstance,
                int minLevelRequired) {
            this.enabled = enabled;
            this.allowReentryAfterDeath = allowReentryAfterDeath;
            this.announceOnSpawn = announceOnSpawn;
            this.despawnWhenEmpty = despawnWhenEmpty;
            this.maxConcurrentSpawns = maxConcurrentSpawns;
            this.spawnIntervalMinutes = spawnIntervalMinutes;
            this.gateDurationMinutes = gateDurationMinutes;
            this.maxPlayersPerInstance = maxPlayersPerInstance;
            this.minLevelRequired = minLevelRequired;
        }

        private static DungeonGateOptions defaults() {
            return new DungeonGateOptions(true, false, true, false, 3, 30, 30, -1, 1);
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

        private AddonContentOptions(boolean mergeRacesWithCore,
                boolean mergeClassesWithCore,
                boolean mergeAugmentsWithCore,
                boolean mergePassivesWithCore,
                boolean examplesEnabled,
                boolean exampleCommandEnabled,
                boolean exampleEventsEnabled) {
            this.mergeRacesWithCore = mergeRacesWithCore;
            this.mergeClassesWithCore = mergeClassesWithCore;
            this.mergeAugmentsWithCore = mergeAugmentsWithCore;
            this.mergePassivesWithCore = mergePassivesWithCore;
            this.examplesEnabled = examplesEnabled;
            this.exampleCommandEnabled = exampleCommandEnabled;
            this.exampleEventsEnabled = exampleEventsEnabled;
        }

        private static AddonContentOptions defaults() {
            return new AddonContentOptions(true, true, true, true, true, true, true);
        }
    }
}

