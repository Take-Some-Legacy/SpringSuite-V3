package com.takesome.springsuite.module;

import java.nio.file.Files;
import java.nio.file.Path;

final class ModuleJarTrustReporter {
    private ModuleJarTrustReporter() {
    }

    static SuiteModuleJarTrustReport report(Path jar, ModuleBootstrapConfig config) {
        try {
            String jarSha256 = ModuleHashing.sha256(jar);
            ModuleCertificateIdentity certificate = ModuleJarCertificateReader.read(jar);
            long size = Files.size(jar);
            String certSha256 = certificate.certificateSha256();
            String publisherIdentity = certificate.publisherIdentity();

            if (config.blockedHashes().contains(jarSha256)) {
                return report(jar, size, jarSha256, certificate, false, SuiteModuleTrustLevel.BLOCKED_HASH, config.trustMode(), "jar hash is explicitly blocked");
            }
            if (!certSha256.isBlank() && config.blockedCertificateHashes().contains(certSha256)) {
                return report(jar, size, jarSha256, certificate, false, SuiteModuleTrustLevel.BLOCKED_CERTIFICATE, config.trustMode(), "signing certificate hash is explicitly blocked");
            }
            if (!publisherIdentity.isBlank() && config.blockedPublishers().contains(ModuleTrustPolicyValues.normalizePublisher(publisherIdentity))) {
                return report(jar, size, jarSha256, certificate, false, SuiteModuleTrustLevel.BLOCKED_PUBLISHER, config.trustMode(), "publisher identity is explicitly blocked");
            }
            if (config.trustedHashes().contains(jarSha256)) {
                return report(jar, size, jarSha256, certificate, true, SuiteModuleTrustLevel.TRUSTED_HASH, config.trustMode(), "jar hash is explicitly trusted");
            }
            if (!certSha256.isBlank() && config.trustedCertificateHashes().contains(certSha256)) {
                return report(jar, size, jarSha256, certificate, true, SuiteModuleTrustLevel.TRUSTED_CERTIFICATE, config.trustMode(), "signing certificate hash is explicitly trusted");
            }
            if (!publisherIdentity.isBlank() && config.trustedPublishers().contains(ModuleTrustPolicyValues.normalizePublisher(publisherIdentity))) {
                return report(jar, size, jarSha256, certificate, true, SuiteModuleTrustLevel.TRUSTED_PUBLISHER, config.trustMode(), "publisher identity is explicitly trusted");
            }
            if (config.requireSignature() && !certificate.signed()) {
                return report(jar, size, jarSha256, certificate, false, SuiteModuleTrustLevel.SIGNATURE_REQUIRED, config.trustMode(), "signature is required but jar is unsigned");
            }
            if (certificate.signed()) {
                if (config.trustMode() == SuiteModuleTrustMode.ENFORCE && !config.allowUnpinnedSigned()) {
                    return report(jar, size, jarSha256, certificate, false, SuiteModuleTrustLevel.UNPINNED_SIGNED, config.trustMode(), "signed jar is not pinned by certificate or publisher policy");
                }
                return report(jar, size, jarSha256, certificate, true, SuiteModuleTrustLevel.SIGNED, config.trustMode(), "signed jar accepted by policy");
            }
            if (config.trustMode() == SuiteModuleTrustMode.ENFORCE) {
                return report(jar, size, jarSha256, certificate, false, SuiteModuleTrustLevel.UNTRUSTED, config.trustMode(), "untrusted jar blocked by ENFORCE mode");
            }
            if (config.allowUnsignedLocal()) {
                return report(jar, size, jarSha256, certificate, true, SuiteModuleTrustLevel.LOCAL_UNVERIFIED, config.trustMode(), "unsigned local jar accepted by policy");
            }
            return report(jar, size, jarSha256, certificate, false, SuiteModuleTrustLevel.UNTRUSTED, config.trustMode(), "unsigned local jar rejected by policy");
        } catch (Exception ex) {
            return report(jar, 0L, "", ModuleCertificateIdentity.unsigned(), false, SuiteModuleTrustLevel.UNTRUSTED, config.trustMode(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    static boolean isPinnedTrust(SuiteModuleJarTrustReport report) {
        return report.trustLevel() == SuiteModuleTrustLevel.TRUSTED_HASH
                || report.trustLevel() == SuiteModuleTrustLevel.TRUSTED_CERTIFICATE
                || report.trustLevel() == SuiteModuleTrustLevel.TRUSTED_PUBLISHER;
    }

    static String compactReport(SuiteModuleJarTrustReport report) {
        return report.fileName()
                + "=" + report.trustLevel()
                + ":" + report.loadAllowed()
                + ":jar=" + report.sha256()
                + ":cert=" + report.certificateSha256()
                + ":publisher=" + sanitizeReportValue(report.publisherIdentity());
    }

    private static SuiteModuleJarTrustReport report(
            Path jar,
            long size,
            String sha256,
            ModuleCertificateIdentity certificate,
            boolean allowed,
            SuiteModuleTrustLevel level,
            SuiteModuleTrustMode mode,
            String reason
    ) {
        return new SuiteModuleJarTrustReport(
                jar.toAbsolutePath().normalize(),
                jar.getFileName().toString(),
                size,
                sha256,
                certificate.signed(),
                certificate.certificateSha256(),
                certificate.subject(),
                certificate.issuer(),
                certificate.publisherIdentity(),
                allowed,
                level,
                mode,
                reason
        );
    }

    private static String sanitizeReportValue(String value) {
        return value == null ? "" : value.replace(';', ',').replace(':', '_').trim();
    }
}
