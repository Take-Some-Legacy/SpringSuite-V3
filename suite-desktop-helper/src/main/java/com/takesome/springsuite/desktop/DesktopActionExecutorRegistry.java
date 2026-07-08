package com.takesome.springsuite.desktop;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DesktopActionExecutorRegistry {
    private static final String DEFAULT_EXECUTOR_ID = "noop-desktop-action-executor";

    private final Map<String, DesktopActionExecutor> executors;

    public DesktopActionExecutorRegistry(List<DesktopActionExecutor> executors) {
        LinkedHashMap<String, DesktopActionExecutor> indexed = new LinkedHashMap<>();
        List<DesktopActionExecutor> ordered = executors == null ? List.of() : executors.stream()
                .sorted(Comparator.comparing(executor -> executor.descriptor().id()))
                .toList();
        for (DesktopActionExecutor executor : ordered) {
            indexed.putIfAbsent(executor.descriptor().id(), executor);
        }
        this.executors = Map.copyOf(indexed);
    }

    public List<DesktopActionExecutor.Descriptor> descriptors() {
        return executors.values().stream()
                .map(DesktopActionExecutor::descriptor)
                .sorted(Comparator.comparing(DesktopActionExecutor.Descriptor::id))
                .toList();
    }

    public Optional<DesktopActionExecutor.Descriptor> descriptor(String id) {
        return find(id).map(DesktopActionExecutor::descriptor);
    }

    public Optional<DesktopActionExecutor> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(executors.get(id.trim()));
    }

    public DesktopActionExecutor defaultExecutor() {
        return find(DEFAULT_EXECUTOR_ID)
                .or(() -> executors.values().stream()
                        .filter(executor -> executor.descriptor().enabled())
                        .filter(executor -> !executor.descriptor().realInputEnabled())
                        .findFirst())
                .orElseThrow(() -> new IllegalStateException("No enabled no-real-input DesktopActionExecutor is available."));
    }

    public DesktopActionExecutor.Descriptor defaultDescriptor() {
        return defaultExecutor().descriptor();
    }

    public Map<String, Object> summary() {
        long enabled = executors.values().stream().filter(executor -> executor.descriptor().enabled()).count();
        long disabled = executors.size() - enabled;
        long realInput = executors.values().stream().filter(executor -> executor.descriptor().realInputEnabled()).count();
        return Map.of(
                "count", executors.size(),
                "enabled", enabled,
                "disabled", disabled,
                "realInputEnabled", realInput,
                "defaultExecutor", defaultDescriptor().id()
        );
    }
}
