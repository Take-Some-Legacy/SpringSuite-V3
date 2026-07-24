package com.takesome.springsuite.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleJarUniquenessTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptsDistinctModuleIds() throws Exception {
        Path dashboard = moduleJar("dashboard.jar", "spring-suite-dashboard", "3.3.2");
        Path diagnostics = moduleJar("diagnostics.jar", "spring-suite-diagnostics", "3.3.2");

        List<ModuleJarIdentity> identities = ModuleJarUniqueness.requireUnique(List.of(dashboard, diagnostics));

        assertThat(identities)
                .extracting(ModuleJarIdentity::moduleId)
                .containsExactly("spring-suite-dashboard", "spring-suite-diagnostics");
    }

    @Test
    void rejectsDuplicateModuleIdsAcrossVersions() throws Exception {
        Path oldJar = moduleJar("dashboard-3.3.1.jar", "spring-suite-dashboard", "3.3.1");
        Path newJar = moduleJar("dashboard-3.3.2.jar", "spring-suite-dashboard", "3.3.2");

        assertThatThrownBy(() -> ModuleJarUniqueness.requireUnique(List.of(oldJar, newJar)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate SpringSuite module IDs")
                .hasMessageContaining("spring-suite-dashboard")
                .hasMessageContaining("3.3.1")
                .hasMessageContaining("3.3.2");
    }

    @Test
    void moduleIdsAreComparedCaseInsensitively() throws Exception {
        Path first = moduleJar("first.jar", "Spring-Suite-Dashboard", "3.3.2");
        Path second = moduleJar("second.jar", "spring-suite-dashboard", "3.3.2");

        assertThatThrownBy(() -> ModuleJarUniqueness.requireUnique(List.of(first, second)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate SpringSuite module IDs");
    }

    @Test
    void rejectsJarWithoutModuleIdentity() throws Exception {
        Path jar = tempDir.resolve("missing-identity.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream ignored = new JarOutputStream(output, manifest)) {
            // Manifest-only test JAR.
        }

        assertThatThrownBy(() -> ModuleJarUniqueness.requireUnique(List.of(jar)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(ModuleJarIdentityReader.MODULE_ID_ATTRIBUTE);
    }

    private Path moduleJar(String name, String moduleId, String version) throws Exception {
        Path jar = tempDir.resolve(name);
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(ModuleJarIdentityReader.MODULE_ID_ATTRIBUTE, moduleId);
        attributes.putValue(ModuleJarIdentityReader.MODULE_VERSION_ATTRIBUTE, version);
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream ignored = new JarOutputStream(output, manifest)) {
            // Manifest-only test JAR.
        }
        return jar;
    }
}
