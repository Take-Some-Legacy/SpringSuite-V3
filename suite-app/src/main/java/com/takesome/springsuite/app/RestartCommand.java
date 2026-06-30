package com.takesome.springsuite.app;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRegistry;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class RestartCommand implements SuiteCommand {
    public static final int RESTART_EXIT_CODE = 42;
    private final ConfigurableApplicationContext context;
    private final ObjectProvider<CommandRegistry> commandRegistryProvider;
    private final OperatorLogService logService;
    private final AtomicBoolean restartRequested = new AtomicBoolean(false);

    public RestartCommand(ConfigurableApplicationContext context, ObjectProvider<CommandRegistry> commandRegistryProvider, OperatorLogService logService) {
        this.context = context;
        this.commandRegistryProvider = commandRegistryProvider;
        this.logService = logService;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "restart",
                List.of("reboot", "suite-restart"),
                "process",
                "Gracefully restart SpringSuite when the command layer is idle.",
                "Schedules a restart after the command returns. It refuses to stop while another command worker is active unless --force is supplied. The process exits with code 42 for launcher-supervised relaunch.",
                "restart [--force] [--delay N] [--dry-run]",
                CommandRiskLevel.PROCESS_CONTROL
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        boolean force = hasFlag(invocation, "--force");
        boolean dryRun = hasFlag(invocation, "--dry-run");
        long delaySeconds = parseDelaySeconds(invocation);
        int active = commandRegistryProvider.getObject().activeExecutions();
        if (!force && active > 1) {
            return CommandExecutionResult.failed("restart_busy", "Refusing restart: another command worker is active. Re-run with --force only if you accept interrupting active work.");
        }
        if (dryRun) {
            return CommandExecutionResult.ok("restart dry run", Map.of("activeExecutions", active, "delaySeconds", delaySeconds, "force", force, "exitCode", RESTART_EXIT_CODE));
        }
        if (!restartRequested.compareAndSet(false, true)) {
            return CommandExecutionResult.failed("restart_already_requested", "A restart request is already scheduled.");
        }
        Thread thread = new Thread(() -> runRestartAfterDelay(delaySeconds, force), "suite-restart-scheduler");
        thread.setDaemon(false);
        thread.start();
        return CommandExecutionResult.ok("restart scheduled", Map.of("activeExecutions", active, "delaySeconds", delaySeconds, "force", force, "exitCode", RESTART_EXIT_CODE));
    }

    private void runRestartAfterDelay(long delaySeconds, boolean force) {
        try {
            Thread.sleep(Math.max(1L, delaySeconds) * 1000L);
            int activeNow = commandRegistryProvider.getObject().activeExecutions();
            if (!force && activeNow > 0) {
                restartRequested.set(false);
                logService.append(OperatorLogLevel.WARN, "process", "restart cancelled because command workers are active", Map.of("activeExecutions", activeNow));
                return;
            }
            logService.append(OperatorLogLevel.WARN, "process", "spring suite graceful restart requested", Map.of("exitCode", RESTART_EXIT_CODE, "force", force));
            int code = SpringApplication.exit(context, () -> RESTART_EXIT_CODE);
            System.exit(code);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            restartRequested.set(false);
            logService.append(OperatorLogLevel.WARN, "process", "restart scheduler interrupted");
        } catch (Exception ex) {
            restartRequested.set(false);
            logService.append(OperatorLogLevel.ERROR, "process", "restart failed", Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    private boolean hasFlag(CommandInvocation invocation, String flag) {
        return invocation.args().stream().anyMatch(arg -> arg.equalsIgnoreCase(flag));
    }

    private long parseDelaySeconds(CommandInvocation invocation) {
        for (int i = 0; i < invocation.args().size(); i++) {
            String arg = invocation.args().get(i).toLowerCase(Locale.ROOT);
            if ((arg.equals("--delay") || arg.equals("-d")) && i + 1 < invocation.args().size()) {
                try {
                    return Math.max(1L, Math.min(60L, Long.parseLong(invocation.args().get(i + 1))));
                } catch (NumberFormatException ignored) {
                    return 2L;
                }
            }
        }
        return 2L;
    }
}
