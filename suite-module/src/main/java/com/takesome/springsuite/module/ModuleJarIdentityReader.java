package com.takesome.springsuite.module;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

final class ModuleJarIdentityReader {
    static final String MODULE_ID_ATTRIBUTE = "SpringSuite-Module";
    static final String MODULE_VERSION_ATTRIBUTE = "SpringSuite-Module-Version";

    private ModuleJarIdentityReader() {
    }

    static ModuleJarIdentity read(Path jarPath) throws IOException {
        Path normalized = jarPath.toAbsolutePath().normalize();
        try (JarFile jar = new JarFile(normalized.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                throw new IOException("module JAR has no manifest: " + normalized);
            }
            Attributes attributes = manifest.getMainAttributes();
            String moduleId = value(attributes, MODULE_ID_ATTRIBUTE);
            String version = value(attributes, MODULE_VERSION_ATTRIBUTE);
            if (moduleId.isBlank()) {
                throw new IOException("module JAR is missing " + MODULE_ID_ATTRIBUTE + ": " + normalized);
            }
            if (version.isBlank()) {
                throw new IOException("module JAR is missing " + MODULE_VERSION_ATTRIBUTE + ": " + normalized);
            }
            return new ModuleJarIdentity(normalized, moduleId, version);
        }
    }

    private static String value(Attributes attributes, String key) {
        String value = attributes.getValue(key);
        return value == null ? "" : value.trim();
    }
}
