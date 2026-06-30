package com.takesome.springsuite.module;

import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public interface SuiteModule {
    SuiteModuleManifest manifest();

    default List<SuiteConfigFile> configFiles() {
        return List.of();
    }

    default List<SuiteCommand> commands() {
        return List.of();
    }

    default List<SuiteModuleCapability> capabilities() {
        return List.of();
    }

    default List<SuiteModuleLifecycleHook> lifecycleHooks() {
        return List.of();
    }
}
