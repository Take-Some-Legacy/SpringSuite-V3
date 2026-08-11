package com.takesome.springsuite.dashboardmodule;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.module.SuiteModuleManifest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DashboardShellCommand implements SuiteCommand {
    private final SuiteModuleManifest manifest;
    private final DashboardRenderer renderer;

    public DashboardShellCommand(SuiteModuleManifest manifest, DashboardRenderer renderer) {
        this.manifest = manifest;
        this.renderer = renderer;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "shell",
                List.of("sh", "bash", "cli"),
                "modules",
                "Enter a safe bash-like dashboard shell provided by an external module jar.",
                "Provides shell-like built-ins without arbitrary process execution: help, pwd, ls, cd, date, uptime, dashboard, clear, exit/back.",
                "shell [--help|-c <builtin>]",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) throws Exception {
        if (invocation.args().stream().anyMatch(arg -> arg.equals("--help") || arg.equals("-h") || arg.equals("help"))) {
            return help();
        }
        int commandIndex = invocation.args().indexOf("-c");
        if (commandIndex >= 0 && commandIndex + 1 < invocation.args().size()) {
            Path cwd = runtimeRoot();
            String command = String.join(" ", invocation.args().subList(commandIndex + 1, invocation.args().size()));
            return CommandExecutionResult.ok("shell command executed", Map.of(
                    "command", command,
                    "output", executeBuiltin(command, cwd).output()
            ));
        }
        if (System.console() == null) {
            return CommandExecutionResult.ok("shell is available only from the attached console; use shell -c <builtin> for non-interactive calls", Map.of(
                    "interactive", false,
                    "builtins", builtins()
            ));
        }
        return interactiveShell();
    }

    private CommandExecutionResult interactiveShell() throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Path cwd = runtimeRoot();
        int commands = 0;
        System.out.println("SpringSuite bash-like shell from " + manifest.id() + " " + manifest.version());
        System.out.println("Built-ins: " + String.join(", ", builtins()));
        System.out.println("Type 'back', 'exit', 'q', or press Ctrl+C to return to the main CLI.");
        while (true) {
            System.out.print("suite:" + promptPath(cwd) + "$ ");
            System.out.flush();
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isBlank()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.equals("exit") || lower.equals("back") || lower.equals("q") || lower.equals("quit")) {
                break;
            }
            BuiltinResult result = executeBuiltin(line, cwd);
            if (result.cwd() != null) {
                cwd = result.cwd();
            }
            if (!result.output().isBlank()) {
                System.out.print(result.output());
                if (!result.output().endsWith("\n")) {
                    System.out.println();
                }
            }
            commands++;
        }
        return CommandExecutionResult.ok("returned from dashboard shell", Map.of(
                "interactive", true,
                "commands", commands,
                "moduleId", manifest.id()
        ));
    }

    private BuiltinResult executeBuiltin(String raw, Path cwd) {
        List<String> tokens = split(raw);
        if (tokens.isEmpty()) {
            return new BuiltinResult("", cwd);
        }
        String command = tokens.get(0).toLowerCase(Locale.ROOT);
        try {
            return switch (command) {
                case "help", "?" -> new BuiltinResult(helpText(), cwd);
                case "pwd" -> new BuiltinResult(cwd.toString() + "\n", cwd);
                case "date" -> new BuiltinResult(Instant.now().toString() + "\n", cwd);
                case "uptime" -> new BuiltinResult(renderer.snapshot().get("uptime") + "\n", cwd);
                case "dashboard", "dash", "top" -> new BuiltinResult(renderer.render(false), cwd);
                case "clear", "cls" -> new BuiltinResult(TerminalAnsi.CLEAR, cwd);
                case "ls", "dir" -> new BuiltinResult(list(cwd, tokens.size() > 1 ? tokens.get(1) : "."), cwd);
                case "cd" -> changeDirectory(cwd, tokens.size() > 1 ? tokens.get(1) : System.getProperty("user.home", "."));
                default -> new BuiltinResult("unknown builtin: " + command + "\n" + helpText(), cwd);
            };
        } catch (Exception ex) {
            return new BuiltinResult("error: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()) + "\n", cwd);
        }
    }

    private BuiltinResult changeDirectory(Path cwd, String target) {
        Path next = resolve(cwd, target);
        if (!Files.isDirectory(next)) {
            return new BuiltinResult("not a directory: " + target + "\n", cwd);
        }
        return new BuiltinResult("", next);
    }

    private String list(Path cwd, String target) throws Exception {
        Path path = resolve(cwd, target);
        if (!Files.isDirectory(path)) {
            return "not a directory: " + target + "\n";
        }
        StringBuilder out = new StringBuilder();
        try (var stream = Files.list(path)) {
            stream.sorted(Comparator.comparing(item -> item.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(item -> out.append(item.getFileName()).append(Files.isDirectory(item) ? "/" : "").append("\n"));
        }
        return out.toString();
    }

    private Path resolve(Path cwd, String raw) {
        Path path = Paths.get(raw == null || raw.isBlank() ? "." : raw);
        return path.isAbsolute() ? path.toAbsolutePath().normalize() : cwd.resolve(path).toAbsolutePath().normalize();
    }

    private Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("user.dir"))).toAbsolutePath().normalize();
    }

    private String promptPath(Path cwd) {
        Path root = runtimeRoot();
        try {
            String rel = root.relativize(cwd).toString().replace('\\', '/');
            return rel.isBlank() ? "~" : "~/" + rel;
        } catch (IllegalArgumentException ex) {
            return cwd.getFileName() == null ? cwd.toString() : cwd.getFileName().toString();
        }
    }

    private List<String> split(String raw) {
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !quoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private CommandExecutionResult help() {
        return CommandExecutionResult.ok("dashboard shell help", Map.of(
                "usage", descriptor().usage(),
                "builtins", builtins(),
                "note", "This is a safe module-local shell; it does not execute arbitrary OS processes."
        ));
    }

    private List<String> builtins() {
        return List.of("help", "pwd", "ls", "cd", "date", "uptime", "dashboard", "clear", "back", "exit");
    }

    private String helpText() {
        return "builtins: " + String.join(", ", builtins()) + "\n";
    }

    private record BuiltinResult(String output, Path cwd) {
    }
}
