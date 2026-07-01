package com.takesome.springsuite.command;

import java.util.ArrayList;
import java.util.List;

final class CommandScriptParser {
    enum Operator { ALWAYS, ON_SUCCESS, ON_FAILURE }

    record Step(String command, Operator operator) {
        Step {
            command = command == null ? "" : command.trim();
            operator = operator == null ? Operator.ALWAYS : operator;
        }
    }

    private CommandScriptParser() { }

    static List<Step> parse(String line) {
        if (line == null || line.isBlank()) { return List.of(); }
        ArrayList<Step> steps = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        Operator nextOperator = Operator.ALWAYS;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (escaped) { current.append(ch); escaped = false; continue; }
            if (ch == 92) { escaped = true; current.append(ch); continue; }
            if (ch == 39 && !doubleQuoted) { singleQuoted = !singleQuoted; current.append(ch); continue; }
            if (ch == 34 && !singleQuoted) { doubleQuoted = !doubleQuoted; current.append(ch); continue; }
            if (!singleQuoted && !doubleQuoted) {
                if (ch == ';') { flush(steps, current, nextOperator); nextOperator = Operator.ALWAYS; continue; }
                if (ch == '&' && i + 1 < line.length() && line.charAt(i + 1) == '&') {
                    flush(steps, current, nextOperator); nextOperator = Operator.ON_SUCCESS; i++; continue;
                }
                if (ch == '|' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
                    flush(steps, current, nextOperator); nextOperator = Operator.ON_FAILURE; i++; continue;
                }
            }
            current.append(ch);
        }
        flush(steps, current, nextOperator);
        return List.copyOf(steps);
    }

    private static void flush(ArrayList<Step> steps, StringBuilder current, Operator operator) {
        String command = current.toString().trim();
        if (!command.isBlank()) { steps.add(new Step(command, operator)); }
        current.setLength(0);
    }
}
