package com.takesome.springsuite.desktop;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BrowserDomDesktopActionExecutor extends DisabledDesktopActionExecutor {
    public BrowserDomDesktopActionExecutor() {
        super(
                "browser-dom-desktop-action-executor",
                "Browser DOM Desktop Action Executor",
                List.of("browser-dom", "form-fill", "field-select", "submit", "future-backend", "disabled"),
                List.of("fill", "select", "check", "uncheck", "submit"),
                "BrowserDomExecutor"
        );
    }
}
