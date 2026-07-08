package com.takesome.springsuite.desktop;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KeyboardDesktopActionExecutor extends DisabledDesktopActionExecutor {
    public KeyboardDesktopActionExecutor() {
        super(
                "keyboard-desktop-action-executor",
                "Keyboard Desktop Action Executor",
                List.of("keyboard-input", "type", "hotkey", "future-backend", "disabled"),
                List.of("type", "hotkey"),
                "KeyboardExecutor"
        );
    }
}
