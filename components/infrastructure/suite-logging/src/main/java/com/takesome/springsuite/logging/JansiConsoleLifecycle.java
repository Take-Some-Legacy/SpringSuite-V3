package com.takesome.springsuite.logging;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JansiConsoleLifecycle {
    private static final Logger log = LoggerFactory.getLogger(JansiConsoleLifecycle.class);

    @jakarta.annotation.PostConstruct
    public void report() {
        log.info("Jansi console colors installed early={}", ConsoleAnsiBootstrap.isInstalled());
    }

    @PreDestroy
    public void uninstall() {
        if (ConsoleAnsiBootstrap.isInstalled()) {
            ConsoleAnsiBootstrap.uninstall();
            log.info("Jansi console colors uninstalled");
        }
    }
}
