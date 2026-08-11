package com.takesome.springsuite.module;

import java.nio.file.Files;
import java.nio.file.Path;

final class ModuleJarFingerprintReader {
    ModuleJarFingerprint fingerprint(Path jar) {
        try {
            SuiteCryptoProviderBootstrap.installBouncyCastleProvider();
            String jarHash = ModuleHashing.sha256(jar);
            ModuleCertificateIdentity cert = ModuleJarCertificateReader.read(jar);
            return new ModuleJarFingerprint(
                    jar.toString(),
                    jar.getFileName().toString(),
                    Files.size(jar),
                    jarHash,
                    cert.signed(),
                    cert.certificateSha256(),
                    cert.subject(),
                    cert.issuer(),
                    cert.publisherIdentity()
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("fingerprint failed: " + ex.getMessage(), ex);
        }
    }
}
