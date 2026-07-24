package com.takesome.springsuite.module;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class ModuleJarUniqueness {
    private ModuleJarUniqueness() {
    }

    static List<ModuleJarIdentity> requireUnique(List<Path> jarPaths) throws IOException {
        ArrayList<ModuleJarIdentity> identities = new ArrayList<>(jarPaths.size());
        LinkedHashMap<String, List<ModuleJarIdentity>> byModuleId = new LinkedHashMap<>();
        for (Path path : jarPaths) {
            ModuleJarIdentity identity = ModuleJarIdentityReader.read(path);
            identities.add(identity);
            byModuleId.computeIfAbsent(identity.moduleId().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(identity);
        }

        Map<String, List<ModuleJarIdentity>> duplicates = byModuleId.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        if (!duplicates.isEmpty()) {
            String details = duplicates.values().stream()
                    .map(group -> group.get(0).moduleId() + " -> " + group.stream()
                            .map(identity -> identity.version() + " @ " + identity.path().getFileName())
                            .collect(Collectors.joining(", ")))
                    .collect(Collectors.joining("; "));
            throw new IOException("duplicate SpringSuite module IDs are not allowed: " + details);
        }
        return List.copyOf(identities);
    }
}
