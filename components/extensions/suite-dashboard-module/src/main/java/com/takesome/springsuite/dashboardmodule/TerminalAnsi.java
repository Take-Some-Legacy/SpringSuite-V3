package com.takesome.springsuite.dashboardmodule;

public final class TerminalAnsi {
    public static final String RESET = "\033[0m";
    public static final String BOLD = "\033[1m";
    public static final String DIM = "\033[2m";
    public static final String RED = "\033[31m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String BLUE = "\033[34m";
    public static final String MAGENTA = "\033[35m";
    public static final String CYAN = "\033[36m";
    public static final String WHITE = "\033[37m";
    public static final String BRIGHT_BLACK = "\033[90m";
    public static final String BRIGHT_GREEN = "\033[92m";
    public static final String BRIGHT_YELLOW = "\033[93m";
    public static final String BRIGHT_BLUE = "\033[94m";
    public static final String BRIGHT_MAGENTA = "\033[95m";
    public static final String BRIGHT_CYAN = "\033[96m";
    public static final String CLEAR = "\033[2J\033[H";
    public static final String HOME = "\033[H";
    public static final String CLEAR_LINE = "\033[2K";
    public static final String HIDE_CURSOR = "\033[?25l";
    public static final String SHOW_CURSOR = "\033[?25h";

    private TerminalAnsi() {
    }

    public static String cursorTo(int row, int column) {
        return "\033[" + Math.max(1, row) + ";" + Math.max(1, column) + "H";
    }

    public static String color(String color, String text) {
        return color + text + RESET;
    }

    public static String healthColor(int percent) {
        if (percent >= 85) {
            return RED;
        }
        if (percent >= 65) {
            return YELLOW;
        }
        return BRIGHT_GREEN;
    }
}
