package com.takesome.springsuite.module;

import java.nio.file.Path;

public record SuiteModuleJarTrustReport(
        Path path,
        String fileName,
        long sizeBytes,
        String sha256,
        boolean signed,
        String certificateSha256,
        String certificateSubject,
        String certificateIssuer,
        String publisherIdentity,
        boolean loadAllowed,
        SuiteModuleTrustLevel trustLevel,
        SuiteModuleTrustMode trustMode,
        String reason
) {
    public SuiteModuleJarTrustReport {
        fileName = fileName == null || fileName.isBlank()
                ? (path == null || path.getFileName() == null ? "" : path.getFileName().toString())
                : fileName.trim();
        sha256 = sha256 == null ? "" : sha256.trim().toLowerCase();
        certificateSha256 = certificateSha256 == null ? "" : certificateSha256.trim().toLowerCase();
        certificateSubject = certificateSubject == null ? "" : certificateSubject.trim();
        certificateIssuer = certificateIssuer == null ? "" : certificateIssuer.trim();
        publisherIdentity = publisherIdentity == null ? "" : publisherIdentity.trim();
        trustLevel = trustLevel == null ? SuiteModuleTrustLevel.UNTRUSTED : trustLevel;
        trustMode = trustMode == null ? SuiteModuleTrustMode.WARN : trustMode;
        reason = reason == null ? "" : reason.trim();
    }
}
