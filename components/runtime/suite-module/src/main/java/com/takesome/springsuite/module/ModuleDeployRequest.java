package com.takesome.springsuite.module;

public record ModuleDeployRequest(String jarPath, String targetFileName, Boolean overwrite) {
}
