package com.takesome.springsuite.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MouseBridgeAdapter implements DesktopBridgeAdapter {
    private static final String ID = "mouse-bridge-adapter";

    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                ID,
                "Mouse Bridge Adapter",
                false,
                true,
                List.of("real-input", "mouse", "pointer", "click", "awt-robot"),
                List.of("click"),
                Map.of(
                        "realDesktopInput", true,
                        "requiresPolicy", "suite.desktop-helper.bridge.allowed-real-input=true and bridges.mouse-bridge-adapter.enabled=true",
                        "sideEffect", "Moves pointer and sends mouse click events to the OS."
                )
        );
    }

    @Override
    public BridgeActionResult perform(BridgeActionContext context) {
        String actionId = context.action() == null ? "" : context.action().actionId();
        if (GraphicsEnvironment.isHeadless()) {
            return BridgeActionResult.failed("desktop_headless", "AWT mouse input is unavailable in headless mode.", ID, actionId, List.of(), Map.of("headless", true));
        }
        Map<String, Object> metadata = context.action() == null ? Map.of() : context.action().metadata();
        Integer x = intValue(metadata.get("x"));
        Integer y = intValue(metadata.get("y"));
        if (x == null || y == null) {
            return BridgeActionResult.failed("mouse_coordinates_missing", "Mouse click requires numeric metadata x and y.", ID, actionId, List.of(), Map.of("required", List.of("x", "y")));
        }
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(25);
            int buttonMask = buttonMask(String.valueOf(metadata.getOrDefault("button", "left")));
            robot.mouseMove(x, y);
            robot.mousePress(buttonMask);
            robot.mouseRelease(buttonMask);
            return new BridgeActionResult(
                    true,
                    "ok",
                    "Mouse click sent through AWT Robot.",
                    ID,
                    actionId,
                    true,
                    true,
                    merge(context.guards(), List.of("bridge:mouse", "input:mouse-click")),
                    List.of(),
                    Map.of("x", x, "y", y, "button", metadata.getOrDefault("button", "left"))
            );
        } catch (Exception ex) {
            return BridgeActionResult.failed("mouse_input_failed", safeMessage(ex), ID, actionId, List.of("No complete mouse action was performed."), Map.of("error", ex.getClass().getSimpleName()));
        }
    }

    private int buttonMask(String button) {
        return switch (button == null ? "left" : button.trim().toLowerCase()) {
            case "right", "secondary" -> InputEvent.BUTTON3_DOWN_MASK;
            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
}
