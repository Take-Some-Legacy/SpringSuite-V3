package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopAgentModels.DesktopFormSuggestion;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import jakarta.annotation.PreDestroy;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MenuItem;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import org.springframework.stereotype.Component;

@Component
public class DesktopAgentUi {
    private static final int OVERLAY_MINIMUM_WIDTH = 448;
    private static final int CONTENT_WIDTH = 392;
    private static final int SCREEN_MARGIN = 10;

    private static final Color HEADER_BACKGROUND = new Color(14, 18, 27);
    private static final Color HEADER_TEXT = new Color(237, 242, 250);
    private static final Color HEADER_MUTED = new Color(151, 164, 185);
    private static final Color SURFACE = new Color(18, 22, 31);
    private static final Color CARD = new Color(25, 31, 43);
    private static final Color BORDER = new Color(65, 77, 99);
    private static final Color TEXT = new Color(237, 242, 250);
    private static final Color MUTED_TEXT = new Color(164, 176, 196);
    private static final Color PRIMARY = new Color(126, 156, 255);
    private static final Color PRIMARY_HOVER = new Color(148, 174, 255);
    private static final Color SECONDARY = new Color(42, 50, 66);
    private static final Color SECONDARY_HOVER = new Color(54, 64, 84);
    private static final Color SUCCESS = new Color(113, 214, 170);

    private final DesktopAgentProperties properties;
    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile TrayIcon trayIcon;
    private volatile JWindow overlay;
    private volatile JPanel overlayHost;
    private volatile String overlaySignature = "";
    private volatile JLabel overlaySummary;
    private volatile JButton overlayFillButton;
    private volatile Timer overlayFillLoadingTimer;
    private volatile int overlayFillLoadingFrame;
    private volatile JDialog fieldsDialog;
    private volatile String fieldsDialogSignature = "";
    private volatile JLabel fieldsDialogHeading;
    private volatile JEditorPane fieldsDialogContent;
    private volatile Point preferredOverlayLocation;
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
            Consumer<String> sourceChangeAction,
            Runnable dismissAction
    ) {
        if (!available() || !properties.isOverlayEnabled() || suggestion == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            stopFillLoading();
            String signature = suggestion.signature();
            JWindow existing = overlay;
            boolean reuseWindow = existing != null
                    && existing.isDisplayable()
                    && overlayHost != null
                    && signature.equals(overlaySignature);
            Point retainedLocation = reuseWindow ? existing.getLocation() : null;

            if (!reuseWindow) {
                disposeOverlay();
            }

            boolean browserDom = isBrowserDom(suggestion);
            JWindow window = reuseWindow ? existing : new JWindow();
            JPanel host = reuseWindow ? overlayHost : new JPanel(new BorderLayout());
            if (!reuseWindow) {
                try {
                    window.setBackground(new Color(0, 0, 0, 0));
                } catch (UnsupportedOperationException ignored) {
                    window.setBackground(SURFACE);
                }
                host.setOpaque(false);
                // Install the JWindow content pane exactly once. Replacing it on an
                // already displayable window can corrupt JRootPane/JLayeredPane state.
                window.setContentPane(host);
                overlayHost = host;
            }
            window.setAlwaysOnTop(true);
            window.setFocusableWindowState(false);

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(SURFACE);
            root.setBorder(BorderFactory.createLineBorder(BORDER, 1, true));

            JPanel header = createHeader(window, suggestion, browserDom, dismissAction);
            root.add(header, BorderLayout.NORTH);

            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

            content.add(createSourceSelector(suggestion, sourceChangeAction));
            content.add(Box.createVerticalStrut(12));

            JLabel summary = new JLabel(html(suggestion.summary(), CONTENT_WIDTH));
            summary.setForeground(TEXT);
            summary.setVerticalAlignment(SwingConstants.TOP);
            summary.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            overlaySummary = summary;
            content.add(summary);

            if (!suggestion.actions().isEmpty()) {
                content.add(Box.createVerticalStrut(12));
                content.add(createProposalSection(suggestion));
            }

            content.add(Box.createVerticalStrut(12));
            JLabel safetyNote = new JLabel(browserDom
                    ? "Значения будут вставлены без автоматической отправки формы."
                    : "Будут выполнены только подтверждённые безопасные действия.");
            safetyNote.setFont(safetyNote.getFont().deriveFont(Font.PLAIN, 11f));
            safetyNote.setForeground(MUTED_TEXT);
            safetyNote.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            content.add(safetyNote);
            content.add(Box.createVerticalStrut(12));
            content.add(createActionBar(suggestion, browserDom, fillAction, hintsAction, dismissAction));

            root.add(content, BorderLayout.CENTER);
            replaceHostContent(host, root);
            window.pack();
            Dimension packed = window.getSize();
            window.setSize(Math.max(OVERLAY_MINIMUM_WIDTH, packed.width), packed.height);

            Point preferred = retainedLocation != null ? retainedLocation : preferredOverlayLocation;
            int requestedX = preferred == null ? suggestion.x() : preferred.x;
            int requestedY = preferred == null ? suggestion.y() : preferred.y;
            Rectangle location = clampToScreen(requestedX, requestedY, window.getSize());
            window.setLocation(location.x, location.y);

            overlay = window;
            overlaySignature = signature;
            if (!window.isVisible()) {
                window.setVisible(true);
            } else {
                window.revalidate();
                window.repaint();
            }
            refreshFieldsDialog(suggestion);
        });
    }

    static void replaceHostContent(JPanel host, JPanel content) {
        if (host == null || content == null) {
            throw new IllegalArgumentException("Overlay host and content are required.");
        }
        host.removeAll();
        host.setLayout(new BorderLayout());
        host.add(content, BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
    }

    private JPanel createHeader(
            JWindow window,
            DesktopFormSuggestion suggestion,
            boolean browserDom,
            Runnable dismissAction
    ) {
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setBackground(HEADER_BACKGROUND);
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 8));

        JLabel grip = new JLabel("⋮⋮");
        grip.setFont(grip.getFont().deriveFont(Font.BOLD, 18f));
        grip.setForeground(HEADER_MUTED);
        grip.setHorizontalAlignment(SwingConstants.CENTER);
        grip.setPreferredSize(new Dimension(22, 34));

        JPanel titleGroup = new JPanel();
        titleGroup.setOpaque(false);
        titleGroup.setLayout(new BoxLayout(titleGroup, BoxLayout.Y_AXIS));

        JLabel title = new JLabel(suggestion.title().isBlank() ? "SpringSuite" : suggestion.title());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(HEADER_TEXT);
        title.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        String activeFieldName = metadataText(suggestion, "activeFieldName", "неизвестное поле");
        JLabel activeField = new JLabel("Активное поле: " + activeFieldName);
        activeField.setFont(activeField.getFont().deriveFont(Font.BOLD, 11f));
        activeField.setForeground(HEADER_TEXT);
        activeField.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        String fieldDetailText = activeFieldDetail(
                metadataText(suggestion, "activeFieldPlaceholder", ""),
                metadataText(suggestion, "activeFieldPrompt", ""),
                metadataText(suggestion, "activeFieldType", "")
        );
        JLabel fieldDetail = new JLabel(fieldDetailText);
        fieldDetail.setFont(fieldDetail.getFont().deriveFont(Font.PLAIN, 10f));
        fieldDetail.setForeground(HEADER_MUTED);
        fieldDetail.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        fieldDetail.setToolTipText(fieldDetailText);

        JLabel subtitle = new JLabel(headerSubtitle(suggestion.actions().size(), browserDom));
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 11f));
        subtitle.setForeground(suggestion.actions().isEmpty() ? HEADER_MUTED : SUCCESS);
        subtitle.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        titleGroup.add(title);
        titleGroup.add(Box.createVerticalStrut(3));
        titleGroup.add(activeField);
        titleGroup.add(Box.createVerticalStrut(1));
        titleGroup.add(fieldDetail);
        titleGroup.add(Box.createVerticalStrut(3));
        titleGroup.add(subtitle);

        JPanel draggable = new JPanel(new BorderLayout(8, 0));
        draggable.setOpaque(false);
        draggable.add(grip, BorderLayout.WEST);
        draggable.add(titleGroup, BorderLayout.CENTER);

        JButton close = new JButton("×");
        close.setToolTipText("Скрыть предложение");
        close.setFont(close.getFont().deriveFont(Font.PLAIN, 20f));
        close.setForeground(HEADER_MUTED);
        close.setOpaque(false);
        close.setContentAreaFilled(false);
        close.setBorderPainted(false);
        close.setFocusPainted(false);
        close.setFocusable(false);
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        close.setPreferredSize(new Dimension(34, 34));
        close.addActionListener(event -> dismissSuggestion(dismissAction));
        installForegroundHover(close, HEADER_MUTED, HEADER_TEXT);

        header.add(draggable, BorderLayout.CENTER);
        header.add(close, BorderLayout.EAST);

        installDragSupport(
                window,
                suggestion.x(),
                suggestion.y(),
                header,
                draggable,
                grip,
                titleGroup,
                title,
                activeField,
                fieldDetail,
                subtitle
        );
        return header;
    }

    private JPanel createSourceSelector(
            DesktopFormSuggestion suggestion,
            Consumer<String> sourceChangeAction
    ) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Источник заполнения");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        title.setForeground(TEXT);
        title.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JLabel explanation = new JLabel("Память — локальный профиль; ИИ — текущий ChatGPT Plus-чат");
        explanation.setFont(explanation.getFont().deriveFont(Font.PLAIN, 10f));
        explanation.setForeground(MUTED_TEXT);
        explanation.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        labels.add(title);
        labels.add(Box.createVerticalStrut(2));
        labels.add(explanation);

        FillSourceOption memory = new FillSourceOption("memory", "Из памяти");
        FillSourceOption ai = new FillSourceOption("ai", "От ИИ");
        JComboBox<FillSourceOption> selector = new JComboBox<>(new FillSourceOption[]{memory, ai});
        selector.setFocusable(false);
        selector.setBackground(CARD);
        selector.setForeground(TEXT);
        selector.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        selector.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(value == null ? "" : value.toString());
            label.setOpaque(true);
            label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            label.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
            label.setBackground(isSelected ? SECONDARY_HOVER : CARD);
            label.setForeground(TEXT);
            return label;
        });
        selector.setPreferredSize(new Dimension(168, 36));
        selector.setMaximumSize(new Dimension(168, 36));
        selector.setToolTipText("Выберите источник и дождитесь обновления предложенных значений");

        String currentSource = metadataText(suggestion, "fillSource", "memory");
        selector.setSelectedItem(("ai".equals(currentSource) || "chatgpt-5.6".equals(currentSource)) ? ai : memory);
        selector.addActionListener(event -> {
            Object selected = selector.getSelectedItem();
            if (!(selected instanceof FillSourceOption option) || option.id().equals(currentSource)) {
                return;
            }
            selector.setEnabled(false);
            setOverlayMessage("ai".equals(option.id())
                    ? "ChatGPT Plus готов. Нажмите «Заполнить», чтобы отправить запрос в текущий чат."
                    : "Читаю значения из локальной памяти автозаполнения…");
            if (sourceChangeAction != null) {
                sourceChangeAction.accept(option.id());
            }
        });

        JButton fields = createSecondaryButton(detectedFieldsLabel(fieldCount(suggestion)));
        fields.setPreferredSize(new Dimension(168, 32));
        fields.setMaximumSize(new Dimension(168, 32));
        fields.setToolTipText("Показать все поля, распознанные в активной форме");
        fields.addActionListener(event -> showDetectedFields(suggestion));

        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        selector.setAlignmentX(java.awt.Component.RIGHT_ALIGNMENT);
        fields.setAlignmentX(java.awt.Component.RIGHT_ALIGNMENT);
        controls.add(selector);
        controls.add(Box.createVerticalStrut(6));
        controls.add(fields);

        row.add(labels, BorderLayout.CENTER);
        row.add(controls, BorderLayout.EAST);
        return row;
    }

    private void showDetectedFields(DesktopFormSuggestion suggestion) {
        JDialog previous = fieldsDialog;
        if (previous != null && previous.isDisplayable()
                && suggestion.signature().equals(fieldsDialogSignature)) {
            refreshFieldsDialog(suggestion);
            previous.setVisible(true);
            previous.toFront();
            return;
        }
        if (previous != null) {
            previous.dispose();
        }

        JDialog dialog = new JDialog();
        dialog.setTitle("SpringSuite · распознанные поля");
        dialog.setAlwaysOnTop(true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel heading = new JLabel("Распознанные поля: " + fieldCount(suggestion));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        heading.setForeground(TEXT);
        heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        root.add(heading, BorderLayout.NORTH);

        JEditorPane fields = new JEditorPane("text/html", detectedFieldsHtml(suggestion));
        fields.setEditable(false);
        fields.setFocusable(false);
        fields.setBackground(CARD);
        fields.setForeground(TEXT);
        fields.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

        JScrollPane scroll = new JScrollPane(fields);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER, 1, true));
        scroll.getViewport().setBackground(CARD);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);

        JButton close = createSecondaryButton("Закрыть");
        close.addActionListener(event -> dialog.dispose());
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        footer.add(close, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.setSize(540, Math.min(560, Math.max(280, 150 + fieldCount(suggestion) * 92)));

        JWindow owner = overlay;
        int requestedX = owner == null ? suggestion.x() : owner.getX() + owner.getWidth() + 10;
        int requestedY = owner == null ? suggestion.y() : owner.getY();
        Rectangle location = clampToScreen(requestedX, requestedY, dialog.getSize());
        dialog.setLocation(location.x, location.y);
        fieldsDialog = dialog;
        fieldsDialogSignature = suggestion.signature();
        fieldsDialogHeading = heading;
        fieldsDialogContent = fields;
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (fieldsDialog == dialog) {
                    clearFieldsDialogState();
                }
            }
        });
        dialog.setVisible(true);
    }

    private void refreshFieldsDialog(DesktopFormSuggestion suggestion) {
        JDialog dialog = fieldsDialog;
        if (dialog == null || !dialog.isDisplayable()
                || !suggestion.signature().equals(fieldsDialogSignature)) {
            return;
        }
        JLabel heading = fieldsDialogHeading;
        JEditorPane content = fieldsDialogContent;
        if (heading != null) {
            heading.setText("Распознанные поля: " + fieldCount(suggestion));
        }
        if (content != null) {
            int scrollPosition = content.getCaretPosition();
            content.setText(detectedFieldsHtml(suggestion));
            content.setCaretPosition(Math.min(scrollPosition, content.getDocument().getLength()));
        }
        dialog.repaint();
    }

    private void clearFieldsDialogState() {
        fieldsDialog = null;
        fieldsDialogSignature = "";
        fieldsDialogHeading = null;
        fieldsDialogContent = null;
    }

    private String detectedFieldsHtml(DesktopFormSuggestion suggestion) {
        StringBuilder html = new StringBuilder("<html><body style='font-family:Segoe UI,sans-serif;background:#191f2b;color:#edf2fa;margin:10px'>");
        if (suggestion.snapshot() == null || suggestion.snapshot().context() == null
                || suggestion.snapshot().context().form().fields().isEmpty()) {
            return html.append("<p>Поля не найдены.</p></body></html>").toString();
        }

        int index = 0;
        for (DesktopFormField field : suggestion.snapshot().context().form().fields()) {
            index++;
            String contextPrompt = metadataString(field, "contextPrompt");
            String name = firstNonBlank(
                    contextPrompt.equalsIgnoreCase(field.label()) ? "" : field.label(),
                    field.name(),
                    field.id(),
                    "Поле " + index
            );
            String detail = activeFieldDetail(
                    field.placeholder(),
                    contextPrompt,
                    field.type()
            );
            String identifier = firstNonBlank(field.name(), field.id(), "—");
            html.append("<div style='border:1px solid #414d63;border-radius:7px;padding:9px;margin-bottom:8px'>")
                    .append("<div><b>")
                    .append(field.focused() ? "● " : "")
                    .append(escapeHtml(name))
                    .append("</b>")
                    .append(field.focused() ? " <span style='color:#71d6aa'>активное</span>" : "")
                    .append("</div>")
                    .append("<div style='color:#a4b0c4;margin-top:3px'>")
                    .append(escapeHtml(detail))
                    .append("</div>")
                    .append("<div style='color:#a4b0c4;margin-top:3px'>Тип: ")
                    .append(escapeHtml(field.type()))
                    .append(" · name/id: ")
                    .append(escapeHtml(identifier))
                    .append("</div>")
                    .append("<div style='margin-top:5px'>")
                    .append(field.required() ? badge("обязательное", "#fff4cc", "#725700") : "")
                    .append(field.sensitive() ? badge("чувствительное", "#ffe0e0", "#8c2020") : "")
                    .append(fieldValuePresent(field) ? badge("уже заполнено", "#e4ecff", "#244ca3") : "")
                    .append(metadataBoolean(field, "disabled") ? badge("отключено", "#eceff3", "#59616d") : "")
                    .append(metadataBoolean(field, "readOnly") ? badge("только чтение", "#eceff3", "#59616d") : "")
                    .append("</div></div>");
        }
        return html.append("</body></html>").toString();
    }

    private String badge(String text, String background, String foreground) {
        return "<span style='background:" + background + ";color:" + foreground
                + ";padding:2px 5px;margin-right:5px'>" + escapeHtml(text) + "</span>";
    }

    private int fieldCount(DesktopFormSuggestion suggestion) {
        if (suggestion.snapshot() == null || suggestion.snapshot().context() == null) {
            return 0;
        }
        return suggestion.snapshot().context().form().fields().size();
    }

    private boolean fieldValuePresent(DesktopFormField field) {
        return !field.value().isBlank() || metadataBoolean(field, "valuePresent");
    }

    private boolean metadataBoolean(DesktopFormField field, String key) {
        Object value = field.metadata().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String metadataString(DesktopFormField field, String key) {
        Object value = field.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private JPanel createProposalSection(DesktopFormSuggestion suggestion) {
        JPanel section = new JPanel();
        section.setOpaque(false);
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("Предложенные значения");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        title.setForeground(TEXT);

        JLabel count = new JLabel(actionCountLabel(suggestion.actions().size()));
        count.setFont(count.getFont().deriveFont(Font.BOLD, 10f));
        count.setForeground(PRIMARY);
        count.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(91, 111, 169), 1, true),
                BorderFactory.createEmptyBorder(2, 7, 2, 7)
        ));

        heading.add(title, BorderLayout.WEST);
        heading.add(count, BorderLayout.EAST);
        section.add(heading);
        section.add(Box.createVerticalStrut(6));

        JPanel proposalCard = new JPanel(new BorderLayout());
        proposalCard.setBackground(CARD);
        proposalCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(9, 10, 9, 10)
        ));
        proposalCard.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JLabel proposal = new JLabel(proposalHtml(suggestion, CONTENT_WIDTH - 24));
        proposal.setForeground(TEXT);
        proposal.setVerticalAlignment(SwingConstants.TOP);
        proposalCard.add(proposal, BorderLayout.CENTER);
        section.add(proposalCard);
        return section;
    }

    private JPanel createActionBar(
            DesktopFormSuggestion suggestion,
            boolean browserDom,
            Runnable fillAction,
            Runnable hintsAction,
            Runnable dismissAction
    ) {
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        boolean hasInsertableValues = !suggestion.actions().isEmpty();
        String fillSource = metadataText(suggestion, "fillSource", "memory");
        boolean aiSource = "ai".equals(fillSource) || "chatgpt-5.6".equals(fillSource);
        boolean fillAvailable = fillActionAvailable(suggestion.actions().size(), fillSource);
        JButton fill = createPrimaryButton("Заполнить");
        overlayFillButton = fill;
        fill.setEnabled(fillAvailable);
        fill.setPreferredSize(new Dimension(152, 38));
        fill.setMaximumSize(new Dimension(152, 38));
        fill.setToolTipText(hasInsertableValues
                ? "Заполнить " + suggestion.actions().size()
                        + " безопасных полей; форма не будет отправлена автоматически"
                : aiSource
                        ? "Отправить prompt активного поля в текущий ChatGPT Plus-чат"
                        : "В локальной памяти нет значения для распознанного поля");
        fill.addActionListener(event -> {
            if (!fill.isEnabled()) {
                return;
            }
            startFillLoading(fill, aiSource && !hasInsertableValues ? "Загрузка" : "Заполнение");
            setOverlayMessage(aiSource && !hasInsertableValues
                    ? "Отправляю запрос в ChatGPT Plus и ожидаю MCP-ответ…"
                    : browserDom
                            ? "Передаю подтверждённые значения браузерному расширению…"
                            : "Проверяю актуальность формы и выполняю разрешённые действия…");
            if (fillAction != null) {
                fillAction.run();
            }
        });
        buttons.add(fill);
        buttons.add(Box.createHorizontalStrut(8));

        JButton hints = createSecondaryButton("Подсказки");
        hints.setToolTipText("Показать рекомендации по текущей форме");
        hints.addActionListener(event -> {
            if (hintsAction != null) {
                hintsAction.run();
            }
        });
        buttons.add(hints);
        buttons.add(Box.createHorizontalGlue());

        JButton dismiss = createSecondaryButton("Не сейчас");
        dismiss.setToolTipText("Скрыть предложение для текущей формы");
        dismiss.addActionListener(event -> dismissSuggestion(dismissAction));
        buttons.add(dismiss);
        return buttons;
    }

    private void startFillLoading(JButton button, String label) {
        stopFillLoading();
        overlayFillButton = button;
        overlayFillLoadingFrame = 0;
        button.setEnabled(false);
        button.setText(loadingButtonText(label, 3));

        Timer timer = new Timer(260, event -> {
            JButton current = overlayFillButton;
            if (current == null || !current.isDisplayable()) {
                stopFillLoading();
                return;
            }
            current.setText(loadingButtonText(label, overlayFillLoadingFrame++));
        });
        timer.setInitialDelay(260);
        timer.start();
        overlayFillLoadingTimer = timer;
    }

    private void stopFillLoading() {
        Timer timer = overlayFillLoadingTimer;
        overlayFillLoadingTimer = null;
        overlayFillLoadingFrame = 0;
        if (timer != null) {
            timer.stop();
        }
    }

    public void showFillSuccess(String message) {
        SwingUtilities.invokeLater(() -> {
            stopFillLoading();
            JButton button = overlayFillButton;
            if (button != null && button.isDisplayable()) {
                button.setText("Готово ✓");
                button.setEnabled(false);
            }
            setOverlayMessage(message);
            Timer timer = new Timer(1600, event -> hideSuggestion());
            timer.setRepeats(false);
            timer.start();
        });
    }

    public void showFillError(String message) {
        SwingUtilities.invokeLater(() -> {
            stopFillLoading();
            JButton button = overlayFillButton;
            if (button != null && button.isDisplayable()) {
                button.setText("Заполнить");
                button.setEnabled(true);
            }
            setOverlayMessage(message);
        });
    }

    private JButton createPrimaryButton(String text) {
        JButton button = new RoundedButton(text);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
        button.setForeground(Color.WHITE);
        button.setBackground(PRIMARY);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        installBackgroundHover(button, PRIMARY, PRIMARY_HOVER);
        return button;
    }

    private JButton createSecondaryButton(String text) {
        JButton button = new RoundedButton(text);
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 12f));
        button.setForeground(TEXT);
        button.setBackground(SECONDARY);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        installBackgroundHover(button, SECONDARY, SECONDARY_HOVER);
        return button;
    }

    private void installBackgroundHover(JButton button, Color normal, Color hover) {
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                if (button.isEnabled()) {
                    button.setBackground(hover);
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                button.setBackground(normal);
            }
        });
    }

    private void installForegroundHover(JButton button, Color normal, Color hover) {
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                button.setForeground(hover);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                button.setForeground(normal);
            }
        });
    }

    private void dismissSuggestion(Runnable dismissAction) {
        hideSuggestion();
        if (dismissAction != null) {
            dismissAction.run();
        }
    }

    public void setOverlayMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = overlaySummary;
            JWindow window = overlay;
            if (label == null || window == null) {
                return;
            }
            label.setText(html(message, CONTENT_WIDTH));
            Point currentLocation = window.getLocation();
            window.pack();
            Dimension packed = window.getSize();
            window.setSize(Math.max(OVERLAY_MINIMUM_WIDTH, packed.width), packed.height);
            Rectangle location = clampToScreen(currentLocation.x, currentLocation.y, window.getSize());
            window.setLocation(location.x, location.y);
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

    public void notifyError(String title, String message) {
        TrayIcon icon = trayIcon;
        if (icon != null) {
            icon.displayMessage(title, message, TrayIcon.MessageType.ERROR);
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
            Desktop.getDesktop().browse(URI.create("http://127.0.0.1:8090/"));
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

    private void installDragSupport(
            JWindow window,
            int resetX,
            int resetY,
            java.awt.Component... dragHandles
    ) {
        Point[] pointerOffset = new Point[1];
        MouseAdapter dragListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                Point pointer = event.getLocationOnScreen();
                pointerOffset[0] = new Point(pointer.x - window.getX(), pointer.y - window.getY());
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                Point offset = pointerOffset[0];
                if (offset == null || !window.isDisplayable()) {
                    return;
                }
                Point pointer = event.getLocationOnScreen();
                Rectangle location = clampToScreen(
                        pointer.x - offset.x,
                        pointer.y - offset.y,
                        window.getSize()
                );
                window.setLocation(location.x, location.y);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                pointerOffset[0] = null;
                if (window.isDisplayable()) {
                    preferredOverlayLocation = window.getLocation();
                }
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && window.isDisplayable()) {
                    preferredOverlayLocation = null;
                    Rectangle location = clampToScreen(resetX, resetY, window.getSize());
                    window.setLocation(location.x, location.y);
                }
            }
        };

        for (java.awt.Component handle : dragHandles) {
            handle.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            if (handle instanceof javax.swing.JComponent component) {
                component.setToolTipText("Перетащите окно; двойной щелчок вернёт его к форме");
            }
            handle.addMouseListener(dragListener);
            handle.addMouseMotionListener(dragListener);
        }
    }

    private Rectangle clampToScreen(int requestedX, int requestedY, Dimension size) {
        Rectangle target = screenBoundsAt(requestedX, requestedY);
        int minX = target.x + SCREEN_MARGIN;
        int minY = target.y + SCREEN_MARGIN;
        int maxX = Math.max(minX, target.x + target.width - size.width - SCREEN_MARGIN);
        int maxY = Math.max(minY, target.y + target.height - size.height - SCREEN_MARGIN);
        int x = Math.max(minX, Math.min(requestedX, maxX));
        int y = Math.max(minY, Math.min(requestedY, maxY));
        return new Rectangle(x, y, size.width, size.height);
    }

    private Rectangle screenBoundsAt(int x, int y) {
        GraphicsConfiguration fallback = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            if (configuration.getBounds().contains(x, y)) {
                return usableBounds(configuration);
            }
        }
        return usableBounds(fallback);
    }

    private Rectangle usableBounds(GraphicsConfiguration configuration) {
        Rectangle bounds = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        return new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                Math.max(1, bounds.width - insets.left - insets.right),
                Math.max(1, bounds.height - insets.top - insets.bottom)
        );
    }

    private String proposalHtml(DesktopFormSuggestion suggestion, int width) {
        StringBuilder value = new StringBuilder("<html><div style='width:").append(width).append("px'>");
        int count = 0;
        for (DesktopApprovalModels.DesktopApprovedAction action : suggestion.actions()) {
            if (count >= 8) {
                value.append("<div style='margin-top:6px;color:#a4b0c4'>… и ещё ")
                        .append(suggestion.actions().size() - count)
                        .append("</div>");
                break;
            }
            value.append("<div style='margin-top:3px'><span style='color:#a4b0c4'>")
                    .append(escapeHtml(action.label().isBlank() ? action.targetFieldId() : action.label()))
                    .append(":</span> <b>")
                    .append(escapeHtml(action.value()).replace("\n", "<br>"))
                    .append("</b></div>");
            count++;
        }
        return value.append("</div></html>").toString();
    }

    static String loadingButtonText(String label, int frame) {
        String safeLabel = label == null || label.isBlank() ? "Загрузка" : label.trim();
        int dots = Math.floorMod(frame, 4);
        return safeLabel + ".".repeat(dots);
    }

    static boolean fillActionAvailable(int actionCount, String fillSource) {
        String source = fillSource == null ? "" : fillSource.trim();
        return actionCount > 0
                || "ai".equalsIgnoreCase(source)
                || "chatgpt-5.6".equalsIgnoreCase(source);
    }

    static String detectedFieldsLabel(int fieldCount) {
        return "Поля: " + Math.max(0, fieldCount);
    }

    static String activeFieldDetail(String placeholder, String contextPrompt, String type) {
        String normalizedPlaceholder = placeholder == null ? "" : placeholder.trim();
        if (!normalizedPlaceholder.isBlank()) {
            return "Placeholder: " + normalizedPlaceholder;
        }
        String normalizedPrompt = contextPrompt == null ? "" : contextPrompt.trim();
        if (!normalizedPrompt.isBlank()) {
            return "Контекст: " + normalizedPrompt;
        }
        String normalizedType = type == null ? "" : type.trim();
        return normalizedType.isBlank() ? "Placeholder отсутствует" : "Тип: " + normalizedType + " · placeholder отсутствует";
    }

    private String metadataText(DesktopFormSuggestion suggestion, String key, String fallback) {
        Object value = suggestion.metadata().get(key);
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    static String headerSubtitle(int actionCount, boolean browserDom) {
        if (actionCount <= 0) {
            return browserDom ? "Веб-форма распознана · нет готовых значений" : "Форма распознана · нет готовых значений";
        }
        return "● Автозаполнение готово · " + actionCountLabel(actionCount);
    }

    static String actionCountLabel(int actionCount) {
        int normalized = Math.max(0, actionCount);
        int mod100 = normalized % 100;
        int mod10 = normalized % 10;
        String noun = mod100 >= 11 && mod100 <= 14
                ? "полей"
                : mod10 == 1
                        ? "поле"
                        : mod10 >= 2 && mod10 <= 4 ? "поля" : "полей";
        return normalized + " " + noun;
    }

    private static final class RoundedButton extends JButton {
        private static final int ARC = 12;

        private RoundedButton(String text) {
            super(text);
            setContentAreaFilled(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = getBackground();
            if (!isEnabled()) {
                fill = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 110);
            } else if (getModel().isPressed()) {
                fill = fill.darker();
            }
            g.setColor(fill);
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), ARC, ARC));
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private record FillSourceOption(String id, String label) {
        private FillSourceOption {
            id = id == null ? "memory" : id.trim();
            label = label == null ? id : label.trim();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private boolean isBrowserDom(DesktopFormSuggestion suggestion) {
        Object value = suggestion.metadata().get("browserDom");
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String escapeHtml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String html(String value, int width) {
        String safe = value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<html><div style='width:" + width + "px;color:#edf2fa;line-height:1.4'>" + safe + "</div></html>";
    }

    private void disposeOverlay() {
        stopFillLoading();
        overlayFillButton = null;
        JDialog details = fieldsDialog;
        clearFieldsDialogState();
        if (details != null) {
            details.setVisible(false);
            details.dispose();
        }

        JWindow window = overlay;
        JPanel host = overlayHost;
        overlay = null;
        overlayHost = null;
        overlaySignature = "";
        overlaySummary = null;
        if (host != null) {
            host.removeAll();
        }
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
