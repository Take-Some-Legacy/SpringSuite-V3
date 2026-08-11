package com.takesome.springsuite.desktop;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DesktopActionExecutorRegistry {
    private final Map<String, DesktopActionExecutor> executors;
    private final DesktopExecutionPolicy policy;

    public DesktopActionExecutorRegistry(List<DesktopActionExecutor> executors, DesktopExecutionPolicy policy) {
        this.policy = policy;
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
                .map(executor -> policy.effectiveDescriptor(executor.descriptor()))
                .sorted(Comparator.comparing(DesktopActionExecutor.Descriptor::id))
                .toList();
    }

    public Optional<DesktopActionExecutor.Descriptor> descriptor(String id) {
        return find(id).map(executor -> policy.effectiveDescriptor(executor.descriptor()));
    }

    public Optional<DesktopActionExecutor> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(executors.get(id.trim()));
    }

    public DesktopActionExecutor defaultExecutor() {
        return find(policy.defaultExecutorId())
                .filter(this::isSelectable)
                .or(() -> executors.values().stream().filter(this::isSelectable).findFirst())
                .orElseThrow(() -> new IllegalStateException("No enabled DesktopActionExecutor is available under current execution policy."));
    }

    public DesktopActionExecutor.Descriptor defaultDescriptor() {
        return policy.effectiveDescriptor(defaultExecutor().descriptor());
    }

    public DesktopActionExecutor.Descriptor effectiveDescriptor(DesktopActionExecutor executor) {
        return policy.effectiveDescriptor(executor.descriptor());
    }

    public Map<String, Object> policySnapshot() {
        return policy.snapshot();
    }

    public Map<String, Object> summary() {
        List<DesktopActionExecutor.Descriptor> descriptors = descriptors();
        long enabled = descriptors.stream().filter(DesktopActionExecutor.Descriptor::enabled).count();
        long disabled = descriptors.size() - enabled;
        long realInput = descriptors.stream().filter(DesktopActionExecutor.Descriptor::realInputEnabled).count();
        return Map.of(
                "count", descriptors.size(),
                "enabled", enabled,
                "disabled", disabled,
                "realInputEnabled", realInput,
                "defaultExecutor", defaultDescriptor().id(),
                "allowedRealInput", policy.allowedRealInput()
        );
    }

    private boolean isSelectable(DesktopActionExecutor executor) {
        DesktopActionExecutor.Descriptor descriptor = policy.effectiveDescriptor(executor.descriptor());
        return descriptor.enabled() && (!descriptor.realInputEnabled() || policy.allowedRealInput());
    }
}
