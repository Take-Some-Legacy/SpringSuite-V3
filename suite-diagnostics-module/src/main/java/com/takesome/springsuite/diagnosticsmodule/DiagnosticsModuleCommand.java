package com.takesome.springsuite.diagnosticsmodule;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.module.SuiteModuleManifest;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DiagnosticsModuleCommand implements SuiteCommand {
    private final SuiteModuleManifest manifest;

    public DiagnosticsModuleCommand(SuiteModuleManifest manifest) {
        this.manifest = manifest;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "diagnostics",
                List.of("diag-module", "module-diagnostics"),
                "modules",
                "Show diagnostics supplied by an external signed module.",
                "Proves that a runtime module loaded from the modules directory can register commands into the core command registry.",
                "diagnostics",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("moduleId", manifest.id());
        data.put("moduleVersion", manifest.version());
        data.put("suiteApiVersion", manifest.suiteApiVersion());
        data.put("loadedAt", Instant.now().toString());
        data.put("java", Runtime.version().toString());
        data.put("pid", ManagementFactory.getRuntimeMXBean().getPid());
        data.put("runtimeRoot", System.getProperty("suite.project.root", System.getProperty("user.dir")));
        data.put("modulesDir", System.getProperty("suite.modules.dir", ""));
        data.put("modulesCount", System.getProperty("suite.modules.count", "0"));
        return CommandExecutionResult.ok("diagnostics module command executed", data);
    }
}
