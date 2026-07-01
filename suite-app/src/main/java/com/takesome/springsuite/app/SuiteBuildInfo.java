package com.takesome.springsuite.app;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Properties;
import org.springframework.boot.info.BuildProperties;

public final class SuiteBuildInfo {
    private static final String BUILD_INFO_RESOURCE = "META-INF/build-info.properties";
    private static final String DEFAULT_NAME = "spring-suite";
    private static final String DEFAULT_VERSION = "0.1.6";
    private static final String UNKNOWN = "unknown";

    private final String name;
    private final String version;
    private final String build;
    private final String commit;
    private final String branch;
    private final String dirty;
    private final String time;

    private SuiteBuildInfo(
            String name,
            String version,
            String build,
            String commit,
            String branch,
            String dirty,
            String time
    ) {
        this.name = requireText(name, "name");
        this.version = requireText(version, "version");
        this.build = requireText(build, "build");
        this.commit = requireText(commit, "commit");
        this.branch = requireText(branch, "branch");
        this.dirty = requireText(dirty, "dirty");
        this.time = requireText(time, "time");
    }

    public static SuiteBuildInfo load() {
        Properties properties = loadBuildProperties();
        String commit = firstNonBlank(
                System.getProperty("suite.git.commit"),
                property(properties, "build.commit", "commit"),
                UNKNOWN
        );
        String build = firstNonBlank(
                System.getProperty("suite.build"),
                property(properties, "build.build", "build"),
                shortCommit(commit),
                "dev"
        );
        return new SuiteBuildInfo(
                firstNonBlank(
                        System.getProperty("suite.name"),
                        property(properties, "build.name", "name"),
                        DEFAULT_NAME
                ),
                firstNonBlank(
                        System.getProperty("suite.version"),
                        property(properties, "build.version", "version"),
                        packageImplementationVersion(),
                        DEFAULT_VERSION
                ),
                build,
                commit,
                firstNonBlank(
                        System.getProperty("suite.git.branch"),
                        property(properties, "build.branch", "branch"),
                        UNKNOWN
                ),
                firstNonBlank(
                        System.getProperty("suite.git.dirty"),
                        property(properties, "build.dirty", "dirty"),
                        UNKNOWN
                ),
                firstNonBlank(
                        System.getProperty("suite.build.time"),
                        property(properties, "build.time", "time"),
                        UNKNOWN
                )
        );
    }

    public String startupLine() {
        return "[SpringSuite] version=" + version
                + " build=" + build
                + " commit=" + commit
                + " branch=" + branch
                + " dirty=" + dirty
                + " time=" + time;
    }

    public BuildProperties toBuildProperties() {
        Properties properties = new Properties();
        properties.setProperty("name", name);
        properties.setProperty("version", version);
        properties.setProperty("build", build);
        properties.setProperty("commit", commit);
        properties.setProperty("branch", branch);
        properties.setProperty("dirty", dirty);
        properties.setProperty("buildTime", time);
        properties.setProperty("time", normalizedInstant(time));
        return new BuildProperties(properties);
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String build() {
        return build;
    }

    public String commit() {
        return commit;
    }

    public String branch() {
        return branch;
    }

    public String dirty() {
        return dirty;
    }

    public String time() {
        return time;
    }

    private static Properties loadBuildProperties() {
        Properties properties = new Properties();
        ClassLoader classLoader = SuiteBuildInfo.class.getClassLoader();
        try (InputStream stream = classLoader.getResourceAsStream(BUILD_INFO_RESOURCE)) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException ignored) {
            // Startup version reporting must be best-effort and must never block the process.
        }
        return properties;
    }

    private static String property(Properties properties, String... keys) {
        for (String key : keys) {
            String value = properties.getProperty(key);
            if (isNonBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (isNonBlank(value)) {
                return value.trim();
            }
        }
        return UNKNOWN;
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String shortCommit(String commit) {
        if (!isNonBlank(commit) || UNKNOWN.equals(commit)) {
            return "";
        }
        String trimmed = commit.trim();
        return trimmed.length() <= 12 ? trimmed : trimmed.substring(0, 12);
    }

    private static String packageImplementationVersion() {
        Package packageInfo = SuiteBuildInfo.class.getPackage();
        if (packageInfo == null) {
            return "";
        }
        return packageInfo.getImplementationVersion();
    }

    private static String normalizedInstant(String value) {
        if (isNonBlank(value)) {
            try {
                return Instant.parse(value.trim()).toString();
            } catch (DateTimeParseException ignored) {
                // Fall through to a valid runtime instant for Spring Boot BuildProperties.
            }
        }
        return Instant.now().toString();
    }

    private static String requireText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName).trim();
        if (text.isEmpty()) {
            return UNKNOWN;
        }
        return text;
    }
}
