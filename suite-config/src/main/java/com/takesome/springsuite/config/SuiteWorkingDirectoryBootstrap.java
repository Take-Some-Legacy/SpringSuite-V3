package com.takesome.springsuite.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Installs the SpringSuite runtime working directory before modules, external config,
 * logging and Spring Boot resource resolution start using relative paths.
 */
public final class SuiteWorkingDirectoryBootstrap {
    public static final String SUITE_HOME_PROPERTY = "suite.home";
    public static final String SUITE_PROJECT_ROOT_PROPERTY = "suite.project.root";
    public static final String SUITE_WORKING_DIRECTORY_PROPERTY = "suite.working.directory";
    public static final String SUITE_WORKING_DIR_PROPERTY = "suite.working.dir";
    public static final String SUITE_LAUNCH_DIRECTORY_PROPERTY = "suite.launch.dir";
    public static final String SUITE_WORKING_DIRECTORY_ENV = "SPRING_SUITE_WORKING_DIRECTORY";
    public static final String SUITE_WORKING_DIR_ENV = "SPRING_SUITE_WORKING_DIR";
    public static final String SUITE_HOME_ENV = "SPRING_SUITE_HOME";

    private SuiteWorkingDirectoryBootstrap() {
    }

    public static Path installFromArgs(String[] args) {
        return install(resolveFromArgs(args));
    }

    public static Path install() {
        return install("");
    }

    public static Path install(String requestedWorkingDirectory) {
        Path launchDirectory = resolveLaunchDirectory();
        String explicit = firstNonBlank(
                requestedWorkingDirectory,
                System.getProperty(SUITE_WORKING_DIRECTORY_PROPERTY),
                System.getProperty(SUITE_WORKING_DIR_PROPERTY),
                System.getProperty(SUITE_HOME_PROPERTY),
                System.getenv(SUITE_WORKING_DIRECTORY_ENV),
                System.getenv(SUITE_WORKING_DIR_ENV),
                System.getenv(SUITE_HOME_ENV)
        );
        Path workingDirectory = explicit.isBlank()
                ? launchDirectory
                : Paths.get(explicit).toAbsolutePath().normalize();
        ensureDirectory(workingDirectory);
        publish(launchDirectory, workingDirectory);
        return workingDirectory;
    }

    public static Path installedDirectory() {
        String configured = firstNonBlank(
                System.getProperty(SUITE_PROJECT_ROOT_PROPERTY),
                System.getProperty(SUITE_WORKING_DIRECTORY_PROPERTY),
                System.getProperty(SUITE_WORKING_DIR_PROPERTY),
                System.getProperty(SUITE_HOME_PROPERTY),
                System.getProperty("user.dir")
        );
        return Paths.get(configured).toAbsolutePath().normalize();
    }

    private static Path resolveLaunchDirectory() {
        String existing = System.getProperty(SUITE_LAUNCH_DIRECTORY_PROPERTY);
        if (existing != null && !existing.isBlank()) {
            return Paths.get(existing).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static void publish(Path launchDirectory, Path workingDirectory) {
        String root = workingDirectory.toString();
        System.setProperty(SUITE_LAUNCH_DIRECTORY_PROPERTY, launchDirectory.toString());
        System.setProperty(SUITE_WORKING_DIRECTORY_PROPERTY, root);
        System.setProperty(SUITE_WORKING_DIR_PROPERTY, root);
        System.setProperty(SUITE_PROJECT_ROOT_PROPERTY, root);
        System.setProperty(SUITE_HOME_PROPERTY, root);
        System.setProperty("user.dir", root);
    }

    private static void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException("SpringSuite working directory cannot be created: " + path, ex);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("SpringSuite working directory is not a directory: " + path);
        }
    }

    private static String resolveFromArgs(String[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i] == null ? "" : args[i].trim();
            if (arg.isBlank()) {
                continue;
            }
            String assigned = assignedValue(arg,
                    "--suite-working-directory",
                    "--suite-working-dir",
                    "--suite.working.directory",
                    "--suite.working.dir",
                    "--suite-home",
                    "--suite.home",
                    "--workdir",
                    "--cwd"
            );
            if (assigned != null) {
                return assigned;
            }
            if (isStandaloneKey(arg,
                    "--suite-working-directory",
                    "--suite-working-dir",
                    "--suite.working.directory",
                    "--suite.working.dir",
                    "--suite-home",
                    "--suite.home",
                    "--workdir",
                    "--cwd")
                    && i + 1 < args.length) {
                String next = args[i + 1] == null ? "" : args[i + 1].trim();
                if (!next.isBlank()) {
                    return next;
                }
            }
        }
        return "";
    }

    private static String assignedValue(String arg, String... keys) {
        for (String key : keys) {
            String prefix = key + "=";
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static boolean isStandaloneKey(String arg, String... keys) {
        for (String key : keys) {
            if (arg.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
