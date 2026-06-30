package com.takesome.springsuite.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

public final class ExternalSuiteConfigBootstrap {
    private static final String BASE_CONFIG_RESOURCE = "suite-base-default.yml";
    private static final String BASE_CONFIG_FILE = "spring-suite.yml";

    private ExternalSuiteConfigBootstrap() {
    }

    public static SuiteConfigBootstrapResult bootstrap() {
        Path projectRoot = resolveProjectRoot();
        Path configDir = resolveConfigDir(projectRoot);

        try {
            Files.createDirectories(configDir);

            List<RegisteredConfigFile> configFiles = registeredConfigFiles();
            ArrayList<ProcessedConfigFile> processed = new ArrayList<>();
            for (RegisteredConfigFile configFile : configFiles) {
                processed.add(processConfigFile(configDir, configFile));
            }

            appendSpringAdditionalLocations(processed.stream().map(ProcessedConfigFile::path).toList());
            String mergedConfigContent = processed.stream()
                    .map(ProcessedConfigFile::content)
                    .collect(Collectors.joining("\n"));

            boolean created = processed.stream().anyMatch(ProcessedConfigFile::created);
            boolean supplemented = processed.stream().anyMatch(ProcessedConfigFile::supplemented);
            Path baseConfigPath = processed.stream()
                    .filter(file -> file.moduleId().equals("suite-base"))
                    .findFirst()
                    .map(ProcessedConfigFile::path)
                    .orElse(configDir.resolve(BASE_CONFIG_FILE).toAbsolutePath().normalize());

            boolean ansiEnabled = readBoolean(mergedConfigContent, "suite.console.ansi.enabled", true);
            boolean ansiProbe = readBoolean(mergedConfigContent, "suite.console.ansi.probe", true);
            String springAnsi = readString(mergedConfigContent, "spring.output.ansi.enabled", "ALWAYS");
            String configuredLogFile = readString(mergedConfigContent, "logging.file.name", "logs/spring-suite.log");
            Path logFile = resolveProjectRelativePath(projectRoot, configuredLogFile);
            Files.createDirectories(logFile.getParent());

            System.setProperty("suite.project.root", projectRoot.toString());
            System.setProperty("suite.config.path", baseConfigPath.toString());
            System.setProperty("suite.config.dir", configDir.toString());
            System.setProperty("suite.config.files", processed.stream()
                    .map(file -> file.moduleId() + "=" + file.path())
                    .collect(Collectors.joining(";")));
            System.setProperty("suite.config.module.count", Integer.toString(processed.size()));
            System.setProperty("suite.logs.path", logFile.toString());
            System.setProperty("suite.config.created", Boolean.toString(created));
            System.setProperty("suite.config.supplemented", Boolean.toString(supplemented));
            System.setProperty("spring.output.ansi.enabled", springAnsi);
            System.setProperty("logging.file.name", logFile.toString());

            return new SuiteConfigBootstrapResult(
                    projectRoot,
                    baseConfigPath,
                    logFile,
                    created,
                    supplemented,
                    ansiEnabled,
                    ansiProbe,
                    springAnsi
            );
        } catch (IOException ex) {
            throw new IllegalStateException("SpringSuite external config bootstrap failed: " + configDir, ex);
        }
    }

    private static List<RegisteredConfigFile> registeredConfigFiles() {
        LinkedHashMap<String, RegisteredConfigFile> filesByName = new LinkedHashMap<>();
        putConfig(filesByName, new RegisteredConfigFile(
                "suite-base",
                BASE_CONFIG_FILE,
                BASE_CONFIG_RESOURCE,
                0,
                ExternalSuiteConfigBootstrap.class
        ));

        ServiceLoader<SuiteConfigContributor> loader = ServiceLoader.load(SuiteConfigContributor.class);
        for (SuiteConfigContributor contributor : loader) {
            for (SuiteConfigFile file : contributor.configFiles()) {
                putConfig(filesByName, new RegisteredConfigFile(
                        file.moduleId(),
                        file.fileName(),
                        file.defaultResource(),
                        file.order(),
                        contributor.getClass()
                ));
            }
        }

        return filesByName.values().stream()
                .sorted(Comparator.comparingInt(RegisteredConfigFile::order).thenComparing(RegisteredConfigFile::fileName))
                .toList();
    }

    private static void putConfig(LinkedHashMap<String, RegisteredConfigFile> filesByName, RegisteredConfigFile file) {
        filesByName.putIfAbsent(file.fileName(), file);
    }

    private static ProcessedConfigFile processConfigFile(Path configDir, RegisteredConfigFile configFile) throws IOException {
        Path configPath = configDir.resolve(configFile.fileName()).toAbsolutePath().normalize();
        String defaultConfig = loadDefaultConfigText(configFile);
        boolean created = createDefaultConfigIfMissing(configPath, defaultConfig);
        boolean supplemented = false;
        if (!created) {
            supplemented = supplementMissingConfig(configPath, defaultConfig, configFile.moduleId());
        }
        return new ProcessedConfigFile(
                configFile.moduleId(),
                configPath,
                created,
                supplemented,
                Files.readString(configPath, StandardCharsets.UTF_8)
        );
    }

    private static Path resolveConfigDir(Path projectRoot) {
        String explicit = firstNonBlank(
                System.getProperty("suite.config.dir"),
                System.getenv("SPRING_SUITE_CONFIG_DIR")
        );
        if (!explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        return projectRoot.resolve("config").toAbsolutePath().normalize();
    }

    private static Path resolveProjectRoot() {
        String explicit = firstNonBlank(
                System.getProperty("suite.home"),
                System.getenv("SPRING_SUITE_HOME")
        );
        if (!explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static Path resolveProjectRelativePath(Path projectRoot, String configuredPath) {
        Path path = Paths.get(configuredPath);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return projectRoot.resolve(path).toAbsolutePath().normalize();
    }

    private static boolean createDefaultConfigIfMissing(Path configPath, String defaultConfig) throws IOException {
        if (Files.exists(configPath)) {
            return false;
        }
        Files.writeString(configPath, defaultConfig, StandardCharsets.UTF_8);
        return true;
    }

    private static String loadDefaultConfigText(RegisteredConfigFile configFile) throws IOException {
        InputStream input = configFile.resourceOwner().getClassLoader().getResourceAsStream(configFile.defaultResource());
        if (input == null) {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            input = contextLoader == null ? null : contextLoader.getResourceAsStream(configFile.defaultResource());
        }
        if (input == null) {
            input = ExternalSuiteConfigBootstrap.class.getClassLoader().getResourceAsStream(configFile.defaultResource());
        }
        if (input == null) {
            throw new IOException("missing config resource: " + configFile.defaultResource() + " for module " + configFile.moduleId());
        }
        try (InputStream resolvedInput = input) {
            return new String(resolvedInput.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean supplementMissingConfig(Path configPath, String defaultConfig, String moduleId) throws IOException {
        String existingConfig = Files.readString(configPath, StandardCharsets.UTF_8);
        ConfigYamlNode existingRoot = ConfigYamlNode.parse(existingConfig);
        ConfigYamlNode defaultRoot = ConfigYamlNode.parse(defaultConfig);
        boolean changed = existingRoot.mergeMissingFrom(defaultRoot);
        if (!changed) {
            return false;
        }

        Path backupPath = backupPath(configPath);
        Files.copy(configPath, backupPath, StandardCopyOption.COPY_ATTRIBUTES);
        String merged = "# SpringSuite module configuration: " + moduleId + "\n"
                + "# Missing keys were supplemented from module defaults.\n"
                + "# Last supplement backup: " + backupPath.getFileName() + "\n\n"
                + existingRoot.toYaml();
        Files.writeString(configPath, merged, StandardCharsets.UTF_8);
        return true;
    }

    private static Path backupPath(Path configPath) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now());
        return configPath.resolveSibling(configPath.getFileName() + ".bak-" + timestamp);
    }

    private static void appendSpringAdditionalLocations(List<Path> configPaths) {
        String current = System.getProperty("spring.config.additional-location", "").trim();
        ArrayList<String> locations = new ArrayList<>();
        if (!current.isEmpty()) {
            for (String part : current.split(",")) {
                if (!part.isBlank()) {
                    locations.add(part.trim());
                }
            }
        }
        for (Path configPath : configPaths) {
            String configUri = configPath.toUri().toString();
            if (!locations.contains(configUri)) {
                locations.add(configUri);
            }
        }
        System.setProperty("spring.config.additional-location", String.join(",", locations));
    }

    private static boolean readBoolean(String yaml, String dottedKey, boolean fallback) {
        String raw = readString(yaml, dottedKey, null);
        if (raw == null) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase()) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> fallback;
        };
    }

    private static String readString(String yaml, String dottedKey, String fallback) {
        ConfigYamlNode root = ConfigYamlNode.parse(yaml);
        ConfigYamlNode node = root.find(dottedKey);
        if (node == null || node.value == null || node.value.isBlank()) {
            return fallback;
        }
        return stripQuotes(node.value);
    }

    private static String stripQuotes(String value) {
        String v = value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record RegisteredConfigFile(
            String moduleId,
            String fileName,
            String defaultResource,
            int order,
            Class<?> resourceOwner
    ) {
    }

    private record ProcessedConfigFile(
            String moduleId,
            Path path,
            boolean created,
            boolean supplemented,
            String content
    ) {
    }
}
