package com.takesome.springsuite.module;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

final class ModuleArtifactPaths {
    Path trustStorePath() {
        String explicit = System.getProperty("suite.modules.trust.store.path", "");
        if (!explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        return runtimeRoot().resolve("config").resolve("trust").resolve("publishers.yml").toAbsolutePath().normalize();
    }

    Path modulesDir() {
        return Paths.get(System.getProperty("suite.modules.dir", runtimeRoot().resolve("modules").toString())).toAbsolutePath().normalize();
    }

    Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("suite.home", System.getProperty("user.dir")))).toAbsolutePath().normalize();
    }

    Path resolvePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return runtimeRoot();
        }
        Path path = Paths.get(raw);
        return path.isAbsolute() ? path.toAbsolutePath().normalize() : runtimeRoot().resolve(path).toAbsolutePath().normalize();
    }

    String idFromPublisher(String publisher, String cert) {
        String source = firstNonBlank(publisher, cert, "publisher").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        source = source.replaceAll("(^-|-$)", "");
        return source.isBlank() ? "publisher" : source;
    }

    String envRequired(String name) {
        String value = env(name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("environment variable is required: " + name);
        }
        return value;
    }

    String env(String name) {
        return name == null || name.isBlank() ? "" : System.getenv().getOrDefault(name, "");
    }

    String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
