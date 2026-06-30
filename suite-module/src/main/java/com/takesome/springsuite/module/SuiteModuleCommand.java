package com.takesome.springsuite.module;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class SuiteModuleCommand implements SuiteCommand {
    private final ObjectProvider<SuiteModuleRegistry> moduleRegistryProvider;

    public SuiteModuleCommand(ObjectProvider<SuiteModuleRegistry> moduleRegistryProvider) {
        this.moduleRegistryProvider = moduleRegistryProvider;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "modules",
                List.of("module", "mods"),
                "modules",
                "Inspect external runtime modules.",
                "Shows modules loaded from /modules, their manifests, commands, lifecycle hooks, dependencies and capabilities.",
                "modules <summary|list|info> [moduleId]",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String action = invocation.arg(0).isBlank() ? "summary" : invocation.arg(0).trim().toLowerCase();
        return switch (action) {
            case "summary", "status" -> CommandExecutionResult.ok("module summary", Map.of("summary", moduleRegistryProvider.getObject().summary()));
            case "list", "ls" -> CommandExecutionResult.ok("modules: " + moduleRegistryProvider.getObject().modules().size(), Map.of(
                    "modules", moduleRegistryProvider.getObject().modules().stream().map(this::line).toList()
            ));
            case "info" -> info(invocation);
            default -> CommandExecutionResult.failed("bad_modules_action", "Unknown modules action: " + action);
        };
    }

    private CommandExecutionResult info(CommandInvocation invocation) {
        String id = invocation.arg(1);
        if (id.isBlank()) {
            return CommandExecutionResult.failed("missing_module_id", "usage: modules info <moduleId>");
        }
        return moduleRegistryProvider.getObject().find(id)
                .map(module -> CommandExecutionResult.ok(module.manifest().id(), Map.of("module", module)))
                .orElseGet(() -> CommandExecutionResult.failed("module_not_found", "Module not found: " + id));
    }

    private String line(RegisteredSuiteModule module) {
        String state = module.enabled() ? "active" : "disabled";
        return module.manifest().id() + " " + module.manifest().version()
                + " [" + state + "] commands=" + module.commands().size()
                + " capabilities=" + module.capabilities().size();
    }
}
