package com.takesome.springsuite.toolbelt.state;

import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.search.ToolSearchEngine;
import com.takesome.springsuite.toolbelt.support.ToolDescriptorValues;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolbeltCatalog {
    private static final int RETIRED_GENERATIONS = 4;

    private final Object lock = new Object();
    private final LinkedHashMap<String, ToolDescriptor> tools = new LinkedHashMap<>();
    private final ArrayDeque<Map<String, ToolDescriptor>> retired = new ArrayDeque<>();
    private final ArrayList<String> diagnostics = new ArrayList<>();
    private final ArrayList<String> resolvedRoots = new ArrayList<>();
    private Instant scannedAt = Instant.EPOCH;
    private long generation;

    public void clear(Instant timestamp) {
        synchronized (lock) {
            retireCurrent();
            tools.clear();
            diagnostics.clear();
            resolvedRoots.clear();
            scannedAt = timestamp == null ? Instant.now() : timestamp;
            generation++;
        }
    }

    public void replace(Map<String, ToolDescriptor> discovered, List<String> newDiagnostics, List<String> newResolvedRoots, Instant timestamp) {
        synchronized (lock) {
            retireCurrent();
            tools.clear();
            if (discovered != null) {
                tools.putAll(discovered);
            }
            diagnostics.clear();
            diagnostics.addAll(newDiagnostics == null ? List.of() : newDiagnostics);
            resolvedRoots.clear();
            resolvedRoots.addAll(newResolvedRoots == null ? List.of() : newResolvedRoots);
            scannedAt = timestamp == null ? Instant.now() : timestamp;
            generation++;
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
            Optional<ToolDescriptor> current = findIn(tools, normalized, searchEngine);
            if (current.isPresent()) {
                return current;
            }
            for (Map<String, ToolDescriptor> generationTools : retired) {
                Optional<ToolDescriptor> historical = findIn(generationTools, normalized, searchEngine);
                if (historical.isPresent()) {
                    return historical;
                }
            }
            return Optional.empty();
        }
    }

    public Instant scannedAt() {
        synchronized (lock) {
            return scannedAt;
        }
    }

    public long generation() {
        synchronized (lock) {
            return generation;
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

    private Optional<ToolDescriptor> findIn(Map<String, ToolDescriptor> source, String normalized, ToolSearchEngine searchEngine) {
        ToolDescriptor direct = source.get(normalized);
        if (direct != null) {
            return Optional.of(direct);
        }
        return source.values().stream()
                .filter(tool -> searchEngine.matchesIdentity(tool, normalized))
                .findFirst();
    }

    private void retireCurrent() {
        if (tools.isEmpty()) {
            return;
        }
        retired.addFirst(Map.copyOf(tools));
        while (retired.size() > RETIRED_GENERATIONS) {
            retired.removeLast();
        }
    }
}
