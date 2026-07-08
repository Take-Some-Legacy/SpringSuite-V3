package com.takesome.springsuite.desktop;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MouseDesktopActionExecutor extends DisabledDesktopActionExecutor {
    public MouseDesktopActionExecutor() {
        super(
                "mouse-desktop-action-executor",
                "Mouse Desktop Action Executor",
                List.of("pointer-input", "click", "move", "future-backend", "disabled"),
                List.of("click"),
                "MouseExecutor"
        );
    }
}
