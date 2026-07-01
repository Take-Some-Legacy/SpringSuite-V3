package com.takesome.springsuite.diagnosticsmodule;

import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.module.SuiteModule;
import com.takesome.springsuite.module.SuiteModuleCapability;
import com.takesome.springsuite.module.SuiteModuleManifest;
import java.util.List;
import java.util.Map;

public final class DiagnosticsSuiteModule implements SuiteModule {
    private final SuiteModuleManifest manifest = new SuiteModuleManifest(
            "spring-suite-diagnostics",
            "SpringSuite Diagnostics Module",
            "0.1.7",
            "TakeSome / SuiteLab",
            "External signed diagnostics module loaded outside the SpringSuite core jar.",
            List.of(),
            List.of(),
            Map.of(
                    "packaging", "external-module",
                    "commandNamespace", "diagnostics"
            )
    );

    private final List<SuiteCommand> commands = List.of(new DiagnosticsModuleCommand(manifest));

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
        return List.of(new SuiteModuleCapability(
                "spring-suite-diagnostics.commands",
                "command-provider",
                "Adds diagnostics commands to the shared SpringSuite command registry.",
                Map.of("commands", commands.stream().map(command -> command.descriptor().name()).toList())
        ));
    }
}
