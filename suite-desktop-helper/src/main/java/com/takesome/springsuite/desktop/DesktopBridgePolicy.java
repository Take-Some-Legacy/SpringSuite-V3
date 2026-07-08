package com.takesome.springsuite.desktop;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DesktopBridgePolicy {
    private final DesktopHelperProperties properties;

    public DesktopBridgePolicy(DesktopHelperProperties properties) {
        this.properties = properties;
    }

    public boolean allowedRealInput() {
        return properties.getBridge().isAllowedRealInput();
    }

    public boolean effectiveEnabled(DesktopBridgeAdapter.Descriptor descriptor) {
        DesktopHelperProperties.BridgeOverride override = properties.getBridges().get(descriptor.id());
        if (override != null && override.getEnabled() != null) {
            return override.getEnabled();
        }
        return descriptor.enabled();
    }

    public DesktopBridgeAdapter.Descriptor effectiveDescriptor(DesktopBridgeAdapter.Descriptor descriptor) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(descriptor.metadata());
        metadata.put("staticEnabled", descriptor.enabled());
        metadata.put("staticRealInputEnabled", descriptor.realInputEnabled());
        metadata.put("policyEnabled", effectiveEnabled(descriptor));
        metadata.put("policyAllowedRealInput", allowedRealInput());
        metadata.put("policyBlockedRealInput", descriptor.realInputEnabled() && !allowedRealInput());
        return new DesktopBridgeAdapter.Descriptor(
                descriptor.id(),
                descriptor.name(),
                effectiveEnabled(descriptor),
                descriptor.realInputEnabled(),
                descriptor.capabilities(),
                descriptor.supportedActions(),
                metadata
        );
    }

    public Map<String, Object> snapshot() {
        LinkedHashMap<String, Object> bridgeOverrides = new LinkedHashMap<>();
        for (Map.Entry<String, DesktopHelperProperties.BridgeOverride> entry : properties.getBridges().entrySet()) {
            DesktopHelperProperties.BridgeOverride override = entry.getValue();
            bridgeOverrides.put(entry.getKey(), Map.of("enabled", override == null || override.getEnabled() == null ? "" : override.getEnabled()));
        }
        return Map.of(
                "allowedRealInput", allowedRealInput(),
                "bridges", bridgeOverrides,
                "realInputPolicy", allowedRealInput()
                        ? "Real bridge input is allowed by configuration, subject to executor and approval guards."
                        : "Real bridge input is globally blocked by policy."
        );
    }
}
