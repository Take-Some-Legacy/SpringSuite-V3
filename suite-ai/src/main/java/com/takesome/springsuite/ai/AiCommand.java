package com.takesome.springsuite.ai;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiChatResponse;
import com.takesome.springsuite.core.ai.AiCredentialStatus;
import com.takesome.springsuite.core.ai.AiGenerationOptions;
import com.takesome.springsuite.core.ai.AiMessage;
import com.takesome.springsuite.core.ai.AiProviderDescriptor;
import com.takesome.springsuite.core.ai.AiService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AiCommand implements SuiteCommand {
    private final AiService aiService;

    public AiCommand(AiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "ai",
                List.of("llm"),
                "ai",
                "Use the provider-agnostic SpringSuite AI service.",
                "Commands: ai providers, ai status [provider], ai ask [--provider id] [--model id] <prompt>.",
                "ai providers|status [provider]|ask [--provider id] [--model id] <prompt>",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        List<String> args = invocation.args();
        String subcommand = args.isEmpty() ? "status" : args.get(0).toLowerCase();
        return switch (subcommand) {
            case "providers", "list" -> providers();
            case "default" -> defaultProvider();
            case "status" -> status(args.size() > 1 ? args.get(1) : "");
            case "ask", "chat" -> ask(args.size() <= 1 ? List.of() : args.subList(1, args.size()));
            case "setup" -> setup(args.size() > 1 ? args.get(1) : "");
            default -> CommandExecutionResult.failed("ai_unknown_subcommand", "Unknown ai subcommand: " + subcommand);
        };
    }

    private CommandExecutionResult providers() {
        List<AiProviderDescriptor> providers = aiService.providers();
        StringBuilder out = new StringBuilder();
        out.append("AI providers:").append(System.lineSeparator());
        for (AiProviderDescriptor provider : providers) {
            out.append("- ").append(provider.id())
                    .append(" type=").append(provider.type())
                    .append(" vendor=").append(provider.vendor())
                    .append(" model=").append(provider.defaultModel())
                    .append(" enabled=").append(provider.enabled())
                    .append(System.lineSeparator());
        }
        return result(true, "ok", "AI providers listed", Map.of("providers", providers), out.toString());
    }

    private CommandExecutionResult defaultProvider() {
        AiProviderDescriptor provider = aiService.defaultProvider();
        return result(true, "ok", "default AI provider", Map.of("provider", provider), "Default AI provider: " + provider.id() + " model=" + provider.defaultModel() + System.lineSeparator());
    }

    private CommandExecutionResult status(String providerId) {
        try {
            AiCredentialStatus status = aiService.status(providerId);
            String out = "AI status: " + (status.available() ? "READY" : "UNAVAILABLE") + System.lineSeparator()
                    + "provider=" + status.providerId() + " kind=" + status.credentialKind() + " source=" + status.source() + System.lineSeparator()
                    + "fingerprint=" + status.fingerprint() + System.lineSeparator()
                    + "message=" + status.message() + System.lineSeparator();
            return result(status.available(), status.available() ? "ok" : "ai_unavailable", status.message(), Map.of("status", status), out);
        } catch (RuntimeException ex) {
            return CommandExecutionResult.failed("ai_status_failed", safeMessage(ex));
        }
    }

    private CommandExecutionResult ask(List<String> args) {
        ParsedAsk parsed = parseAsk(args);
        if (parsed.prompt().isBlank()) {
            return CommandExecutionResult.failed("ai_empty_prompt", "Usage: ai ask [--provider id] [--model id] <prompt>");
        }
        AiChatRequest request = new AiChatRequest(
                parsed.providerId(),
                parsed.model(),
                List.of(AiMessage.user(parsed.prompt())),
                new AiGenerationOptions(null, null, null, false, "", null, null, Map.of()),
                List.of(),
                Map.of("source", "spring-suite-console")
        );
        AiChatResponse response = aiService.chat(request);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("response", response);
        data.put("_stdout", response.ok() ? response.outputText() : response.errorMessage());
        data.put("_consoleRaw", true);
        return new CommandExecutionResult(response.ok(), response.ok() ? "ok" : response.errorCode(), response.ok() ? "AI response created" : response.errorMessage(), data, Instant.now());
    }

    private CommandExecutionResult setup(String provider) {
        String resolved = provider == null || provider.isBlank() ? "openai" : provider.trim();
        String message = switch (resolved) {
            case "openai" -> "OpenAI browser setup: http://localhost:8090/openai/setup";
            case "zai", "glm", "glm-5.2" -> "Z.ai/GLM provider uses ZAI_API_KEY for now. Configure suite.ai.providers.zai or set env ZAI_API_KEY.";
            default -> "No browser setup registered for provider: " + resolved;
        };
        return result(true, "ok", message, Map.of("provider", resolved), message + System.lineSeparator());
    }

    private ParsedAsk parseAsk(List<String> args) {
        String provider = "";
        String model = "";
        ArrayList<String> prompt = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ((arg.equals("--provider") || arg.equals("-p")) && i + 1 < args.size()) {
                provider = args.get(++i);
                continue;
            }
            if ((arg.equals("--model") || arg.equals("-m")) && i + 1 < args.size()) {
                model = args.get(++i);
                continue;
            }
            prompt.add(arg);
        }
        return new ParsedAsk(provider, model, String.join(" ", prompt));
    }

    private CommandExecutionResult result(boolean ok, String code, String message, Map<String, Object> data, String stdout) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>(data);
        out.put("_stdout", stdout);
        out.put("_consoleRaw", true);
        return new CommandExecutionResult(ok, code, message, out, Instant.now());
    }

    private String safeMessage(Throwable ex) {
        String message = ex == null ? "" : ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record ParsedAsk(String providerId, String model, String prompt) {
    }
}
