package com.takesome.springsuite.workspace.fs;

import com.takesome.springsuite.core.mode.SuiteOperatorMode;
import com.takesome.springsuite.workspace.WorkspaceProperties;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class WorkspacePathPolicy {
    private final WorkspaceProperties properties;
    private final List<Path> allowedRoots;
    private final Set<String> deniedSegments;
    private final List<String> deniedGlobs;

    public WorkspacePathPolicy(WorkspaceProperties properties) {
        this.properties = properties;
        this.allowedRoots = computeAllowedRoots();
        LinkedHashSet<String> normalizedSegments = new LinkedHashSet<>();
        for (String value : properties.effectiveDenySegments()) {
            if (value != null && !value.isBlank()) {
                normalizedSegments.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.deniedSegments = Set.copyOf(normalizedSegments);
        this.deniedGlobs = properties.effectiveDenyGlobs().stream()
                .map(this::normalizeGlob)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    public Path resolveSafe(String rawPath) {
        Path path = rawPath == null || rawPath.isBlank() ? Paths.get(".") : Paths.get(rawPath);
        Path resolved = path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : runtimeRoot().resolve(path).toAbsolutePath().normalize();
        if (SuiteOperatorMode.isElevated()) {
            return resolved;
        }
        if (containingAllowedRoot(resolved) == null) {
            throw new IllegalArgumentException("path escapes configured workspace roots: " + rawPath);
        }
        if (!isNotDeniedForScan(resolved)) {
            throw new IllegalArgumentException("path denied by workspace policy: " + rawPath);
        }
        return resolved;
    }

    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    public boolean isNotDenied(Path path) {
        return SuiteOperatorMode.isElevated() || isNotDeniedForScan(path);
    }

    /**
     * Strict traversal policy for recursive scans. Unlike direct elevated access, recursive scans
     * must never descend into denied build/cache/VCS trees: doing so turns a read-only search into
     * an unbounded filesystem operation on large workspaces.
     */
    public boolean isNotDeniedForScan(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path root = containingAllowedRoot(normalized);
        Path policyPath = root == null ? normalized : relativizeSafe(root, normalized);
        return !hasDeniedSegment(policyPath) && !matchesDeniedGlob(policyPath);
    }

    public String displayPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : allowedRoots) {
            if (startsWith(normalized, root)) {
                try {
                    String rel = root.relativize(normalized).toString().replace('\\', '/');
                    return rel.isBlank() ? "." : rel;
                } catch (IllegalArgumentException ignored) {
                    // Try next root.
                }
            }
        }
        try {
            return runtimeRoot().relativize(normalized).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return normalized.toString();
        }
    }

    public Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("user.dir"))).toAbsolutePath().normalize();
    }

    private List<Path> computeAllowedRoots() {
        ArrayList<Path> roots = new ArrayList<>();
        Path runtimeRoot = runtimeRoot();
        for (String raw : properties.effectiveRoots()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path path = Paths.get(raw);
            roots.add(path.isAbsolute() ? path.toAbsolutePath().normalize() : runtimeRoot.resolve(path).toAbsolutePath().normalize());
        }
        return roots.isEmpty() ? List.of(runtimeRoot) : List.copyOf(roots);
    }

    private Path containingAllowedRoot(Path path) {
        for (Path root : allowedRoots) {
            if (startsWith(path, root)) {
                return root;
            }
        }
        return null;
    }

    private boolean startsWith(Path path, Path root) {
        return path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    private Path relativizeSafe(Path root, Path path) {
        try {
            return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException ex) {
            return path;
        }
    }

    private boolean hasDeniedSegment(Path policyPath) {
        for (Path part : policyPath) {
            if (deniedSegments.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDeniedGlob(Path policyPath) {
        String rel = policyPath.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (rel.isBlank() || rel.equals(".")) {
            return false;
        }
        for (String glob : deniedGlobs) {
            if (globMatches(glob, rel)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeGlob(String raw) {
        return raw == null ? "" : raw.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private boolean globMatches(String glob, String rel) {
        if (glob.isBlank()) {
            return false;
        }
        if (glob.startsWith("**/") && glob.endsWith("/**")) {
            String segment = glob.substring(3, glob.length() - 3);
            return rel.equals(segment) || rel.startsWith(segment + "/") || rel.contains("/" + segment + "/") || rel.endsWith("/" + segment);
        }
        if (glob.startsWith("**/*")) {
            return rel.endsWith(glob.substring(4));
        }
        String quoted = Pattern.quote(glob)
                .replace("\\*\\*", "\\E.*\\Q")
                .replace("\\*", "\\E[^/]*\\Q")
                .replace("\\?", "\\E.\\Q");
        return Pattern.compile("^" + quoted + "$").matcher(rel).matches();
    }
}
