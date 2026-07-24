package com.takesome.springsuite.cloudflared;

import com.takesome.springsuite.core.platform.PlatformExecutables;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class CloudflaredExecutableResolver {
    static final String EXECUTABLE_ENV = "SPRING_SUITE_CLOUDFLARED_EXECUTABLE";

    private CloudflaredExecutableResolver() {
    }

    static Optional<Path> resolve(Path runtimeRoot, String configured, Map<String, String> environment) {
        Path root = runtimeRoot == null
                ? Paths.get("").toAbsolutePath().normalize()
                : runtimeRoot.toAbsolutePath().normalize();
        Map<String, String> env = environment == null ? Map.of() : environment;
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();

        addConfiguredCandidates(candidates, root, env.get(EXECUTABLE_ENV), env.get("PATH"));
        addConfiguredCandidates(candidates, root, configured, env.get("PATH"));

        String executableName = configured == null || configured.isBlank() ? "cloudflared" : configured.trim();
        if (!isPathLike(executableName)) {
            addExecutableVariants(candidates, root.resolve("suiteBinaries").resolve(executableName));
            addExecutableVariants(candidates, root.resolve(executableName));
            addPathCandidates(candidates, executableName, env.get("PATH"));
        }

        if (PlatformExecutables.isWindows()) {
            addWindowsCandidates(candidates, env);
        }

        return candidates.stream()
                .filter(Files::isRegularFile)
                .map(path -> path.toAbsolutePath().normalize())
                .findFirst();
    }

    static List<Path> searchCandidates(Path runtimeRoot, String configured, Map<String, String> environment) {
        Path root = runtimeRoot == null
                ? Paths.get("").toAbsolutePath().normalize()
                : runtimeRoot.toAbsolutePath().normalize();
        Map<String, String> env = environment == null ? Map.of() : environment;
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        addConfiguredCandidates(candidates, root, env.get(EXECUTABLE_ENV), env.get("PATH"));
        addConfiguredCandidates(candidates, root, configured, env.get("PATH"));
        String executableName = configured == null || configured.isBlank() ? "cloudflared" : configured.trim();
        if (!isPathLike(executableName)) {
            addExecutableVariants(candidates, root.resolve("suiteBinaries").resolve(executableName));
            addExecutableVariants(candidates, root.resolve(executableName));
            addPathCandidates(candidates, executableName, env.get("PATH"));
        }
        if (PlatformExecutables.isWindows()) {
            addWindowsCandidates(candidates, env);
        }
        return List.copyOf(candidates);
    }

    private static void addConfiguredCandidates(LinkedHashSet<Path> candidates, Path root, String raw, String pathValue) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = raw.trim();
        Path configured = Paths.get(value);
        if (configured.isAbsolute()) {
            addExecutableVariants(candidates, configured);
        } else if (isPathLike(value)) {
            addExecutableVariants(candidates, root.resolve(configured).normalize());
        } else {
            addPathCandidates(candidates, value, pathValue);
        }
    }

    private static void addPathCandidates(LinkedHashSet<Path> candidates, String executableName, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return;
        }
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path directory = Paths.get(entry.trim());
            for (String name : PlatformExecutables.executableNames(executableName)) {
                candidates.add(directory.resolve(name).normalize());
            }
        }
    }

    private static void addWindowsCandidates(LinkedHashSet<Path> candidates, Map<String, String> env) {
        addFromEnvironment(candidates, env, "LOCALAPPDATA", "Microsoft", "WinGet", "Links", "cloudflared.exe");
        addFromEnvironment(candidates, env, "ChocolateyInstall", "bin", "cloudflared.exe");
        addFromEnvironment(candidates, env, "SCOOP", "shims", "cloudflared.exe");
        addFromEnvironment(candidates, env, "USERPROFILE", ".cloudflared", "cloudflared.exe");
        addFromEnvironment(candidates, env, "ProgramFiles", "cloudflared", "cloudflared.exe");
        addFromEnvironment(candidates, env, "ProgramFiles(x86)", "cloudflared", "cloudflared.exe");
    }

    private static void addFromEnvironment(
            LinkedHashSet<Path> candidates,
            Map<String, String> env,
            String variable,
            String... relative
    ) {
        String base = env.get(variable);
        if (base == null || base.isBlank()) {
            return;
        }
        Path candidate = Paths.get(base.trim());
        for (String segment : relative) {
            candidate = candidate.resolve(segment);
        }
        candidates.add(candidate.normalize());
    }

    private static void addExecutableVariants(LinkedHashSet<Path> candidates, Path path) {
        candidates.addAll(PlatformExecutables.executablePathVariants(path));
    }

    private static boolean isPathLike(String value) {
        return value.contains("/") || value.contains("\\") || Paths.get(value).isAbsolute();
    }
}
