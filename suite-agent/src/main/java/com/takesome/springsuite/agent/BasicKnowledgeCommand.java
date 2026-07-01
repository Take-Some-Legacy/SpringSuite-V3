package com.takesome.springsuite.agent;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionContext;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BasicKnowledgeCommand implements SuiteCommand {
    private final BasicKnowledgeStore store;

    public BasicKnowledgeCommand(BasicKnowledgeStore store) {
        this.store = store;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "knowledge",
                List.of("bk", "basicKnowledge", "remember", "memorize", "recall"),
                "agent",
                "Store and search top-level BasicKnowledge shared across repositories.",
                "Persists global facts/constants such as company names, contacts and project-level invariants in .springsuite/basic-knowledge.json.",
                "knowledge add <key> <value...> [--tag tag] | knowledge search <query> | knowledge list | knowledge show <key|id> | knowledge dump",
                CommandRiskLevel.LOCAL_MUTATION
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        if (invocation.commandName().equals("remember") || invocation.commandName().equals("memorize")) {
            return add(invocation.args());
        }
        String mode = invocation.arg(0).toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "add", "set", "save", "remember", "fix", "record" -> add(invocation.args().subList(1, invocation.args().size()));
            case "search", "find", "grep" -> search(invocation.args().subList(1, invocation.args().size()));
            case "show", "get" -> show(invocation.args().subList(1, invocation.args().size()));
            case "list", "all", "ls" -> list();
            case "dump", "json" -> dump();
            case "help", "--help", "-h", "" -> help();
            default -> search(invocation.args());
        };
    }

    private CommandExecutionResult add(List<String> args) {
        if (args.size() < 2) {
            return CommandExecutionResult.failed("knowledge_usage", "Usage: knowledge add <key> <value...> [--tag tag] [--source source]");
        }
        String key = args.get(0);
        ArrayList<String> valueParts = new ArrayList<>();
        ArrayList<String> tags = new ArrayList<>();
        String source = "operator";
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if ((arg.equals("--tag") || arg.equals("-t")) && i + 1 < args.size()) {
                tags.add(args.get(++i));
                continue;
            }
            if ((arg.equals("--source") || arg.equals("-s")) && i + 1 < args.size()) {
                source = args.get(++i);
                continue;
            }
            valueParts.add(arg);
        }
        String value = String.join(" ", valueParts).trim();
        if (value.isBlank()) {
            return CommandExecutionResult.failed("knowledge_empty_value", "BasicKnowledge value is empty");
        }
        BasicKnowledgeItem item = store.remember(key, value, tags, source);
        return CommandExecutionResult.ok("basic knowledge saved", data(item, store.search("")));
    }

    private CommandExecutionResult search(List<String> args) {
        String query = String.join(" ", args).trim();
        List<BasicKnowledgeItem> results = store.search(query);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("count", results.size());
        data.put("items", results);
        data.put("path", store.storagePath().toString());
        data.put("_stdout", format(results));
        data.put("_consoleRaw", CommandExecutionContext.isConsole());
        return CommandExecutionResult.ok("basic knowledge search complete", data);
    }

    private CommandExecutionResult show(List<String> args) {
        if (args.isEmpty()) {
            return CommandExecutionResult.failed("knowledge_usage", "Usage: knowledge show <key|id>");
        }
        BasicKnowledgeItem item = store.get(String.join(" ", args));
        if (item == null) {
            return CommandExecutionResult.failed("knowledge_not_found", "BasicKnowledge item not found: " + String.join(" ", args));
        }
        return CommandExecutionResult.ok("basic knowledge item", data(item, List.of(item)));
    }

    private CommandExecutionResult list() {
        List<BasicKnowledgeItem> items = store.list();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("count", items.size());
        data.put("items", items);
        data.put("path", store.storagePath().toString());
        data.put("_stdout", format(items));
        data.put("_consoleRaw", CommandExecutionContext.isConsole());
        return CommandExecutionResult.ok("basic knowledge list", data);
    }

    private CommandExecutionResult dump() {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>(store.dump());
        return CommandExecutionResult.ok("basic knowledge dump", data);
    }

    private CommandExecutionResult help() {
        return CommandExecutionResult.ok("basic knowledge help", Map.of(
                "usage", descriptor().usage(),
                "examples", List.of(
                        "knowledge add company.name Take Some() --tag company",
                        "remember support.email support@example.com --tag contact",
                        "knowledge search company",
                        "knowledge list",
                        "knowledge show company.name",
                        "knowledge dump"
                ),
                "path", store.storagePath().toString()
        ));
    }

    private Map<String, Object> data(BasicKnowledgeItem item, List<BasicKnowledgeItem> visibleItems) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("item", item);
        data.put("count", visibleItems.size());
        data.put("items", visibleItems);
        data.put("path", store.storagePath().toString());
        data.put("_stdout", format(List.of(item)));
        data.put("_consoleRaw", CommandExecutionContext.isConsole());
        return data;
    }

    private String format(List<BasicKnowledgeItem> items) {
        if (items == null || items.isEmpty()) {
            return "basicKnowledge: empty\n";
        }
        StringBuilder out = new StringBuilder();
        for (BasicKnowledgeItem item : items) {
            out.append(item.key()).append(" = ").append(item.value());
            if (!item.tags().isEmpty()) {
                out.append("  # ").append(String.join(",", item.tags()));
            }
            out.append("\n");
        }
        return out.toString();
    }
}
