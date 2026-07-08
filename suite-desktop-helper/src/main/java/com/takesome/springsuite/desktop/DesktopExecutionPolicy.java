package com.takesome.springsuite.desktop;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DesktopExecutionPolicy {
    private final DesktopHelperProperties properties;

    public DesktopExecutionPolicy(DesktopHelperProperties properties) {
        this.properties = properties;
    }

    public String defaultExecutorId() {
        return properties.getExecutor().getDefaultId();
    }

    public boolean allowedRealInput() {
        return properties.getExecutor().isAllowedRealInput();
    }

    public boolean effectiveEnabled(DesktopActionExecutor.Descriptor descriptor) {
        DesktopHelperProperties.ExecutorOverride override = properties.getExecutors().get(descriptor.id());
        if (override != null && override.getEnabled() != null) {
            return override.getEnabled();
        }
        return descriptor.enabled();
    }

    public boolean effectiveRealInputEnabled(DesktopActionExecutor.Descriptor descriptor) {
        return descriptor.realInputEnabled();
    }

    public DesktopActionExecutor.Descriptor effectiveDescriptor(DesktopActionExecutor.Descriptor descriptor) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(descriptor.metadata());
        metadata.put("staticEnabled", descriptor.enabled());
        metadata.put("staticRealInputEnabled", descriptor.realInputEnabled());
        metadata.put("policyEnabled", effectiveEnabled(descriptor));
        metadata.put("policyAllowedRealInput", allowedRealInput());
        metadata.put("effectiveRealInputEnabled", effectiveRealInputEnabled(descriptor));
        metadata.put("policyBlockedRealInput", descriptor.realInputEnabled() && !allowedRealInput());
        return new DesktopActionExecutor.Descriptor(
                descriptor.id(),
                descriptor.name(),
                effectiveEnabled(descriptor),
                effectiveRealInputEnabled(descriptor),
                descriptor.capabilities(),
                descriptor.supportedActions(),
                metadata
        );
    }

    public Map<String, Object> snapshot() {
        LinkedHashMap<String, Object> executorOverrides = new LinkedHashMap<>();
        for (Map.Entry<String, DesktopHelperProperties.ExecutorOverride> entry : properties.getExecutors().entrySet()) {
            DesktopHelperProperties.ExecutorOverride override = entry.getValue();
            executorOverrides.put(entry.getKey(), Map.of("enabled", override == null || override.getEnabled() == null ? "" : override.getEnabled()));
        }
        return Map.of(
                "defaultId", defaultExecutorId(),
                "allowedRealInput", allowedRealInput(),
                "executors", executorOverrides,
                "realInputPolicy", allowedRealInput()
                        ? "Real input is allowed by configuration, but individual executor guards still apply."
                        : "Real desktop input is globally blocked by policy."
        );
    }
}
