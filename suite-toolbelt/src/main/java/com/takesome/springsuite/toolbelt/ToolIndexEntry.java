package com.takesome.springsuite.toolbelt;

import java.util.List;

public record ToolIndexEntry(
        String id,
        String publicName,
        String name,
        String title,
        String source,
        String kind,
        String descriptorPath,
        boolean available,
        List<String> terms
) {
    public ToolIndexEntry {
        id = id == null ? "" : id;
        publicName = publicName == null ? "" : publicName;
        name = name == null ? "" : name;
        title = title == null ? "" : title;
        source = source == null ? "" : source;
        kind = kind == null ? "" : kind;
        descriptorPath = descriptorPath == null ? "" : descriptorPath;
        terms = terms == null ? List.of() : List.copyOf(terms);
    }
}
