package com.takesome.springsuite.fnmodule;

import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.config.SuiteConfigFile;
import com.takesome.springsuite.module.SuiteModule;
import com.takesome.springsuite.module.SuiteModuleCapability;
import com.takesome.springsuite.module.SuiteModuleManifest;
import java.util.List;
import java.util.Map;

public final class FnSuiteModule implements SuiteModule {
    public static final String VERSION = "0.1.11";

    private final SuiteModuleManifest manifest = new SuiteModuleManifest(
            "spring-suite-fn",
            "SpringSuite FN Operator Module",
            VERSION,
            "TakeSome / SuiteLab",
            "External signed FN operator module with twelve configurable explicit function-button routes.",
            List.of(),
            List.of(),
            Map.of(
                    "packaging", "external-module",
                    "commandNamespace", "fn",
                    "buttonCount", 12,
                    "defaultRoute", "FN-12 -> desktop.screenshot.send",
                    "destination", "active-chat"
            )
    );

    private final List<SuiteCommand> commands = List.of(new FnCommand());

    @Override
    public SuiteModuleManifest manifest() {
        return manifest;
    }

    @Override
    public List<SuiteConfigFile> configFiles() {
        return List.of(new SuiteConfigFile(
                "suite-fn-module",
                "suite-fn.yml",
                "suite-fn-default.yml",
                720
        ));
    }

    @Override
    public List<SuiteCommand> commands() {
        return commands;
    }

    @Override
    public List<SuiteModuleCapability> capabilities() {
        return List.of(
                new SuiteModuleCapability(
                        "spring-suite-fn.12-button-registry",
                        "operator-fn-registry",
                        "Defines twelve explicit FN operator buttons through suite-fn.yml.",
                        Map.of("buttons", 12, "config", "suite-fn.yml")
                ),
                new SuiteModuleCapability(
                        "spring-suite-fn.active-chat-screenshot-route",
                        "mcp-tool-route",
                        "Binds FN-12 to desktop.screenshot.send for active-chat image handoff.",
                        Map.of("fn", "FN-12", "route", "desktop.screenshot.send", "destination", "active-chat")
                )
        );
    }
}
