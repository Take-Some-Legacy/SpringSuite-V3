package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DesktopBridgeAdapterRegistry {
    private final Map<String, DesktopBridgeAdapter> bridges;
    private final DesktopBridgePolicy policy;

    public DesktopBridgeAdapterRegistry(List<DesktopBridgeAdapter> bridges, DesktopBridgePolicy policy) {
        this.policy = policy;
        LinkedHashMap<String, DesktopBridgeAdapter> indexed = new LinkedHashMap<>();
        List<DesktopBridgeAdapter> ordered = bridges == null ? List.of() : bridges.stream()
                .sorted(Comparator.comparing(adapter -> adapter.descriptor().id()))
                .toList();
        for (DesktopBridgeAdapter bridge : ordered) {
            indexed.putIfAbsent(bridge.descriptor().id(), bridge);
        }
        this.bridges = Map.copyOf(indexed);
    }

    public List<DesktopBridgeAdapter.Descriptor> descriptors() {
        return bridges.values().stream()
                .map(adapter -> policy.effectiveDescriptor(adapter.descriptor()))
                .sorted(Comparator.comparing(DesktopBridgeAdapter.Descriptor::id))
                .toList();
    }

    public Optional<DesktopBridgeAdapter.Descriptor> descriptor(String id) {
        return find(id).map(adapter -> policy.effectiveDescriptor(adapter.descriptor()));
    }

    public Optional<DesktopBridgeAdapter> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(bridges.get(id.trim()));
    }

    public Optional<DesktopBridgeAdapter> selectForAction(DesktopApprovedAction action) {
        String bridgeId = metadataText(action.metadata().get("bridgeId"));
        if (!bridgeId.isBlank()) {
            return find(bridgeId).filter(this::isSelectable);
        }
        String preferred = switch (action.action()) {
            case "fill", "paste" -> "clipboard-bridge-adapter";
            case "type", "hotkey", "submit" -> "keyboard-bridge-adapter";
            case "click" -> "mouse-bridge-adapter";
            case "select", "check", "uncheck" -> "browser-dom-bridge-adapter";
            default -> "";
        };
        if (!preferred.isBlank()) {
            Optional<DesktopBridgeAdapter> bridge = find(preferred)
                    .filter(this::isSelectable)
                    .filter(adapter -> effectiveDescriptor(adapter).supportedActions().contains(action.action()));
            if (bridge.isPresent()) {
                return bridge;
            }
        }
        return bridges.values().stream()
                .filter(this::isSelectable)
                .filter(adapter -> policy.effectiveDescriptor(adapter.descriptor()).supportedActions().contains(action.action()))
                .findFirst();
    }

    public DesktopBridgeAdapter.Descriptor effectiveDescriptor(DesktopBridgeAdapter adapter) {
        return policy.effectiveDescriptor(adapter.descriptor());
    }

    public Map<String, Object> policySnapshot() {
        return policy.snapshot();
    }

    public Map<String, Object> summary() {
        List<DesktopBridgeAdapter.Descriptor> descriptors = descriptors();
        long enabled = descriptors.stream().filter(DesktopBridgeAdapter.Descriptor::enabled).count();
        long disabled = descriptors.size() - enabled;
        long realInput = descriptors.stream().filter(DesktopBridgeAdapter.Descriptor::realInputEnabled).count();
        return Map.of(
                "count", descriptors.size(),
                "enabled", enabled,
                "disabled", disabled,
                "realInputEnabled", realInput,
                "allowedRealInput", policy.allowedRealInput()
        );
    }

    public boolean isSelectable(DesktopBridgeAdapter adapter) {
        DesktopBridgeAdapter.Descriptor descriptor = policy.effectiveDescriptor(adapter.descriptor());
        return descriptor.enabled() && (!descriptor.realInputEnabled() || policy.allowedRealInput());
    }

    private String metadataText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text.trim();
        }
        return String.valueOf(value).trim();
    }
}
