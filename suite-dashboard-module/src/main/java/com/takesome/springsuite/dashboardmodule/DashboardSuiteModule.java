package com.takesome.springsuite.dashboardmodule;

import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.module.SuiteModule;
import com.takesome.springsuite.module.SuiteModuleCapability;
import com.takesome.springsuite.module.SuiteModuleManifest;
import java.util.List;
import java.util.Map;

public final class DashboardSuiteModule implements SuiteModule {
    public static final String VERSION = "0.1.7";

    private final SuiteModuleManifest manifest = new SuiteModuleManifest(
            "spring-suite-dashboard",
            "SpringSuite Dashboard Module",
            VERSION,
            "TakeSome / SuiteLab",
            "External signed dashboard and bash-like CLI module loaded from the modules directory.",
            List.of(),
            List.of(),
            Map.of(
                    "packaging", "external-module",
                    "commandNamespace", "dashboard",
                    "ui", "unix-cli"
            )
    );

    private final DashboardRenderer renderer = new DashboardRenderer(manifest);
    private final List<SuiteCommand> commands = List.of(
            new DashboardCommand(manifest, renderer),
            new DashboardShellCommand(manifest, renderer)
    );

    @Override
    public SuiteModuleManifest manifest() {
        return manifest;
    }

    @Override
    public List<SuiteCommand> commands() {
        return commands;
    }

    @Override
    public List<SuiteModuleCapability> capabilities() {
        return List.of(
                new SuiteModuleCapability(
                        "spring-suite-dashboard.terminal-dashboard",
                        "terminal-ui",
                        "Renders a UNIX-like terminal dashboard with ASCII progress bars and hotkey return.",
                        Map.of("command", "dashboard", "hotkeys", List.of("q", "esc", "enter", "ctrl+c"))
                ),
                new SuiteModuleCapability(
                        "spring-suite-dashboard.bash-like-cli",
                        "subshell",
                        "Adds a safe bash-like CLI surface backed by module-local built-ins.",
                        Map.of("command", "shell", "aliases", List.of("sh", "bash", "cli"))
                )
        );
    }
}
