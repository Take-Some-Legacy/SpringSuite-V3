package com.takesome.springsuite.logging;

import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;

public final class ConsoleAnsiBootstrap {
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

    public static void uninstall() {
        if (installed) {
            AnsiConsole.systemUninstall();
            installed = false;
        }
    }
}
