package com.takesome.springsuite.toolbelt;

import java.util.List;
import java.util.Map;

public record ToolDescriptor(
        String id,
        String name,
        String title,
        String source,
        String kind,
        String description,
        String descriptorPath,
        String executable,
        List<String> commandTemplate,
        List<String> safeCommandIds,
        List<String> tags,
        boolean available,
        String availabilityMessage,
        boolean alwaysWrite,
        Map<String, Object> raw
) {
    public ToolDescriptor {
        id = blankToDefault(id, name);
        name = blankToDefault(name, id);
        title = blankToDefault(title, name);
        source = blankToDefault(source, "unknown");
        kind = blankToDefault(kind, "external-cli");
        description = description == null ? "" : description;
        descriptorPath = descriptorPath == null ? "" : descriptorPath;
        executable = executable == null ? "" : executable;
        commandTemplate = commandTemplate == null ? List.of() : List.copyOf(commandTemplate);
        safeCommandIds = safeCommandIds == null ? List.of() : List.copyOf(safeCommandIds);
        tags = tags == null ? List.of() : List.copyOf(tags);
        availabilityMessage = availabilityMessage == null ? "" : availabilityMessage;
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback.trim()) : value.trim();
    }
}
