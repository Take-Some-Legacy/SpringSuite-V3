package com.takesome.springsuite.module;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public record RegisteredSuiteModule(
        SuiteModuleManifest manifest,
        String providerClass,
        boolean enabled,
        SuiteModuleActivationStatus activationStatus,
        List<String> activationProblems,
        List<String> missingDependencies,
        List<SuiteConfigFile> configFiles,
        List<CommandDescriptor> commands,
        List<SuiteModuleCapability> capabilities,
        int lifecycleHookCount,
        String suiteApiVersion,
        SuiteModuleIsolationPolicy isolationPolicy
) {
    public RegisteredSuiteModule {
        activationStatus = activationStatus == null ? SuiteModuleActivationStatus.DISABLED_INVALID_MANIFEST : activationStatus;
        activationProblems = activationProblems == null ? List.of() : List.copyOf(activationProblems);
        missingDependencies = missingDependencies == null ? List.of() : List.copyOf(missingDependencies);
        configFiles = configFiles == null ? List.of() : List.copyOf(configFiles);
        commands = commands == null ? List.of() : List.copyOf(commands);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        suiteApiVersion = suiteApiVersion == null ? "" : suiteApiVersion.trim();
        isolationPolicy = isolationPolicy == null ? SuiteModuleIsolationPolicy.SHARED_CLASSPATH : isolationPolicy;
    }
}
