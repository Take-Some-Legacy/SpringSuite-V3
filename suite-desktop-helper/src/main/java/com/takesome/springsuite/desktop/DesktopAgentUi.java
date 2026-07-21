package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopAgentModels.DesktopFormSuggestion;
import jakarta.annotation.PreDestroy;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.springframework.stereotype.Component;

@Component
public class DesktopAgentUi {
    private final DesktopAgentProperties properties;
    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile TrayIcon trayIcon;
    private volatile JWindow overlay;
    private volatile JLabel overlaySummary;
    private volatile Runnable scanAction = () -> {
    };
    private volatile Runnable pauseAction = () -> {
    };
    private volatile Runnable resumeAction = () -> {
    };
    private volatile Runnable restartSidecarAction = () -> {
    };

    public DesktopAgentUi(DesktopAgentProperties properties) {
        this.properties = properties;
    }

    public boolean available() {
        return !GraphicsEnvironment.isHeadless();
    }

    public boolean trayAvailable() {
        return available() && SystemTray.isSupported();
    }

    public void initialize(
            Runnable scanAction,
            Runnable pauseAction,
            Runnable resumeAction,
            Runnable restartSidecarAction
    ) {
        this.scanAction = scanAction == null ? () -> {
        } : scanAction;
        this.pauseAction = pauseAction == null ? () -> {
        } : pauseAction;
        this.resumeAction = resumeAction == null ? () -> {
        } : resumeAction;
        this.restartSidecarAction = restartSidecarAction == null ? () -> {
        } : restartSidecarAction;
        if (!available() || !initialized.compareAndSet(false, true)) {
            return;
        }
        SwingUtilities.invokeLater(this::initializeTray);
    }

    public void showSuggestion(
            DesktopFormSuggestion suggestion,
            Runnable fillAction,
            Runnable hintsAction,
            Runnable dismissAction
    ) {
        if (!available() || !properties.isOverlayEnabled() || suggestion == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            disposeOverlay();
            JWindow window = new JWindow();
            window.setAlwaysOnTop(true);
            window.setFocusableWindowState(false);
            window.setType(Window.Type.POPUP);

            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(62, 68, 78), 1, true),
                    BorderFactory.createEmptyBorder(10, 12, 10, 12)
            ));
            panel.setBackground(new Color(245, 247, 250));

            JLabel title = new JLabel(suggestion.title().isBlank() ? "SpringSuite" : suggestion.title());
            title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
            title.setAlignmentX(0f);
            panel.add(title);
            panel.add(Box.createVerticalStrut(4));

            JLabel summary = new JLabel(html(suggestion.summary(), 360));
            summary.setVerticalAlignment(SwingConstants.TOP);
            summary.setAlignmentX(0f);
            overlaySummary = summary;
            panel.add(summary);
            panel.add(Box.createVerticalStrut(8));

            JPanel buttons = new JPanel();
            buttons.setOpaque(false);
            buttons.setAlignmentX(0f);

            if (!suggestion.actions().isEmpty()) {
                JButton fill = new JButton("Заполнить " + suggestion.actions().size());
                fill.setToolTipText("Заполнить безопасные поля из локального профиля SpringSuite");
                fill.addActionListener(event -> {
                    setOverlayMessage("Проверяю форму и выполняю разрешённые действия…");
                    if (fillAction != null) {
                        fillAction.run();
                    }
                });
                buttons.add(fill);
            }

            JButton hints = new JButton("Подсказки");
            hints.addActionListener(event -> {
                if (hintsAction != null) {
                    hintsAction.run();
                }
            });
            buttons.add(hints);

            JButton dismiss = new JButton("Скрыть");
            dismiss.addActionListener(event -> {
                hideSuggestion();
                if (dismissAction != null) {
                    dismissAction.run();
                }
            });
            buttons.add(dismiss);
            panel.add(buttons);

            window.setContentPane(panel);
            window.pack();
            Rectangle location = clampToScreen(suggestion.x(), suggestion.y(), window.getSize());
            window.setLocation(location.x, location.y);
            overlay = window;
            window.setVisible(true);
        });
    }

    public void setOverlayMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = overlaySummary;
            if (label != null) {
                label.setText(html(message, 360));
                JWindow window = overlay;
                if (window != null) {
                    window.pack();
                }
            }
        });
    }

    public void hideSuggestion() {
        if (!available()) {
            return;
        }
        SwingUtilities.invokeLater(this::disposeOverlay);
    }

    public void notifyInfo(String title, String message) {
        TrayIcon icon = trayIcon;
        if (icon != null) {
            icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    public void notifyWarning(String title, String message) {
        TrayIcon icon = trayIcon;
        if (icon != null) {
            icon.displayMessage(title, message, TrayIcon.MessageType.WARNING);
        }
    }

    public void showTransientMessage(String message) {
        setOverlayMessage(message);
        Timer timer = new Timer(5000, event -> hideSuggestion());
        timer.setRepeats(false);
        timer.start();
    }

    private void initializeTray() {
        if (!properties.isTrayEnabled() || !SystemTray.isSupported()) {
            return;
        }
        PopupMenu menu = new PopupMenu();
        MenuItem scan = new MenuItem("Проверить активную форму");
        scan.addActionListener(event -> scanAction.run());
        menu.add(scan);

        MenuItem pause = new MenuItem("Приостановить подсказки");
        pause.addActionListener(event -> pauseAction.run());
        menu.add(pause);

        MenuItem resume = new MenuItem("Возобновить подсказки");
        resume.addActionListener(event -> resumeAction.run());
        menu.add(resume);

        MenuItem restartSidecar = new MenuItem("Перезапустить desktop-agent");
        restartSidecar.addActionListener(event -> restartSidecarAction.run());
        menu.add(restartSidecar);

        MenuItem open = new MenuItem("Открыть SpringSuite");
        open.addActionListener(event -> openDashboard());
        menu.add(open);

        MenuItem hide = new MenuItem("Скрыть текущую панель");
        hide.addActionListener(event -> hideSuggestion());
        menu.add(hide);

        menu.addSeparator();
        MenuItem exit = new MenuItem("Завершить SpringSuite");
        exit.addActionListener(event -> System.exit(0));
        menu.add(exit);

        TrayIcon icon = new TrayIcon(createTrayImage(), "SpringSuite · Агент рабочего стола", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(event -> scanAction.run());
        try {
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
            icon.displayMessage(
                    "SpringSuite · Агент рабочего стола",
                    "Наблюдение за активными формами включено.",
                    TrayIcon.MessageType.INFO
            );
        } catch (AWTException ignored) {
            trayIcon = null;
        }
    }

    private void openDashboard() {
        if (!Desktop.isDesktopSupported()) {
            notifyWarning("SpringSuite", "Открытие браузера недоступно в текущем окружении.");
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create("http://127.0.0.1:8080/"));
        } catch (Exception ex) {
            notifyWarning("SpringSuite", "Не удалось открыть панель: " + ex.getMessage());
        }
    }

    private Image createTrayImage() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(27, 31, 38));
        graphics.fillRoundRect(2, 2, 28, 28, 8, 8);
        graphics.setColor(new Color(225, 230, 238));
        graphics.setStroke(new BasicStroke(3f));
        graphics.drawLine(9, 10, 23, 10);
        graphics.drawLine(9, 16, 20, 16);
        graphics.drawLine(9, 22, 17, 22);
        graphics.dispose();
        return image;
    }

    private Rectangle clampToScreen(int requestedX, int requestedY, Dimension size) {
        Rectangle target = screenBoundsAt(requestedX, requestedY);
        int x = Math.max(target.x + 8, Math.min(requestedX, target.x + target.width - size.width - 8));
        int y = Math.max(target.y + 8, Math.min(requestedY, target.y + target.height - size.height - 8));
        return new Rectangle(x, y, size.width, size.height);
    }

    private Rectangle screenBoundsAt(int x, int y) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            Rectangle bounds = configuration.getBounds();
            if (bounds.contains(x, y)) {
                return bounds;
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    private String html(String value, int width) {
        String safe = value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return "<html><div style='width:" + width + "px'>" + safe + "</div></html>";
    }

    private void disposeOverlay() {
        JWindow window = overlay;
        overlay = null;
        overlaySummary = null;
        if (window != null) {
            window.setVisible(false);
            window.dispose();
        }
    }

    @PreDestroy
    public void close() {
        disposeOverlay();
        TrayIcon icon = trayIcon;
        trayIcon = null;
        if (icon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(icon);
        }
    }
}
