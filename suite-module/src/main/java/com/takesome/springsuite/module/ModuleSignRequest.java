package com.takesome.springsuite.module;

public record ModuleSignRequest(String jarPath, String outputPath, String keystorePath, String alias, String storePassEnv, String keyPassEnv) {
}
