package com.takesome.springsuite.command.builtin;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRegistry;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class HelpCommand implements SuiteCommand {
    private final ObjectProvider<CommandRegistry> registryProvider;

    public HelpCommand(ObjectProvider<CommandRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "help",
                List.of("?", "commands"),
                "core",
                "Show registered commands.",
                "Lists command registry entries with usage and descriptions.",
                "help [command]",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        CommandRegistry registry = registryProvider.getObject();
        if (!invocation.args().isEmpty()) {
            String name = invocation.arg(0);
            SuiteCommand command = registry.find(name);
            if (command == null) {
                return CommandExecutionResult.failed("unknown_command", "Unknown command: " + name);
            }
            CommandDescriptor descriptor = command.descriptor();
            return CommandExecutionResult.ok(descriptor.name() + " :: " + descriptor.summary(), Map.of(
                    "name", descriptor.name(),
                    "aliases", descriptor.aliases(),
                    "category", descriptor.category(),
                    "usage", descriptor.usage(),
                    "description", descriptor.description(),
                    "risk", descriptor.riskLevel().name()
            ));
        }

        List<String> lines = registry.descriptors().stream()
                .map(descriptor -> descriptor.name() + " — " + descriptor.summary() + " | " + descriptor.usage())
                .toList();
        return CommandExecutionResult.ok("Registered commands: " + lines.size(), Map.of("commands", lines));
    }
}
