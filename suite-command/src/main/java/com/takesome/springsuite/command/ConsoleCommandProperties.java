package com.takesome.springsuite.command;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.console.command")
public class ConsoleCommandProperties {
    private boolean enabled = true;
    private String prompt = "> ";
    private boolean printWelcome = true;
    private boolean allowShutdown = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt == null || prompt.isBlank() ? "> " : prompt;
    }

    public boolean isPrintWelcome() {
        return printWelcome;
    }

    public void setPrintWelcome(boolean printWelcome) {
        this.printWelcome = printWelcome;
    }

    public boolean isAllowShutdown() {
        return allowShutdown;
    }

    public void setAllowShutdown(boolean allowShutdown) {
        this.allowShutdown = allowShutdown;
    }
}
