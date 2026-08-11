package com.takesome.springsuite.logging;

import com.takesome.springsuite.core.process.ManagedProcess;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Establishes UTF-8 at the process boundary before Spring, Logback, Jansi or AWT
 * create console-facing objects. This is especially important on Java 17 and
 * Windows installations whose legacy ANSI code page is not UTF-8.
 */
public final class ConsoleEncodingBootstrap {
    private static volatile boolean installed;

    private ConsoleEncodingBootstrap() {
    }

    public static synchronized void installUtf8() {
        if (installed) {
            return;
        }

        System.setProperty("file.encoding", StandardCharsets.UTF_8.name());
        System.setProperty("stdout.encoding", StandardCharsets.UTF_8.name());
        System.setProperty("stderr.encoding", StandardCharsets.UTF_8.name());
        System.setProperty("sun.stdout.encoding", StandardCharsets.UTF_8.name());
        System.setProperty("sun.stderr.encoding", StandardCharsets.UTF_8.name());

        if (isWindows()) {
            switchWindowsConsoleToUtf8();
        }

        try {
            System.setOut(new PrintStream(
                    new FileOutputStream(FileDescriptor.out),
                    true,
                    StandardCharsets.UTF_8
            ));
            System.setErr(new PrintStream(
                    new FileOutputStream(FileDescriptor.err),
                    true,
                    StandardCharsets.UTF_8
            ));
        } catch (Exception ignored) {
            // Preserve the JVM-provided streams if the runtime forbids replacing them.
        }
        installed = true;
    }

    public static boolean isInstalled() {
        return installed;
    }

    private static void switchWindowsConsoleToUtf8() {
        try {
            ManagedProcess managed = ManagedProcess.start(
                    new ProcessBuilder(
                            "cmd.exe",
                            "/d",
                            "/c",
                            "chcp 65001 >nul"
                    ),
                    "console-encoding",
                    Duration.ofMillis(100),
                    Duration.ofSeconds(1)
            );
            Process process = managed.process();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                managed.terminate(Duration.ofMillis(100), Duration.ofSeconds(1));
            } else {
                managed.complete();
            }
        } catch (Exception ignored) {
            // A GUI launch may have no attached console. UTF-8 PrintStreams still protect logs and pipes.
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
