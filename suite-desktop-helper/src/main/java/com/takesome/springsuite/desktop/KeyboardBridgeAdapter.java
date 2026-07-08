package com.takesome.springsuite.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KeyboardBridgeAdapter implements DesktopBridgeAdapter {
    private static final String ID = "keyboard-bridge-adapter";

    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                ID,
                "Keyboard Bridge Adapter",
                false,
                true,
                List.of("real-input", "keyboard", "type", "hotkey", "submit", "awt-robot"),
                List.of("type", "hotkey", "submit"),
                Map.of(
                        "realDesktopInput", true,
                        "requiresPolicy", "suite.desktop-helper.bridge.allowed-real-input=true and bridges.keyboard-bridge-adapter.enabled=true",
                        "sideEffect", "Sends keyboard events to the currently focused OS window."
                )
        );
    }

    @Override
    public BridgeActionResult perform(BridgeActionContext context) {
        String actionId = context.action() == null ? "" : context.action().actionId();
        String action = context.action() == null ? "" : context.action().action();
        if (GraphicsEnvironment.isHeadless()) {
            return BridgeActionResult.failed("desktop_headless", "AWT keyboard input is unavailable in headless mode.", ID, actionId, List.of(), Map.of("headless", true));
        }
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(20);
            switch (action) {
                case "submit" -> press(robot, KeyEvent.VK_ENTER);
                case "hotkey" -> pressHotkey(robot, firstText(metadataText(context.action().metadata().get("keys")), context.action().value()));
                case "type" -> typeText(robot, context.action().value());
                default -> {
                    return BridgeActionResult.failed("keyboard_action_unsupported", "Unsupported keyboard action: " + action, ID, actionId, List.of(), Map.of("action", action));
                }
            }
            return new BridgeActionResult(
                    true,
                    "ok",
                    "Keyboard action sent through AWT Robot.",
                    ID,
                    actionId,
                    true,
                    true,
                    merge(context.guards(), List.of("bridge:keyboard", "input:keyboard-events")),
                    List.of(),
                    Map.of("action", action)
            );
        } catch (Exception ex) {
            return BridgeActionResult.failed("keyboard_input_failed", safeMessage(ex), ID, actionId, List.of("No complete keyboard action was performed."), Map.of("error", ex.getClass().getSimpleName()));
        }
    }

    private void typeText(Robot robot, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (char c : text.toCharArray()) {
            typeChar(robot, c);
        }
    }

    private void typeChar(Robot robot, char c) {
        if (c == '\n' || c == '\r') {
            press(robot, KeyEvent.VK_ENTER);
            return;
        }
        if (c == '\t') {
            press(robot, KeyEvent.VK_TAB);
            return;
        }
        KeyStroke stroke = strokeFor(c);
        if (stroke == null) {
            throw new IllegalArgumentException("Unsupported keyboard character: U+" + Integer.toHexString(c));
        }
        if (stroke.shift()) {
            robot.keyPress(KeyEvent.VK_SHIFT);
        }
        press(robot, stroke.keyCode());
        if (stroke.shift()) {
            robot.keyRelease(KeyEvent.VK_SHIFT);
        }
    }

    private KeyStroke strokeFor(char c) {
        if (c >= 'a' && c <= 'z') {
            return new KeyStroke(KeyEvent.VK_A + (c - 'a'), false);
        }
        if (c >= 'A' && c <= 'Z') {
            return new KeyStroke(KeyEvent.VK_A + (c - 'A'), true);
        }
        if (c >= '0' && c <= '9') {
            return new KeyStroke(KeyEvent.VK_0 + (c - '0'), false);
        }
        return switch (c) {
            case ' ' -> new KeyStroke(KeyEvent.VK_SPACE, false);
            case '.' -> new KeyStroke(KeyEvent.VK_PERIOD, false);
            case ',' -> new KeyStroke(KeyEvent.VK_COMMA, false);
            case '-' -> new KeyStroke(KeyEvent.VK_MINUS, false);
            case '_' -> new KeyStroke(KeyEvent.VK_MINUS, true);
            case '=' -> new KeyStroke(KeyEvent.VK_EQUALS, false);
            case '+' -> new KeyStroke(KeyEvent.VK_EQUALS, true);
            case '/' -> new KeyStroke(KeyEvent.VK_SLASH, false);
            case '?' -> new KeyStroke(KeyEvent.VK_SLASH, true);
            case '\\' -> new KeyStroke(KeyEvent.VK_BACK_SLASH, false);
            case '|' -> new KeyStroke(KeyEvent.VK_BACK_SLASH, true);
            case ';' -> new KeyStroke(KeyEvent.VK_SEMICOLON, false);
            case ':' -> new KeyStroke(KeyEvent.VK_SEMICOLON, true);
            case '\'' -> new KeyStroke(KeyEvent.VK_QUOTE, false);
            case '"' -> new KeyStroke(KeyEvent.VK_QUOTE, true);
            case '[' -> new KeyStroke(KeyEvent.VK_OPEN_BRACKET, false);
            case '{' -> new KeyStroke(KeyEvent.VK_OPEN_BRACKET, true);
            case ']' -> new KeyStroke(KeyEvent.VK_CLOSE_BRACKET, false);
            case '}' -> new KeyStroke(KeyEvent.VK_CLOSE_BRACKET, true);
            case '`' -> new KeyStroke(KeyEvent.VK_BACK_QUOTE, false);
            case '~' -> new KeyStroke(KeyEvent.VK_BACK_QUOTE, true);
            case '!' -> new KeyStroke(KeyEvent.VK_1, true);
            case '@' -> new KeyStroke(KeyEvent.VK_2, true);
            case '#' -> new KeyStroke(KeyEvent.VK_3, true);
            case '$' -> new KeyStroke(KeyEvent.VK_4, true);
            case '%' -> new KeyStroke(KeyEvent.VK_5, true);
            case '^' -> new KeyStroke(KeyEvent.VK_6, true);
            case '&' -> new KeyStroke(KeyEvent.VK_7, true);
            case '*' -> new KeyStroke(KeyEvent.VK_8, true);
            case '(' -> new KeyStroke(KeyEvent.VK_9, true);
            case ')' -> new KeyStroke(KeyEvent.VK_0, true);
            case '<' -> new KeyStroke(KeyEvent.VK_COMMA, true);
            case '>' -> new KeyStroke(KeyEvent.VK_PERIOD, true);
            default -> null;
        };
    }

    private void pressHotkey(Robot robot, String hotkey) {
        if (hotkey == null || hotkey.isBlank()) {
            throw new IllegalArgumentException("Hotkey value is blank.");
        }
        String[] parts = hotkey.toUpperCase().replace("CTRL", "CONTROL").split("[+ ]+");
        java.util.ArrayList<Integer> keys = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            keys.add(keyCode(part));
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Hotkey has no keys: " + hotkey);
        }
        for (Integer key : keys) {
            robot.keyPress(key);
        }
        for (int i = keys.size() - 1; i >= 0; i--) {
            robot.keyRelease(keys.get(i));
        }
    }

    private int keyCode(String token) {
        return switch (token) {
            case "CONTROL" -> KeyEvent.VK_CONTROL;
            case "SHIFT" -> KeyEvent.VK_SHIFT;
            case "ALT" -> KeyEvent.VK_ALT;
            case "META", "WIN", "WINDOWS" -> KeyEvent.VK_WINDOWS;
            case "ENTER" -> KeyEvent.VK_ENTER;
            case "TAB" -> KeyEvent.VK_TAB;
            case "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE;
            case "SPACE" -> KeyEvent.VK_SPACE;
            case "BACKSPACE" -> KeyEvent.VK_BACK_SPACE;
            case "DELETE" -> KeyEvent.VK_DELETE;
            case "UP" -> KeyEvent.VK_UP;
            case "DOWN" -> KeyEvent.VK_DOWN;
            case "LEFT" -> KeyEvent.VK_LEFT;
            case "RIGHT" -> KeyEvent.VK_RIGHT;
            default -> {
                if (token.length() == 1) {
                    int key = KeyEvent.getExtendedKeyCodeForChar(token.charAt(0));
                    if (key != KeyEvent.VK_UNDEFINED) {
                        yield key;
                    }
                }
                if (token.matches("F\\d{1,2}")) {
                    int f = Integer.parseInt(token.substring(1));
                    if (f >= 1 && f <= 12) {
                        yield KeyEvent.VK_F1 + f - 1;
                    }
                }
                throw new IllegalArgumentException("Unsupported hotkey token: " + token);
            }
        };
    }

    private void press(Robot robot, int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second.trim()) : first.trim();
    }

    private String metadataText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<String> merge(List<String> left, List<String> right) {
        java.util.ArrayList<String> merged = new java.util.ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        merged.addAll(right);
        return List.copyOf(merged);
    }

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record KeyStroke(int keyCode, boolean shift) {
    }
}
