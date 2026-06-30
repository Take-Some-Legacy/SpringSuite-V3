package com.takesome.springsuite.command;

import java.util.ArrayList;
import java.util.List;

final class CommandTokenizer {
    private CommandTokenizer() {
    }

    static List<String> tokenize(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (escaped) {
                current.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !quoted) {
                flush(tokens, current);
                continue;
            }
            current.append(ch);
        }
        flush(tokens, current);
        return List.copyOf(tokens);
    }

    private static void flush(ArrayList<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }
}
