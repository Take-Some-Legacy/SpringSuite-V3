package com.takesome.springsuite.module;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class ReloadCommand implements SuiteCommand {
    private final ObjectProvider<SuiteModuleRegistry> moduleRegistryProvider;
    private final AtomicBoolean reloading = new AtomicBoolean(false);

    public ReloadCommand(ObjectProvider<SuiteModuleRegistry> moduleRegistryProvider) {
        this.moduleRegistryProvider = moduleRegistryProvider;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "reload",
                List.of("modules-reload", "module-reload"),
                "modules",
                "Reload external runtime modules without restarting SpringSuite.",
                "Runs module shutdown hooks, unregisters module commands, discovers modules again, and registers commands. It never force-kills active workers.",
                "reload",
                CommandRiskLevel.LOCAL_MUTATION
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        if (!reloading.compareAndSet(false, true)) {
            return CommandExecutionResult.failed("reload_busy", "Module reload is already running.");
        }
        try {
            SuiteModuleSummary summary = moduleRegistryProvider.getObject().reloadModules();
            return CommandExecutionResult.ok("modules reloaded", Map.of("summary", summary));
        } finally {
            reloading.set(false);
        }
    }
}
