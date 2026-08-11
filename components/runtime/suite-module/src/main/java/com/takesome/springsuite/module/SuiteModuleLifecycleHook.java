package com.takesome.springsuite.module;

public interface SuiteModuleLifecycleHook {
    void onModuleLifecycle(SuiteModuleLifecycleContext context) throws Exception;
}
