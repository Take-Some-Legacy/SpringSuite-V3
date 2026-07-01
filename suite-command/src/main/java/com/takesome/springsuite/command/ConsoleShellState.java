package com.takesome.springsuite.command;

import com.takesome.springsuite.core.mode.SuiteOperatorMode;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class ConsoleShellState {
    private String currentDirectory = ".";

    public synchronized String currentDirectory() { return currentDirectory; }

    public synchronized void changeDirectory(String path) {
        currentDirectory = normalizeDisplay(path == null || path.isBlank() ? "." : path);
    }

    public synchronized String prompt(String fallback) {
        String marker = SuiteOperatorMode.isElevated() ? "[ELEVATED]" : "";
        return "suite" + marker + ":" + currentDirectory + "$ ";
    }

    public String modeBanner() {
        if (!SuiteOperatorMode.isElevated()) {
            return "mode=STANDARD";
        }
        return "mode=ELEVATED source=" + SuiteOperatorMode.source();
    }

    public synchronized String resolve(String rawPath) {
        String raw = rawPath == null || rawPath.isBlank() ? "." : rawPath.trim();
        Path rawAsPath = Path.of(raw);
        if (rawAsPath.isAbsolute()) { return normalizeDisplay(rawAsPath.normalize().toString()); }
        Path base = Path.of(currentDirectory == null || currentDirectory.isBlank() ? "." : currentDirectory);
        return normalizeDisplay(base.resolve(raw).normalize().toString());
    }

    private String normalizeDisplay(String value) {
        String normalized = value == null || value.isBlank() ? "." : value.replace((char) 92, '/');
        return normalized.isBlank() ? "." : normalized;
    }
}
