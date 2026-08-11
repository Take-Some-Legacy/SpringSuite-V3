package com.takesome.springsuite.openai;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCommand implements SuiteCommand {
    private final OpenAiTokenProvider tokenProvider;
    private final OpenAiClient client;
    private final OpenAiBrowserSetupService browserSetup;
    private final OpenAiLocalCredentialStore localCredentialStore;

    public OpenAiCommand(OpenAiTokenProvider tokenProvider, OpenAiClient client, OpenAiBrowserSetupService browserSetup, OpenAiLocalCredentialStore localCredentialStore) {
        this.tokenProvider = tokenProvider;
        this.client = client;
        this.browserSetup = browserSetup;
        this.localCredentialStore = localCredentialStore;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "openai",
                List.of("oai"),
                "ai",
                "Inspect, bind or use the SpringSuite OpenAI integration.",
                "Commands: openai status, openai setup, openai refresh, openai ask <prompt>.",
                "openai status|setup|refresh|ask <prompt>",
                CommandRiskLevel.LOCAL_MUTATION
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        List<String> args = invocation.args();
        String subcommand = args.isEmpty() ? "status" : args.get(0).toLowerCase();
        return switch (subcommand) {
            case "status" -> status();
            case "setup", "login", "bind" -> setup();
            case "refresh" -> refresh();
            case "ask", "responses", "response" -> ask(args.size() <= 1 ? "" : String.join(" ", args.subList(1, args.size())));
            default -> CommandExecutionResult.failed("openai_unknown_subcommand", "Unknown openai subcommand: " + subcommand);
        };
    }

    private CommandExecutionResult status() {
        OpenAiCredentialStatus status = tokenProvider.status();
        OpenAiLocalCredentialStatus local = localCredentialStore.status();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("localCredential", local);
        data.put("setupUrl", browserSetup.localSetupUrl());
        data.put("_stdout", renderStatus(status, local));
        data.put("_consoleRaw", true);
        return new CommandExecutionResult(status.available(), status.available() ? "ok" : "openai_unavailable", status.message(), data, Instant.now());
    }

    private CommandExecutionResult setup() {
        OpenAiBrowserLaunchResult launch = browserSetup.openSetupInBrowser();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("browser", launch);
        data.put("setupUrl", launch.url());
        data.put("_stdout", "OpenAI setup URL: " + launch.url() + System.lineSeparator() + launch.message() + System.lineSeparator());
        data.put("_consoleRaw", true);
        return new CommandExecutionResult(true, launch.opened() ? "ok" : "openai_browser_open_failed", launch.message(), data, Instant.now());
    }

    private CommandExecutionResult refresh() {
        OpenAiCredentialStatus status = tokenProvider.refresh();
        OpenAiLocalCredentialStatus local = localCredentialStore.status();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("localCredential", local);
        data.put("_stdout", renderStatus(status, local));
        data.put("_consoleRaw", true);
        return new CommandExecutionResult(status.available(), status.available() ? "ok" : "openai_refresh_failed", status.message(), data, Instant.now());
    }

    private CommandExecutionResult ask(String input) {
        if (input == null || input.isBlank()) {
            return CommandExecutionResult.failed("openai_empty_prompt", "Usage: openai ask <prompt>");
        }
        OpenAiResponseResult result = client.createResponse(new OpenAiResponseRequest(input, "", null, null, null, Map.of("source", "spring-suite-console")));
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("result", result);
        data.put("_stdout", result.ok() ? result.outputText() : result.errorMessage());
        data.put("_consoleRaw", true);
        return new CommandExecutionResult(result.ok(), result.ok() ? "ok" : "openai_response_failed", result.ok() ? "OpenAI response created" : result.errorMessage(), data, Instant.now());
    }

    private String renderStatus(OpenAiCredentialStatus status, OpenAiLocalCredentialStatus local) {
        return "OpenAI: " + (status.available() ? "READY" : "UNAVAILABLE") + System.lineSeparator()
                + "mode=" + status.mode() + " kind=" + status.credentialKind() + " source=" + status.source() + System.lineSeparator()
                + "fingerprint=" + status.fingerprint() + System.lineSeparator()
                + "expiresAt=" + status.expiresAt() + " refreshAt=" + status.refreshAt() + System.lineSeparator()
                + "cache=" + status.cachePath() + " cached=" + status.cached() + System.lineSeparator()
                + "localLinked=" + local.linked() + " localPath=" + local.path() + System.lineSeparator()
                + "setupUrl=" + browserSetup.localSetupUrl() + System.lineSeparator()
                + "message=" + status.message() + System.lineSeparator();
    }
}
