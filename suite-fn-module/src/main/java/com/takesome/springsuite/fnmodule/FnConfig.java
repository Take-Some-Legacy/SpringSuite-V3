package com.takesome.springsuite.fnmodule;

import java.util.List;
import java.util.Map;

public record FnConfig(
        boolean enabled,
        int buttonCount,
        String namespace,
        String dispatchMode,
        String defaultDestination,
        List<FnBinding> buttons
) {
    public FnConfig {
        buttonCount = buttonCount <= 0 ? 12 : buttonCount;
        namespace = namespace == null || namespace.isBlank() ? "fn" : namespace.trim();
        dispatchMode = dispatchMode == null || dispatchMode.isBlank() ? "explicit-operator-action" : dispatchMode.trim();
        defaultDestination = defaultDestination == null || defaultDestination.isBlank() ? "active-chat" : defaultDestination.trim();
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }

    public static FnConfig fallback() {
        return new FnConfig(true, 12, "fn", "explicit-operator-action", "active-chat", List.of(
                binding(1, false, "Unassigned", "", "none", Map.of()),
                binding(2, false, "Unassigned", "", "none", Map.of()),
                binding(3, false, "Unassigned", "", "none", Map.of()),
                binding(4, false, "Unassigned", "", "none", Map.of()),
                binding(5, false, "Unassigned", "", "none", Map.of()),
                binding(6, false, "Unassigned", "", "none", Map.of()),
                binding(7, false, "Unassigned", "", "none", Map.of()),
                binding(8, false, "Unassigned", "", "none", Map.of()),
                binding(9, false, "Unassigned", "", "none", Map.of()),
                binding(10, false, "Unassigned", "", "none", Map.of()),
                binding(11, false, "Unassigned", "", "none", Map.of()),
                binding(12, true, "Send Desktop Screenshot", "desktop.screenshot.send", "visual-private", Map.of("target", "virtual", "maxWidth", "1600"))
        ));
    }

    private static FnBinding binding(int index, boolean enabled, String title, String route, String riskTier, Map<String, String> args) {
        return new FnBinding(String.format("FN-%02d", index), index, enabled, title, route, riskTier, "active-chat", args);
    }
}
