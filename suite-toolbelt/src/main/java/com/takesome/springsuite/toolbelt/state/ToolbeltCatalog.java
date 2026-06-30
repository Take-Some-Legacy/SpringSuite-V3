package com.takesome.springsuite.toolbelt.state;

import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.search.ToolSearchEngine;
import com.takesome.springsuite.toolbelt.support.ToolDescriptorValues;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolbeltCatalog {
    private final Object lock = new Object();
    private final LinkedHashMap<String, ToolDescriptor> tools = new LinkedHashMap<>();
    private final ArrayList<String> diagnostics = new ArrayList<>();
    private final ArrayList<String> resolvedRoots = new ArrayList<>();
    private Instant scannedAt = Instant.EPOCH;

    public void clear(Instant timestamp) {
        synchronized (lock) {
            tools.clear();
            diagnostics.clear();
            resolvedRoots.clear();
            scannedAt = timestamp == null ? Instant.now() : timestamp;
        }
    }

    public void replace(Map<String, ToolDescriptor> discovered, List<String> newDiagnostics, List<String> newResolvedRoots, Instant timestamp) {
        synchronized (lock) {
            tools.clear();
            if (discovered != null) {
                tools.putAll(discovered);
            }
            diagnostics.clear();
            diagnostics.addAll(newDiagnostics == null ? List.of() : newDiagnostics);
            resolvedRoots.clear();
            resolvedRoots.addAll(newResolvedRoots == null ? List.of() : newResolvedRoots);
            scannedAt = timestamp == null ? Instant.now() : timestamp;
        }
    }

    public List<ToolDescriptor> listTools() {
        synchronized (lock) {
            return tools.values().stream()
                    .sorted(Comparator.comparing(ToolDescriptor::id))
                    .toList();
        }
    }

    public Optional<ToolDescriptor> find(String idOrName, ToolSearchEngine searchEngine) {
        String normalized = ToolDescriptorValues.normalize(idOrName);
        synchronized (lock) {
            ToolDescriptor direct = tools.get(normalized);
            if (direct != null) {
                return Optional.of(direct);
            }
            return tools.values().stream()
                    .filter(tool -> searchEngine.matchesIdentity(tool, normalized))
                    .findFirst();
        }
    }

    public Instant scannedAt() {
        synchronized (lock) {
            return scannedAt;
        }
    }

    public List<String> resolvedRoots() {
        synchronized (lock) {
            return List.copyOf(resolvedRoots);
        }
    }

    public List<String> diagnostics() {
        synchronized (lock) {
            return List.copyOf(diagnostics);
        }
    }
}
