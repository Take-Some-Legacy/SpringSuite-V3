package com.takesome.springsuite.cloudflared;

import static org.assertj.core.api.Assertions.assertThat;

import com.takesome.springsuite.core.platform.PlatformExecutables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloudflaredExecutableResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesBundledExecutableBeforeDependingOnPath() throws Exception {
        Path runtimeRoot = temporaryDirectory.resolve("runtime");
        Path executable = runtimeRoot.resolve("suiteBinaries")
                .resolve(PlatformExecutables.sidecarName("cloudflared"));
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "test");

        assertThat(CloudflaredExecutableResolver.resolve(runtimeRoot, "cloudflared", Map.of("PATH", "")))
                .contains(executable.toAbsolutePath().normalize());
    }

    @Test
    void explicitEnvironmentOverrideWins() throws Exception {
        Path runtimeRoot = temporaryDirectory.resolve("runtime");
        Path executable = temporaryDirectory.resolve(PlatformExecutables.sidecarName("custom-cloudflared"));
        Files.writeString(executable, "test");
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "");
        environment.put(CloudflaredExecutableResolver.EXECUTABLE_ENV, executable.toString());

        assertThat(CloudflaredExecutableResolver.resolve(runtimeRoot, "cloudflared", environment))
                .contains(executable.toAbsolutePath().normalize());
    }

    @Test
    void resolvesExecutableFromProvidedPath() throws Exception {
        Path runtimeRoot = temporaryDirectory.resolve("runtime");
        Path bin = temporaryDirectory.resolve("bin");
        Path executable = bin.resolve(PlatformExecutables.sidecarName("cloudflared"));
        Files.createDirectories(bin);
        Files.writeString(executable, "test");

        assertThat(CloudflaredExecutableResolver.resolve(runtimeRoot, "cloudflared", Map.of("PATH", bin.toString())))
                .contains(executable.toAbsolutePath().normalize());
    }
}
