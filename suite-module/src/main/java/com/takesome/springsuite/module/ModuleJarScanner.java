package com.takesome.springsuite.module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class ModuleJarScanner {
    private ModuleJarScanner() {
    }

    static List<Path> scan(Path modulesDir, boolean recursive) throws IOException {
        int depth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> stream = Files.walk(modulesDir, depth)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }
}
