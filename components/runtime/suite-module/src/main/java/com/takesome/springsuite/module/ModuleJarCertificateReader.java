package com.takesome.springsuite.module;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;

final class ModuleJarCertificateReader {
    private ModuleJarCertificateReader() {
    }

    static ModuleCertificateIdentity read(Path path) {
        try (JarFile jar = new JarFile(path.toFile(), true)) {
            byte[] buffer = new byte[8192];
            X509Certificate firstCertificate = null;
            boolean signedEntrySeen = false;
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().toUpperCase(Locale.ROOT);
                try (InputStream input = jar.getInputStream(entry)) {
                    while (input.read(buffer) >= 0) {
                        // Read fully to trigger JarVerifier.
                    }
                }
                if (name.startsWith("META-INF/")) {
                    continue;
                }
                Certificate[] certificates = entry.getCertificates();
                if (certificates == null || certificates.length == 0) {
                    return ModuleCertificateIdentity.unsigned();
                }
                signedEntrySeen = true;
                if (firstCertificate == null) {
                    for (Certificate certificate : certificates) {
                        if (certificate instanceof X509Certificate x509Certificate) {
                            firstCertificate = x509Certificate;
                            break;
                        }
                    }
                }
            }
            if (!signedEntrySeen || firstCertificate == null) {
                return ModuleCertificateIdentity.unsigned();
            }
            JcaX509CertificateHolder holder = new JcaX509CertificateHolder(firstCertificate);
            String subject = holder.getSubject().toString();
            String issuer = holder.getIssuer().toString();
            String certificateSha256 = ModuleHashing.sha256(firstCertificate.getEncoded());
            return new ModuleCertificateIdentity(true, certificateSha256, subject, issuer, subject);
        } catch (Exception ignored) {
            return ModuleCertificateIdentity.unsigned();
        }
    }
}

record ModuleCertificateIdentity(
        boolean signed,
        String certificateSha256,
        String subject,
        String issuer,
        String publisherIdentity
) {
    static ModuleCertificateIdentity unsigned() {
        return new ModuleCertificateIdentity(false, "", "", "", "");
    }
}
