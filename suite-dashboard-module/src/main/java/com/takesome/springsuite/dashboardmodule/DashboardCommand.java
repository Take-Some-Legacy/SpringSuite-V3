package com.takesome.springsuite.dashboardmodule;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandExecutionContext;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.module.SuiteModuleManifest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DashboardCommand implements SuiteCommand {
    private final SuiteModuleManifest manifest;
    private final DashboardRenderer renderer;

    public DashboardCommand(SuiteModuleManifest manifest, DashboardRenderer renderer) {
        this.manifest = manifest;
        this.renderer = renderer;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "dashboard",
                List.of("dash", "top", "monitor"),
                "modules",
                "Render a UNIX-like SpringSuite dashboard with ASCII progress bars.",
                "Shows runtime health, memory, threads, module count and disk usage. Use 'dashboard watch' for a hotkey-return mini-screen.",
                "dashboard [once|watch|help] [--ticks N]",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) throws Exception {
        String mode = invocation.arg(0).toLowerCase(Locale.ROOT);
        if (mode.equals("help") || mode.equals("--help") || mode.equals("-h")) {
            return CommandExecutionResult.ok("dashboard help", Map.of(
                    "usage", descriptor().usage(),
                    "hotkeys", "q / Esc / Enter returns to main CLI in watch mode",
                    "examples", List.of("dashboard", "dashboard watch", "dashboard watch --ticks 10")
            ));
        }

        if (mode.isBlank() && CommandExecutionContext.isConsole()) {
            return watch(invocation);
        }
        if (mode.equals("watch") || mode.equals("w")) {
            return watch(invocation);
        }

        String screen = renderer.render(false);
        System.out.print(screen);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>(renderer.snapshot());
        data.put("screen", screen);
        data.put("moduleId", manifest.id());
        data.put("_consoleSilent", CommandExecutionContext.isConsole());
        return CommandExecutionResult.ok("dashboard rendered", data);
    }

    private CommandExecutionResult watch(CommandInvocation invocation) throws Exception {
        int ticks = parseTicks(invocation.args(), 60);
        boolean interactive = CommandExecutionContext.isConsole();
        if (!interactive) {
            String screen = renderer.render(false);
            System.out.print(screen);
            return CommandExecutionResult.ok("dashboard rendered once; no interactive console detected", Map.of(
                    "interactive", false,
                    "screen", screen,
                    "_consoleSilent", CommandExecutionContext.isConsole()
            ));
        }

        int rendered = 0;
        System.out.print(TerminalAnsi.HIDE_CURSOR);
        try {
            for (; rendered < ticks; rendered++) {
                clearScreen();
                System.out.print(renderer.render(true));
                System.out.print(TerminalAnsi.color(TerminalAnsi.BRIGHT_GREEN, "\n  dashboard/watch> live refresh: 1s | q / Esc / Enter -> main CLI "));
                System.out.flush();
                if (waitForReturnHotkey(1000)) {
                    break;
                }
            }
        } finally {
            System.out.print(TerminalAnsi.SHOW_CURSOR + TerminalAnsi.RESET);
        }
        System.out.println();
        return CommandExecutionResult.ok("dashboard watch returned to main CLI", Map.of(
                "interactive", true,
                "frames", rendered + 1,
                "moduleId", manifest.id(),
                "_consoleSilent", CommandExecutionContext.isConsole()
        ));
    }

    private boolean waitForReturnHotkey(long millis) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (System.in.available() > 0) {
                int ch = System.in.read();
                return ch == 'q' || ch == 'Q' || ch == 27 || ch == '\n' || ch == '\r';
            }
            Thread.sleep(50);
        }
        return false;
    }

    private int parseTicks(List<String> args, int fallback) {
        for (int i = 0; i < args.size(); i++) {
            String value = args.get(i);
            if ((value.equals("--ticks") || value.equals("-n")) && i + 1 < args.size()) {
                try {
                    return Math.max(1, Math.min(3600, Integer.parseInt(args.get(i + 1))));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private void clearScreen() {
        System.out.print(TerminalAnsi.CLEAR);
    }
}
