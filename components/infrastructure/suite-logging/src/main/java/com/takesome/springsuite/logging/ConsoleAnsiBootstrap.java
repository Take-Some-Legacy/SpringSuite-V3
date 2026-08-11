package com.takesome.springsuite.logging;

import java.io.PrintStream;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;

public final class ConsoleAnsiBootstrap {
    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;
    private static volatile boolean installed;

    private ConsoleAnsiBootstrap() {
    }

    public static void installEarly(boolean enabled, boolean probe) {
        if (!enabled) {
            return;
        }

        System.setProperty("logback.skipJansi", "false");
        System.setProperty("jansi.mode", "force");

        AnsiConsole.systemInstall();
        installed = true;

        if (probe) {
            AnsiConsole.out().println(Ansi.ansi()
                    .fgBrightCyan()
                    .a("[SpringSuite]")
                    .reset()
                    .a(" ")
                    .fgBrightGreen()
                    .a("JANSI early console bootstrap active")
                    .reset());
        }
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static synchronized void uninstall() {
        if (installed) {
            try {
                AnsiConsole.systemUninstall();
            } finally {
                installed = false;
                restorePlainStreams();
            }
        }
    }

    /**
     * Disable Jansi after an output parser failure and restore the original JVM streams.
     * This method must never propagate the original console failure into application code.
     */
    public static synchronized void disableAfterFailure(Throwable failure) {
        try {
            if (installed) {
                AnsiConsole.systemUninstall();
            }
        } catch (Throwable ignored) {
            // A broken ANSI stream must not be allowed to terminate SpringSuite.
        } finally {
            installed = false;
            System.setProperty("jansi.mode", "strip");
            System.setProperty("logback.skipJansi", "true");
            restorePlainStreams();
        }
    }

    public static PrintStream plainOut() {
        return ORIGINAL_OUT;
    }

    public static PrintStream plainErr() {
        return ORIGINAL_ERR;
    }

    private static void restorePlainStreams() {
        if (System.out != ORIGINAL_OUT) {
            System.setOut(ORIGINAL_OUT);
        }
        if (System.err != ORIGINAL_ERR) {
            System.setErr(ORIGINAL_ERR);
        }
    }
}
