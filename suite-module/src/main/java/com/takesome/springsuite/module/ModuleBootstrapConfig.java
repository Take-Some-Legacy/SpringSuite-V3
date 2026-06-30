package com.takesome.springsuite.module;

import java.nio.file.Path;
import java.util.Set;

public record ModuleBootstrapConfig(
        boolean enabled,
        boolean recursive,
        Path modulesDir,
        SuiteModuleTrustMode trustMode,
        boolean allowUnsignedLocal,
        boolean requireSignature,
        boolean allowUnpinnedSigned,
        Set<String> trustedHashes,
        Set<String> blockedHashes,
        Set<String> trustedCertificateHashes,
        Set<String> blockedCertificateHashes,
        Set<String> trustedPublishers,
        Set<String> blockedPublishers,
        Path trustStorePath,
        boolean trustStoreLoaded,
        boolean trustStoreCreated,
        int trustStoreRecordCount,
        int trustStoreTrustedCount,
        int trustStoreRevokedCount,
        int trustStoreExpiredCount,
        String trustStoreMessage
) {
    public ModuleBootstrapConfig {
        trustedHashes = trustedHashes == null ? Set.of() : Set.copyOf(trustedHashes);
        blockedHashes = blockedHashes == null ? Set.of() : Set.copyOf(blockedHashes);
        trustedCertificateHashes = trustedCertificateHashes == null ? Set.of() : Set.copyOf(trustedCertificateHashes);
        blockedCertificateHashes = blockedCertificateHashes == null ? Set.of() : Set.copyOf(blockedCertificateHashes);
        trustedPublishers = trustedPublishers == null ? Set.of() : Set.copyOf(trustedPublishers);
        blockedPublishers = blockedPublishers == null ? Set.of() : Set.copyOf(blockedPublishers);
        trustStoreMessage = trustStoreMessage == null ? "" : trustStoreMessage;
    }
}
