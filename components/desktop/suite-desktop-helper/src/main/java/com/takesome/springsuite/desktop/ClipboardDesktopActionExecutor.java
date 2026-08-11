package com.takesome.springsuite.desktop;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ClipboardDesktopActionExecutor extends DisabledDesktopActionExecutor {
    public ClipboardDesktopActionExecutor() {
        super(
                "clipboard-desktop-action-executor",
                "Clipboard Desktop Action Executor",
                List.of("clipboard-write", "clipboard-read", "paste", "copy", "future-backend", "disabled"),
                List.of("paste"),
                "ClipboardExecutor"
        );
    }
}
