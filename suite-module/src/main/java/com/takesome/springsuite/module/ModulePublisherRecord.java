package com.takesome.springsuite.module;

import java.util.List;

public record ModulePublisherRecord(String id, String name, String trustLevel, boolean revoked, String expiresAt, List<String> certificateSha256, List<String> publisherIdentities) {
    public ModulePublisherRecord {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        trustLevel = trustLevel == null || trustLevel.isBlank() ? "untrusted" : trustLevel.trim();
        expiresAt = expiresAt == null ? "" : expiresAt.trim();
        certificateSha256 = certificateSha256 == null ? List.of() : List.copyOf(certificateSha256);
        publisherIdentities = publisherIdentities == null ? List.of() : List.copyOf(publisherIdentities);
    }
}
