package com.takesome.springsuite.command;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommandRegistryController {
    private final CommandRegistry commandRegistry;

    public CommandRegistryController(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    @GetMapping("/api/commands")
    public SuiteApiResponse<List<CommandDescriptor>> commands() {
        return SuiteApiResponse.ok(commandRegistry.descriptors());
    }

    @PostMapping("/api/commands/execute")
    public SuiteApiResponse<CommandExecutionResult> execute(@RequestBody CommandExecuteRequest request) throws Exception {
        return SuiteApiResponse.ok(CommandExecutionContext.runAs(
                CommandExecutionContext.Source.API,
                () -> commandRegistry.executeRaw(request.line())
        ));
    }
}
