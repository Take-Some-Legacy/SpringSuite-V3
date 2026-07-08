package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.RealInputSelfTestModels.RealInputSelfTestCheck;
import com.takesome.springsuite.desktop.RealInputSelfTestModels.RealInputSelfTestRequest;
import com.takesome.springsuite.desktop.RealInputSelfTestModels.RealInputSelfTestResult;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.springframework.stereotype.Service;

@Service
public class RealInputSelfTestService {
    private final DesktopHelperProperties properties;
    private final DesktopActionExecutorRegistry executorRegistry;
    private final DesktopBridgeAdapterRegistry bridgeRegistry;

    public RealInputSelfTestService(
            DesktopHelperProperties properties,
            DesktopActionExecutorRegistry executorRegistry,
            DesktopBridgeAdapterRegistry bridgeRegistry
    ) {
        this.properties = properties;
        this.executorRegistry = executorRegistry;
        this.bridgeRegistry = bridgeRegistry;
    }

    public RealInputSelfTestResult selfTest(RealInputSelfTestRequest request) {
        RealInputSelfTestRequest safeRequest = request == null ? RealInputSelfTestRequest.diagnosticsOnly() : request;
        ArrayList<RealInputSelfTestCheck> checks = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        boolean headless = GraphicsEnvironment.isHeadless();

        checks.add(headless
                ? RealInputSelfTestCheck.failed("awt.headless", "AWT headless state", "AWT is running in headless mode; real desktop input is unavailable.", Map.of("headless", true))
                : RealInputSelfTestCheck.ok("awt.headless", "AWT headless state", "AWT has a graphical desktop environment.", Map.of("headless", false)));

        checks.add(checkClipboard(headless));
        checks.add(checkRobot(headless));
        checks.add(checkPolicy());
        checks.add(checkEnabledBridges());

        warnings.add("Real input is focus-sensitive. Production actions target the currently focused OS window.");
        if (safeRequest.perform()) {
            warnings.add("perform=true opens a temporary SpringSuite test window and attempts input only against that window.");
        } else {
            warnings.add("perform=false: no keyboard, mouse or clipboard write operation is attempted by this self-test.");
        }
        checks.add(RealInputSelfTestCheck.warn("focus.warning", "Focused window warning", "Desktop input depends on focus; use perform=true only when the temporary self-test window is visible and safe.", Map.of("perform", safeRequest.perform())));

        if (safeRequest.perform()) {
            checks.addAll(performChecks(safeRequest, headless));
        }

        boolean ok = checks.stream().noneMatch(check -> "failed".equals(check.status()));
        String summary = ok
                ? (safeRequest.perform() ? "Real input self-test completed." : "Real input diagnostics completed without executing input.")
                : "Real input self-test found blocked or failed checks.";
        return new RealInputSelfTestResult(
                ok,
                summary,
                safeRequest.perform(),
                checks,
                warnings,
                Map.of(
                        "executor", executorRegistry.policySnapshot(),
                        "bridge", bridgeRegistry.policySnapshot()
                ),
                Map.of(
                        "executorSummary", executorRegistry.summary(),
                        "bridgeSummary", bridgeRegistry.summary(),
                        "safeTestWindow", safeRequest.perform()
                )
        );
    }

    private RealInputSelfTestCheck checkClipboard(boolean headless) {
        if (headless) {
            return RealInputSelfTestCheck.failed("clipboard.available", "Clipboard availability", "Clipboard is not checked because AWT is headless.", Map.of("headless", true));
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard();
            return RealInputSelfTestCheck.ok("clipboard.available", "Clipboard availability", "System clipboard is available.", Map.of());
        } catch (Exception ex) {
            return RealInputSelfTestCheck.failed("clipboard.available", "Clipboard availability", safeMessage(ex), Map.of("error", ex.getClass().getSimpleName()));
        }
    }

    private RealInputSelfTestCheck checkRobot(boolean headless) {
        if (headless) {
            return RealInputSelfTestCheck.failed("robot.available", "Robot creation", "Robot is unavailable in headless mode.", Map.of("headless", true));
        }
        try {
            new Robot();
            return RealInputSelfTestCheck.ok("robot.available", "Robot creation", "AWT Robot can be created.", Map.of());
        } catch (Exception ex) {
            return RealInputSelfTestCheck.failed("robot.available", "Robot creation", safeMessage(ex), Map.of("error", ex.getClass().getSimpleName()));
        }
    }

    private RealInputSelfTestCheck checkPolicy() {
        boolean executorReal = properties.getExecutor().isAllowedRealInput();
        boolean bridgeReal = properties.getBridge().isAllowedRealInput();
        boolean execution = properties.isAllowAutofillExecution();
        boolean ok = executorReal && bridgeReal && execution;
        return ok
                ? RealInputSelfTestCheck.ok("policy.real-input", "Policy state", "Real input policy is enabled.", policyMetadata())
                : RealInputSelfTestCheck.warn("policy.real-input", "Policy state", "Real input is not fully enabled by policy. Diagnostics can run, but perform=true actions will be blocked until policy is enabled.", policyMetadata());
    }

    private RealInputSelfTestCheck checkEnabledBridges() {
        List<String> enabled = bridgeRegistry.descriptors().stream()
                .filter(DesktopBridgeAdapter.Descriptor::enabled)
                .map(DesktopBridgeAdapter.Descriptor::id)
                .toList();
        if (enabled.isEmpty()) {
            return RealInputSelfTestCheck.warn("bridges.enabled", "Enabled bridges", "No bridge adapters are enabled.", Map.of("enabled", enabled, "summary", bridgeRegistry.summary()));
        }
        return RealInputSelfTestCheck.ok("bridges.enabled", "Enabled bridges", "At least one bridge adapter is enabled.", Map.of("enabled", enabled, "summary", bridgeRegistry.summary()));
    }

    private List<RealInputSelfTestCheck> performChecks(RealInputSelfTestRequest request, boolean headless) {
        ArrayList<RealInputSelfTestCheck> checks = new ArrayList<>();
        if (headless) {
            checks.add(RealInputSelfTestCheck.failed("perform.blocked", "Perform self-test", "perform=true is blocked because AWT is headless.", Map.of()));
            return checks;
        }
        if (!properties.getExecutor().isAllowedRealInput() || !properties.getBridge().isAllowedRealInput() || !properties.isAllowAutofillExecution()) {
            checks.add(RealInputSelfTestCheck.failed("perform.policy", "Perform policy", "perform=true requires allow-autofill-execution=true, executor.allowed-real-input=true and bridge.allowed-real-input=true.", policyMetadata()));
            return checks;
        }

        String oldClipboardText = readClipboardText();
        try (SelfTestWindow window = SelfTestWindow.open()) {
            sleep(300);
            if (request.testClipboardPaste()) {
                checks.add(testClipboardPaste(window, request.testText()));
            }
            if (request.testTyping()) {
                checks.add(testTyping(window));
            }
            if (request.testClick()) {
                checks.add(testClick(window));
            }
        } catch (Exception ex) {
            checks.add(RealInputSelfTestCheck.failed("perform.window", "Safe test window", safeMessage(ex), Map.of("error", ex.getClass().getSimpleName())));
        } finally {
            restoreClipboardText(oldClipboardText);
        }
        return checks;
    }

    private RealInputSelfTestCheck testClipboardPaste(SelfTestWindow window, String text) {
        Optional<DesktopBridgeAdapter> bridge = bridgeRegistry.find("clipboard-bridge-adapter");
        if (bridge.isEmpty() || !bridgeRegistry.isSelectable(bridge.get())) {
            return RealInputSelfTestCheck.failed("perform.clipboard-paste", "Clipboard paste", "clipboard-bridge-adapter is not selectable under current policy.", Map.of("bridge", bridgeRegistry.descriptor("clipboard-bridge-adapter").orElse(null)));
        }
        window.clearAndFocus();
        DesktopApprovedAction action = new DesktopApprovedAction("self-test:clipboard-paste", "paste", "self-test-textarea", "Self-test text area", text, true, false, false, "real input self-test", Map.of());
        DesktopBridgeAdapter.BridgeActionResult result = bridge.get().perform(new DesktopBridgeAdapter.BridgeActionContext(action, null, List.of("self-test", "safe-window"), null, Map.of("safeTestWindow", true)));
        sleep(250);
        String actual = window.text();
        boolean ok = result.ok() && actual.contains(text);
        return ok
                ? RealInputSelfTestCheck.ok("perform.clipboard-paste", "Clipboard paste", "Clipboard paste reached the self-test window.", Map.of("bridgeResult", result, "textLength", actual.length()))
                : RealInputSelfTestCheck.failed("perform.clipboard-paste", "Clipboard paste", "Clipboard paste did not produce expected text in self-test window.", Map.of("bridgeResult", result, "actual", actual));
    }

    private RealInputSelfTestCheck testTyping(SelfTestWindow window) {
        Optional<DesktopBridgeAdapter> bridge = bridgeRegistry.find("keyboard-bridge-adapter");
        if (bridge.isEmpty() || !bridgeRegistry.isSelectable(bridge.get())) {
            return RealInputSelfTestCheck.failed("perform.typing", "Keyboard typing", "keyboard-bridge-adapter is not selectable under current policy.", Map.of("bridge", bridgeRegistry.descriptor("keyboard-bridge-adapter").orElse(null)));
        }
        window.clearAndFocus();
        String text = "TYPE-OK";
        DesktopApprovedAction action = new DesktopApprovedAction("self-test:type", "type", "self-test-textarea", "Self-test text area", text, true, false, false, "real input self-test", Map.of());
        DesktopBridgeAdapter.BridgeActionResult result = bridge.get().perform(new DesktopBridgeAdapter.BridgeActionContext(action, null, List.of("self-test", "safe-window"), null, Map.of("safeTestWindow", true)));
        sleep(250);
        String actual = window.text();
        boolean ok = result.ok() && actual.contains(text);
        return ok
                ? RealInputSelfTestCheck.ok("perform.typing", "Keyboard typing", "Keyboard typing reached the self-test window.", Map.of("bridgeResult", result, "actual", actual))
                : RealInputSelfTestCheck.failed("perform.typing", "Keyboard typing", "Keyboard typing did not produce expected text in self-test window.", Map.of("bridgeResult", result, "actual", actual));
    }

    private RealInputSelfTestCheck testClick(SelfTestWindow window) {
        Optional<DesktopBridgeAdapter> bridge = bridgeRegistry.find("mouse-bridge-adapter");
        if (bridge.isEmpty() || !bridgeRegistry.isSelectable(bridge.get())) {
            return RealInputSelfTestCheck.failed("perform.click", "Mouse click", "mouse-bridge-adapter is not selectable under current policy.", Map.of("bridge", bridgeRegistry.descriptor("mouse-bridge-adapter").orElse(null)));
        }
        window.focus();
        Point point = window.buttonCenterOnScreen();
        DesktopApprovedAction action = new DesktopApprovedAction("self-test:click", "click", "self-test-button", "Self-test button", "", true, false, false, "real input self-test", Map.of("x", point.x, "y", point.y, "button", "left"));
        DesktopBridgeAdapter.BridgeActionResult result = bridge.get().perform(new DesktopBridgeAdapter.BridgeActionContext(action, null, List.of("self-test", "safe-window"), null, Map.of("safeTestWindow", true)));
        sleep(250);
        boolean ok = result.ok() && window.clicked();
        return ok
                ? RealInputSelfTestCheck.ok("perform.click", "Mouse click", "Mouse click reached the self-test button.", Map.of("bridgeResult", result, "x", point.x, "y", point.y))
                : RealInputSelfTestCheck.failed("perform.click", "Mouse click", "Mouse click did not activate the self-test button.", Map.of("bridgeResult", result, "clicked", window.clicked(), "x", point.x, "y", point.y));
    }

    private Map<String, Object> policyMetadata() {
        return Map.of(
                "allowAutofillExecution", properties.isAllowAutofillExecution(),
                "executorAllowedRealInput", properties.getExecutor().isAllowedRealInput(),
                "bridgeAllowedRealInput", properties.getBridge().isAllowedRealInput(),
                "executor", executorRegistry.summary(),
                "bridge", bridgeRegistry.summary()
        );
    }

    private String readClipboardText() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                Object data = clipboard.getData(DataFlavor.stringFlavor);
                return data == null ? "" : String.valueOf(data);
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private void restoreClipboardText(String value) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value == null ? "" : value), null);
        } catch (Exception ignored) {
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static final class SelfTestWindow implements AutoCloseable {
        private final JFrame frame;
        private final JTextArea textArea;
        private final JButton button;
        private final AtomicBoolean clicked;

        private SelfTestWindow(JFrame frame, JTextArea textArea, JButton button, AtomicBoolean clicked) {
            this.frame = frame;
            this.textArea = textArea;
            this.button = button;
            this.clicked = clicked;
        }

        static SelfTestWindow open() throws Exception {
            AtomicReference<SelfTestWindow> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                AtomicBoolean clicked = new AtomicBoolean(false);
                JFrame frame = new JFrame("SpringSuite Real Input Self-Test");
                JTextArea textArea = new JTextArea(8, 48);
                JButton button = new JButton("SpringSuite Click Target");
                button.addActionListener(event -> clicked.set(true));
                frame.setLayout(new BorderLayout());
                frame.add(new JScrollPane(textArea), BorderLayout.CENTER);
                frame.add(button, BorderLayout.SOUTH);
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setAlwaysOnTop(true);
                frame.setVisible(true);
                frame.toFront();
                frame.requestFocus();
                textArea.requestFocusInWindow();
                ref.set(new SelfTestWindow(frame, textArea, button, clicked));
            });
            return ref.get();
        }

        void clearAndFocus() {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    textArea.setText("");
                    frame.toFront();
                    textArea.requestFocusInWindow();
                });
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        void focus() {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    frame.toFront();
                    frame.requestFocus();
                    button.requestFocusInWindow();
                });
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        String text() {
            AtomicReference<String> ref = new AtomicReference<>("");
            try {
                SwingUtilities.invokeAndWait(() -> ref.set(textArea.getText()));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            return ref.get();
        }

        Point buttonCenterOnScreen() {
            AtomicReference<Point> ref = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    Point point = button.getLocationOnScreen();
                    ref.set(new Point(point.x + button.getWidth() / 2, point.y + button.getHeight() / 2));
                });
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            return ref.get();
        }

        boolean clicked() {
            return clicked.get();
        }

        @Override
        public void close() {
            try {
                SwingUtilities.invokeAndWait(frame::dispose);
            } catch (Exception ignored) {
            }
        }
    }
}
