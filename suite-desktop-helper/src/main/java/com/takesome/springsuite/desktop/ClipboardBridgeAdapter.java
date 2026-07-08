package com.takesome.springsuite.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ClipboardBridgeAdapter implements DesktopBridgeAdapter {
    private static final String ID = "clipboard-bridge-adapter";

    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                ID,
                "Clipboard Bridge Adapter",
                false,
                true,
                List.of("real-input", "clipboard-write", "paste", "awt-toolkit", "awt-robot"),
                List.of("fill", "paste"),
                Map.of(
                        "realDesktopInput", true,
                        "requiresPolicy", "suite.desktop-helper.bridge.allowed-real-input=true and bridges.clipboard-bridge-adapter.enabled=true",
                        "sideEffect", "Writes system clipboard and sends Ctrl+V."
                )
        );
    }

    @Override
    public BridgeActionResult perform(BridgeActionContext context) {
        String actionId = context.action() == null ? "" : context.action().actionId();
        String value = context.action() == null ? "" : context.action().value();
        if (value.isBlank()) {
            return BridgeActionResult.failed("clipboard_value_missing", "Clipboard paste requires a non-empty action value.", ID, actionId, List.of(), Map.of());
        }
        if (GraphicsEnvironment.isHeadless()) {
            return BridgeActionResult.failed("desktop_headless", "AWT desktop input is unavailable in headless mode.", ID, actionId, List.of(), Map.of("headless", true));
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
            Robot robot = new Robot();
            robot.setAutoDelay(25);
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            return new BridgeActionResult(
                    true,
                    "ok",
                    "Clipboard value pasted through AWT Robot.",
                    ID,
                    actionId,
                    true,
                    true,
                    merge(context.guards(), List.of("bridge:clipboard", "input:ctrl-v")),
                    List.of("System clipboard was overwritten for this paste operation."),
                    Map.of("valueLength", value.length())
            );
        } catch (Exception ex) {
            return BridgeActionResult.failed("clipboard_input_failed", safeMessage(ex), ID, actionId, List.of("No desktop input was completed."), Map.of("error", ex.getClass().getSimpleName()));
        }
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
