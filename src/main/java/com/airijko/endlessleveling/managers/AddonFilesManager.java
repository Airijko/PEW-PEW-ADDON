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
import java.util.Comparator;
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
    private final File conquerorExampleAugmentFile;
    private final File berzerkerExamplePassiveFile;
    private final File humanExampleRaceFile;
    private final File adventurerExampleClassFile;
    private final Object archiveLock = new Object();

    private AddonContentOptions contentOptions;
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
        this.conquerorExampleAugmentFile = new File(augmentsFolder, "conqueror_example.yml");
        this.berzerkerExamplePassiveFile = new File(passivesFolder, "berzerker_example.yml");
        this.humanExampleRaceFile = new File(racesFolder, "human_example.yml");
        this.adventurerExampleClassFile = new File(classesFolder, "adventurer_exmaple.yml");

        initialize();
    }

    private void initialize() {
        createFolders();
        initYamlFile("config.yml");
        this.contentOptions = loadContentOptions();
        syncConfigIfNeeded();

        syncDirectoryIfNeeded(
                "races",
                racesFolder,
                AddonVersionRegistry.RACES_VERSION_FILE,
                AddonVersionRegistry.BUILTIN_RACES_VERSION,
                contentOptions.mergeRacesWithCore,
                contentOptions.forceBuiltinRaces,
                contentOptions.seedAddonExamplesOnStartup);

        syncDirectoryIfNeeded(
                "classes",
                classesFolder,
                AddonVersionRegistry.CLASSES_VERSION_FILE,
                AddonVersionRegistry.BUILTIN_CLASSES_VERSION,
                contentOptions.mergeClassesWithCore,
                contentOptions.forceBuiltinClasses,
                contentOptions.seedAddonExamplesOnStartup);

        syncDirectoryIfNeeded(
                "augments",
                augmentsFolder,
                AddonVersionRegistry.AUGMENTS_VERSION_FILE,
                AddonVersionRegistry.BUILTIN_AUGMENTS_VERSION,
                contentOptions.mergeAugmentsWithCore,
                contentOptions.forceBuiltinAugments,
                contentOptions.seedAddonExamplesOnStartup);

        syncDirectoryIfNeeded(
                "passives",
                passivesFolder,
                AddonVersionRegistry.PASSIVES_VERSION_FILE,
                AddonVersionRegistry.BUILTIN_PASSIVES_VERSION,
                contentOptions.mergePassivesWithCore,
                contentOptions.forceBuiltinPassives,
                contentOptions.seedAddonExamplesOnStartup);
    }

    private void syncConfigIfNeeded() {
        if (!contentOptions.forceBuiltinConfig) {
            return;
        }
        int storedVersion = readConfigVersion(configFile);
        if (storedVersion == AddonVersionRegistry.CONFIG_YML_VERSION) {
            return;
        }

        archiveFileIfExists(configFile, "config.yml", "config_version:" + storedVersion);
        copyResourceToFile("config.yml", configFile, true);
        try {
            ensureConfigVersionMarkerOnCreate("config.yml", configFile);
        } catch (IOException exception) {
            LOGGER.atWarning().log("Failed to append config version marker: %s", exception.getMessage());
        }
        LOGGER.atInfo().log("Synced built-in config.yml to version %d (force_builtin_config=true)",
                AddonVersionRegistry.CONFIG_YML_VERSION);
        this.contentOptions = loadContentOptions();
    }

    private void syncDirectoryIfNeeded(String resourceRoot,
            File destination,
            String versionFileName,
            int targetVersion,
            boolean mergeEnabled,
            boolean forceBuiltin,
            boolean allowSeed) {
        if (!mergeEnabled || destination == null) {
            return;
        }

        if (forceBuiltin) {
            int storedVersion = readDirectoryVersion(destination, versionFileName);
            if (storedVersion == targetVersion) {
                return;
            }
            archivePathIfExists(destination.toPath(), resourceRoot, versionFileName + ":" + storedVersion);
            clearDirectory(destination.toPath());
            exportResourceDirectory(resourceRoot, destination, true);
            writeDirectoryVersion(destination, versionFileName, targetVersion);
            LOGGER.atInfo().log("Synced built-in %s to version %d", resourceRoot, targetVersion);
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

            boolean forceConfig = readBoolean(sync.get("force_builtin_config"),
                    readBoolean(root.get("force_builtin_config"), false));
            boolean forceRaces = readBoolean(sync.get("force_builtin_races"),
                    readBoolean(root.get("force_builtin_races"), false));
            boolean forceClasses = readBoolean(sync.get("force_builtin_classes"),
                    readBoolean(root.get("force_builtin_classes"), false));
            boolean forceAugments = readBoolean(sync.get("force_builtin_augments"),
                    readBoolean(root.get("force_builtin_augments"), false));
            boolean forcePassives = readBoolean(sync.get("force_builtin_passives"),
                    readBoolean(root.get("force_builtin_passives"), false));

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

            boolean seedExamples = readBoolean(sync.get("seed_addon_examples_on_startup"),
                    readBoolean(sync.get("seed_defaults_on_startup"), true));

            return new AddonContentOptions(
                    mergeRaces,
                    mergeClasses,
                    mergeAugments,
                    mergePassives,
                    seedExamples,
                    forceConfig,
                    forceRaces,
                    forceClasses,
                    forceAugments,
                    forcePassives);
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

    private void clearDirectory(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOGGER.atWarning().log("Failed to delete %s: %s", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to clear %s: %s", root, e.getMessage());
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

    private static final class AddonContentOptions {
        private final boolean mergeRacesWithCore;
        private final boolean mergeClassesWithCore;
        private final boolean mergeAugmentsWithCore;
        private final boolean mergePassivesWithCore;
        private final boolean seedAddonExamplesOnStartup;
        private final boolean forceBuiltinConfig;
        private final boolean forceBuiltinRaces;
        private final boolean forceBuiltinClasses;
        private final boolean forceBuiltinAugments;
        private final boolean forceBuiltinPassives;

        private AddonContentOptions(boolean mergeRacesWithCore,
                boolean mergeClassesWithCore,
                boolean mergeAugmentsWithCore,
                boolean mergePassivesWithCore,
                boolean seedAddonExamplesOnStartup,
                boolean forceBuiltinConfig,
                boolean forceBuiltinRaces,
                boolean forceBuiltinClasses,
                boolean forceBuiltinAugments,
                boolean forceBuiltinPassives) {
            this.mergeRacesWithCore = mergeRacesWithCore;
            this.mergeClassesWithCore = mergeClassesWithCore;
            this.mergeAugmentsWithCore = mergeAugmentsWithCore;
            this.mergePassivesWithCore = mergePassivesWithCore;
            this.seedAddonExamplesOnStartup = seedAddonExamplesOnStartup;
            this.forceBuiltinConfig = forceBuiltinConfig;
            this.forceBuiltinRaces = forceBuiltinRaces;
            this.forceBuiltinClasses = forceBuiltinClasses;
            this.forceBuiltinAugments = forceBuiltinAugments;
            this.forceBuiltinPassives = forceBuiltinPassives;
        }

        private static AddonContentOptions defaults() {
            return new AddonContentOptions(true, true, true, true, true, false, false, false, false, false);
        }
    }
}

