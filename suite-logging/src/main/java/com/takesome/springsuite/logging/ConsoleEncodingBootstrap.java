package com.takesome.springsuite.logging;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
            Process process = new ProcessBuilder(
                    "cmd.exe",
                    "/d",
                    "/c",
                    "chcp 65001 >nul"
            ).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
            // A GUI launch may have no attached console. UTF-8 PrintStreams still protect logs and pipes.
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
