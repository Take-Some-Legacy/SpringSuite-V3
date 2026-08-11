package com.takesome.springsuite.toolbelt.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import com.takesome.springsuite.core.platform.PlatformExecutables;

public final class ToolbeltPaths {
    private ToolbeltPaths() {
    }

    public static Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("user.dir")))
                .toAbsolutePath()
                .normalize();
    }

    public static Path resolveRuntimePath(String configuredPath) {
        Path path = Paths.get(configuredPath);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return runtimeRoot().resolve(path).toAbsolutePath().normalize();
    }

    public static Path descriptorRepoRoot(Path scanRoot) {
        Path current = scanRoot.toAbsolutePath().normalize();
        while (current != null) {
            if (current.getFileName() != null && current.getFileName().toString().equalsIgnoreCase("tools")) {
                Path parent = current.getParent();
                if (parent != null) {
                    return parent.toAbsolutePath().normalize();
                }
            }
            current = current.getParent();
        }
        return runtimeRoot();
    }

    public static Optional<Path> findOnPath(String executableName) {
        return PlatformExecutables.findOnPath(executableName);
    }

}
