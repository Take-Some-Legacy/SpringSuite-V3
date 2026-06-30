package com.takesome.springsuite.module;

import java.time.Instant;

public record SuiteModuleLifecycleContext(
        SuiteModuleLifecyclePhase phase,
        SuiteModuleManifest manifest,
        Instant timestamp
) {
    public SuiteModuleLifecycleContext {
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
