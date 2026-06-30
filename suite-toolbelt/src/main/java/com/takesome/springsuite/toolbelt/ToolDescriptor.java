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
        String schema,
        String owner,
        String maturity,
        String sourceType,
        String root,
        String packageRoot,
        String sourceRoot,
        String cargoManifest,
        String installPath,
        List<String> defaultArgs,
        List<String> validationArgs,
        List<String> capabilities,
        List<String> formats,
        List<String> contentKinds,
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
        schema = schema == null ? "" : schema;
        owner = owner == null ? "" : owner;
        maturity = maturity == null ? "" : maturity;
        sourceType = sourceType == null ? "" : sourceType;
        root = root == null ? "" : root;
        packageRoot = packageRoot == null ? "" : packageRoot;
        sourceRoot = sourceRoot == null ? "" : sourceRoot;
        cargoManifest = cargoManifest == null ? "" : cargoManifest;
        installPath = installPath == null ? "" : installPath;
        defaultArgs = defaultArgs == null ? List.of() : List.copyOf(defaultArgs);
        validationArgs = validationArgs == null ? List.of() : List.copyOf(validationArgs);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        formats = formats == null ? List.of() : List.copyOf(formats);
        contentKinds = contentKinds == null ? List.of() : List.copyOf(contentKinds);
        availabilityMessage = availabilityMessage == null ? "" : availabilityMessage;
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback.trim()) : value.trim();
    }
}
