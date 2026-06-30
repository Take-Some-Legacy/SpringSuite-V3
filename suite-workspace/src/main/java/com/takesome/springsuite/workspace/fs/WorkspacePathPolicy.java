package com.takesome.springsuite.workspace.fs;

import com.takesome.springsuite.workspace.WorkspaceProperties;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class WorkspacePathPolicy {
    private final WorkspaceProperties properties;

    public WorkspacePathPolicy(WorkspaceProperties properties) {
        this.properties = properties;
    }

    public Path resolveSafe(String rawPath) {
        Path path = rawPath == null || rawPath.isBlank() ? Paths.get(".") : Paths.get(rawPath);
        Path resolved = path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : runtimeRoot().resolve(path).toAbsolutePath().normalize();
        Optional<Path> root = allowedRoots().stream().filter(allowed -> startsWith(resolved, allowed)).findFirst();
        if (root.isEmpty()) {
            throw new IllegalArgumentException("path escapes configured workspace roots: " + rawPath);
        }
        if (!isNotDenied(resolved)) {
            throw new IllegalArgumentException("path contains denied segment: " + rawPath);
        }
        return resolved;
    }

    public List<Path> allowedRoots() {
        ArrayList<Path> roots = new ArrayList<>();
        for (String raw : properties.getRoots()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path path = Paths.get(raw);
            roots.add(path.isAbsolute() ? path.toAbsolutePath().normalize() : runtimeRoot().resolve(path).toAbsolutePath().normalize());
        }
        return roots.isEmpty() ? List.of(runtimeRoot()) : List.copyOf(roots);
    }

    public boolean isNotDenied(Path path) {
        Set<String> denied = properties.getDenySegments().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        for (Path part : path) {
            if (denied.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    public String displayPath(Path path) {
        try {
            return runtimeRoot().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    public Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("user.dir"))).toAbsolutePath().normalize();
    }

    private boolean startsWith(Path path, Path root) {
        return path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }
}
