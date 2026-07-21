package com.takesome.springsuite.app;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Last-resort crash reporting that deliberately does not depend on the Spring context.
 * It is installed before configuration, modules and the embedded web server start.
 */
public final class SpringSuiteCrashReporter {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS z", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final AtomicBoolean DIALOG_ACTIVE = new AtomicBoolean();
    private static volatile String[] launchArguments = new String[0];

    private SpringSuiteCrashReporter() {
    }

    public static void install(String[] args) {
        launchArguments = args == null ? new String[0] : args.clone();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            try {
                report("Необработанная ошибка в потоке " + safeThreadName(thread), failure, false);
            } catch (Throwable reporterFailure) {
                reporterFailure.printStackTrace(System.err);
                failure.printStackTrace(System.err);
            }
        });
    }

    public static void reportStartupFailure(Throwable failure) {
        report("SpringSuite не смог запуститься", failure, true);
    }

    public static Path report(String reason, Throwable failure, boolean fatal) {
        Throwable actualFailure = failure == null
                ? new IllegalStateException("Неизвестная ошибка без Throwable")
                : failure;
        Instant now = Instant.now();
        String report = buildReport(reason, actualFailure, now, fatal);
        Path reportPath = writeReport(report, now);

        System.err.println("[SpringSuite][CRASH] " + reason);
        System.err.println("[SpringSuite][CRASH] report: " + reportPath);
        actualFailure.printStackTrace(System.err);

        if (!GraphicsEnvironment.isHeadless() && DIALOG_ACTIVE.compareAndSet(false, true)) {
            showDialog(reason, actualFailure, report, reportPath, fatal);
        }
        return reportPath;
    }

    private static String buildReport(String reason, Throwable failure, Instant now, boolean fatal) {
        StringBuilder report = new StringBuilder(32_768);
        report.append("SpringSuite crash report\n")
                .append("========================\n\n")
                .append("Time: ").append(DISPLAY_TIMESTAMP.format(now)).append('\n')
                .append("Reason: ").append(nonBlank(reason, "Unknown failure")).append('\n')
                .append("Fatal startup failure: ").append(fatal).append('\n')
                .append("Exception: ").append(failure.getClass().getName()).append('\n')
                .append("Message: ").append(nonBlank(failure.getMessage(), "<no message>")).append("\n\n");

        report.append("Runtime\n-------\n")
                .append("SpringSuite version: ").append(System.getProperty("suite.version", "unknown")).append('\n')
                .append("SpringSuite build: ").append(System.getProperty("suite.build", "unknown")).append('\n')
                .append("PID: ").append(ProcessHandle.current().pid()).append('\n')
                .append("Working directory: ").append(resolveWorkingDirectory()).append('\n')
                .append("Java: ").append(System.getProperty("java.runtime.name", ""))
                .append(' ').append(System.getProperty("java.runtime.version", "")).append('\n')
                .append("Java home: ").append(System.getProperty("java.home", "")).append('\n')
                .append("OS: ").append(System.getProperty("os.name", ""))
                .append(' ').append(System.getProperty("os.version", ""))
                .append(" (" ).append(System.getProperty("os.arch", "")).append(")\n")
                .append("Thread: ").append(Thread.currentThread().getName()).append('\n')
                .append("Command: ").append(sanitizedCommand()).append('\n');

        Runtime runtime = Runtime.getRuntime();
        report.append("Heap max: ").append(runtime.maxMemory()).append('\n')
                .append("Heap total: ").append(runtime.totalMemory()).append('\n')
                .append("Heap free: ").append(runtime.freeMemory()).append("\n\n");

        report.append("Stack trace\n-----------\n")
                .append(stackTrace(failure)).append('\n');

        appendThreadDump(report);
        appendRecentLog(report);
        appendNativeCrashReference(report);
        return report.toString();
    }

    private static void appendThreadDump(StringBuilder report) {
        report.append("\nThread dump\n-----------\n");
        try {
            Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
            traces.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
                    .forEach(entry -> {
                        Thread thread = entry.getKey();
                        report.append('"').append(thread.getName()).append('"')
                                .append(" id=").append(thread.getId())
                                .append(" state=").append(thread.getState())
                                .append(" daemon=").append(thread.isDaemon()).append('\n');
                        for (StackTraceElement element : entry.getValue()) {
                            report.append("    at ").append(element).append('\n');
                        }
                        report.append('\n');
                    });
        } catch (Throwable failure) {
            report.append("Thread dump unavailable: ").append(failure).append('\n');
        }
    }

    private static void appendRecentLog(StringBuilder report) {
        report.append("\nRecent SpringSuite log\n----------------------\n");
        Path log = resolveWorkingDirectory().resolve("logs").resolve("spring-suite.log");
        if (!Files.isRegularFile(log)) {
            report.append("Log file not found: ").append(log).append('\n');
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(log);
            int start = Math.max(0, bytes.length - 65_536);
            report.append(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
            if (start > 0) {
                report.append("\n[Only the last 65536 bytes are included.]\n");
            }
        } catch (Throwable failure) {
            report.append("Unable to read log: ").append(failure).append('\n');
        }
    }

    private static void appendNativeCrashReference(StringBuilder report) {
        Path root = resolveWorkingDirectory();
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "hs_err_pid*.log")) {
            stream.forEach(candidates::add);
        } catch (Throwable ignored) {
            // Best-effort diagnostics only.
        }
        Optional<Path> newest = candidates.stream().max(Comparator.comparingLong(SpringSuiteCrashReporter::lastModified));
        newest.ifPresent(path -> report.append("\nNative JVM crash log\n--------------------\n")
                .append(path.toAbsolutePath().normalize()).append('\n'));
    }

    private static Path writeReport(String report, Instant now) {
        Path directory = resolveWorkingDirectory().resolve("logs").resolve("crash");
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve("spring-suite-crash-" + FILE_TIMESTAMP.format(now) + ".txt");
            Files.writeString(target, report, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return target.toAbsolutePath().normalize();
        } catch (Throwable failure) {
            Path fallback = Path.of(System.getProperty("java.io.tmpdir", "."))
                    .resolve("spring-suite-crash-" + FILE_TIMESTAMP.format(now) + ".txt");
            try {
                Files.writeString(fallback, report, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                return fallback.toAbsolutePath().normalize();
            } catch (Throwable ignored) {
                return fallback.toAbsolutePath().normalize();
            }
        }
    }

    private static void showDialog(
            String reason,
            Throwable failure,
            String report,
            Path reportPath,
            boolean fatal
    ) {
        Runnable action = () -> {
            try {
                JDialog dialog = new JDialog();
                dialog.setTitle("SpringSuite — аварийная ошибка");
                dialog.setModal(true);
                dialog.setAlwaysOnTop(true);
                dialog.setDefaultCloseOperation(fatal
                        ? WindowConstants.DO_NOTHING_ON_CLOSE
                        : WindowConstants.DISPOSE_ON_CLOSE);

                JLabel heading = new JLabel("<html><b>SpringSuite столкнулся с ошибкой</b><br>"
                        + html(reason) + "<br><small>Отчёт: " + html(reportPath.toString()) + "</small></html>");
                heading.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

                JTextArea details = new JTextArea(report);
                details.setEditable(false);
                details.setCaretPosition(0);
                details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                details.setLineWrap(false);
                JScrollPane scroll = new JScrollPane(details);
                scroll.setPreferredSize(new Dimension(920, 560));

                JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                JButton copy = new JButton("Копировать текст");
                copy.addActionListener(event -> copyToClipboard(report));
                buttons.add(copy);

                JButton openFolder = new JButton("Открыть папку отчёта");
                openFolder.addActionListener(event -> openReportFolder(reportPath));
                buttons.add(openFolder);

                JButton restart = new JButton("Перезапустить SpringSuite");
                restart.addActionListener(event -> {
                    try {
                        restartProcess();
                        dialog.dispose();
                        System.exit(70);
                    } catch (Throwable restartFailure) {
                        JOptionPane.showMessageDialog(dialog,
                                "Не удалось перезапустить SpringSuite:\n" + restartFailure,
                                "Ошибка перезапуска",
                                JOptionPane.ERROR_MESSAGE);
                    }
                });
                buttons.add(restart);

                JButton close = new JButton(fatal ? "Закрыть SpringSuite" : "Закрыть окно");
                close.addActionListener(event -> {
                    dialog.dispose();
                    if (fatal) {
                        System.exit(1);
                    }
                });
                buttons.add(close);

                dialog.getContentPane().setLayout(new BorderLayout(8, 8));
                dialog.getContentPane().add(heading, BorderLayout.NORTH);
                dialog.getContentPane().add(scroll, BorderLayout.CENTER);
                dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
                dialog.pack();
                dialog.setLocationRelativeTo(null);
                dialog.setVisible(true);
            } catch (Throwable uiFailure) {
                System.err.println("[SpringSuite][CRASH] crash dialog failed: " + uiFailure);
            } finally {
                DIALOG_ACTIVE.set(false);
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeAndWait(action);
            }
        } catch (Throwable failureToShow) {
            DIALOG_ACTIVE.set(false);
            System.err.println("[SpringSuite][CRASH] unable to display crash dialog: " + failureToShow);
            System.err.println(stackTrace(failure));
        }
    }

    private static void copyToClipboard(String report) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(report), null);
        } catch (Throwable failure) {
            System.err.println("[SpringSuite][CRASH] clipboard unavailable: " + failure);
        }
    }

    private static void openReportFolder(Path reportPath) {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IllegalStateException("Desktop API is unavailable");
            }
            Path directory = reportPath.toAbsolutePath().normalize().getParent();
            Desktop.getDesktop().open(directory.toFile());
        } catch (Throwable failure) {
            JOptionPane.showMessageDialog(null,
                    "Не удалось открыть папку:\n" + failure,
                    "SpringSuite",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void restartProcess() throws Exception {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String command = info.command().orElseGet(SpringSuiteCrashReporter::javaExecutable);
        String[] arguments = info.arguments().orElse(new String[0]);
        List<String> restart = new ArrayList<>();
        restart.add(command);
        restart.addAll(Arrays.asList(arguments));

        if (arguments.length == 0) {
            String javaCommand = System.getProperty("sun.java.command", "").trim();
            if (javaCommand.isBlank()) {
                throw new IllegalStateException("Не удалось восстановить команду запуска");
            }
            String firstToken = firstCommandToken(javaCommand);
            restart.add("-jar");
            restart.add(firstToken);
            restart.addAll(Arrays.asList(launchArguments));
        }

        new ProcessBuilder(restart)
                .directory(resolveWorkingDirectory().toFile())
                .inheritIO()
                .start();
    }

    private static String firstCommandToken(String command) {
        if (command.startsWith("\"")) {
            int closing = command.indexOf('"', 1);
            return closing > 1 ? command.substring(1, closing) : command.substring(1);
        }
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    private static String javaExecutable() {
        String executable = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home", ""), "bin", executable).toString();
    }

    private static String sanitizedCommand() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        String executable = ProcessHandle.current().info().command().orElseGet(SpringSuiteCrashReporter::javaExecutable);
        String javaCommand = System.getProperty("sun.java.command", "").trim();
        StringBuilder command = new StringBuilder(executable);
        for (String argument : runtime.getInputArguments()) {
            command.append(' ').append(isSensitive(argument) ? "<redacted>" : argument);
        }
        if (!javaCommand.isBlank()) {
            command.append(' ').append(firstCommandToken(javaCommand));
        }
        for (String argument : launchArguments) {
            command.append(' ').append(isSensitive(argument) ? "<redacted>" : argument);
        }
        return command.toString().trim();
    }

    private static boolean isSensitive(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("api-key")
                || normalized.contains("apikey")
                || normalized.contains("credential");
    }

    private static Path resolveWorkingDirectory() {
        String configured = firstNonBlank(
                System.getProperty("suite.working.directory"),
                System.getProperty("suite.working.dir"),
                System.getProperty("suite.home"),
                System.getProperty("user.dir")
        );
        try {
            return Path.of(configured).toAbsolutePath().normalize();
        } catch (Throwable ignored) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private static String stackTrace(Throwable failure) {
        StringWriter buffer = new StringWriter(8192);
        try (PrintWriter writer = new PrintWriter(buffer)) {
            failure.printStackTrace(writer);
        }
        return buffer.toString();
    }

    private static String html(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    private static String safeThreadName(Thread thread) {
        return thread == null ? "unknown" : nonBlank(thread.getName(), "unnamed");
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return ".";
    }
}
