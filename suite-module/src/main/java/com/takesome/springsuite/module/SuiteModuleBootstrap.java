package com.takesome.springsuite.module;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public final class SuiteModuleBootstrap {
    private static final String MODULE_CONFIG_FILE = "suite-modules.yml";

    private SuiteModuleBootstrap() {
    }

    public static SuiteModuleBootstrapResult bootstrap() {
        SuiteCryptoProviderBootstrap.installBouncyCastleProvider();
        Path runtimeRoot = resolveRuntimeRoot();
        Path preConfig = runtimeRoot.resolve("config").resolve(MODULE_CONFIG_FILE).toAbsolutePath().normalize();
        PreModuleConfig config = readPreModuleConfig(runtimeRoot, preConfig);

        try {
            Files.createDirectories(config.modulesDir());
            List<Path> discoveredJars = config.enabled() ? scanJars(config.modulesDir(), config.recursive()) : List.of();
            List<SuiteModuleJarTrustReport> trustReports = discoveredJars.stream()
                    .map(jar -> trustReport(jar, config))
                    .toList();
            List<Path> loadableJars = trustReports.stream()
                    .filter(SuiteModuleJarTrustReport::loadAllowed)
                    .map(SuiteModuleJarTrustReport::path)
                    .toList();

            ClassLoader parent = Thread.currentThread().getContextClassLoader();
            if (parent == null) {
                parent = SuiteModuleBootstrap.class.getClassLoader();
            }
            URL[] urls = new URL[loadableJars.size()];
            for (int i = 0; i < loadableJars.size(); i++) {
                urls[i] = loadableJars.get(i).toUri().toURL();
            }
            URLClassLoader moduleClassLoader = new URLClassLoader(urls, parent);
            Thread.currentThread().setContextClassLoader(moduleClassLoader);

            System.setProperty("suite.modules.enabled", Boolean.toString(config.enabled()));
            System.setProperty("suite.modules.dir", config.modulesDir().toString());
            System.setProperty("suite.modules.recursive", Boolean.toString(config.recursive()));
            System.setProperty("suite.modules.count", Integer.toString(loadableJars.size()));
            System.setProperty("suite.modules.discovered.count", Integer.toString(discoveredJars.size()));
            System.setProperty("suite.modules.blocked.count", Long.toString(trustReports.stream().filter(report -> !report.loadAllowed()).count()));
            System.setProperty("suite.modules.jars", String.join(";", loadableJars.stream().map(Path::toString).toList()));
            System.setProperty("suite.modules.trust.mode", config.trustMode().name());
            System.setProperty("suite.modules.trust.require.signature", Boolean.toString(config.requireSignature()));
            System.setProperty("suite.modules.trust.allow.unsigned.local", Boolean.toString(config.allowUnsignedLocal()));
            System.setProperty("suite.modules.trust.allow.unpinned.signed", Boolean.toString(config.allowUnpinnedSigned()));
            System.setProperty("suite.modules.trust.trusted.count", Long.toString(trustReports.stream().filter(SuiteModuleBootstrap::isPinnedTrust).count()));
            System.setProperty("suite.modules.trust.reports", trustReports.stream()
                    .map(SuiteModuleBootstrap::compactReport)
                    .collect(Collectors.joining(";")));
            System.setProperty("suite.modules.trust.store.path", config.trustStorePath().toString());
            System.setProperty("suite.modules.trust.store.loaded", Boolean.toString(config.trustStoreLoaded()));
            System.setProperty("suite.modules.trust.store.created", Boolean.toString(config.trustStoreCreated()));
            System.setProperty("suite.modules.trust.store.records", Integer.toString(config.trustStoreRecordCount()));
            System.setProperty("suite.modules.trust.store.trusted", Integer.toString(config.trustStoreTrustedCount()));
            System.setProperty("suite.modules.trust.store.revoked", Integer.toString(config.trustStoreRevokedCount()));
            System.setProperty("suite.modules.trust.store.expired", Integer.toString(config.trustStoreExpiredCount()));
            System.setProperty("suite.modules.trust.store.message", config.trustStoreMessage());

            return new SuiteModuleBootstrapResult(runtimeRoot, config.modulesDir(), config.enabled(), config.recursive(), loadableJars, trustReports, moduleClassLoader);
        } catch (IOException ex) {
            throw new IllegalStateException("SpringSuite module bootstrap failed: " + config.modulesDir(), ex);
        }
    }

    private static boolean isPinnedTrust(SuiteModuleJarTrustReport report) {
        return report.trustLevel() == SuiteModuleTrustLevel.TRUSTED_HASH
                || report.trustLevel() == SuiteModuleTrustLevel.TRUSTED_CERTIFICATE
                || report.trustLevel() == SuiteModuleTrustLevel.TRUSTED_PUBLISHER;
    }

    private static String compactReport(SuiteModuleJarTrustReport report) {
        return report.fileName()
                + "=" + report.trustLevel()
                + ":" + report.loadAllowed()
                + ":jar=" + report.sha256()
                + ":cert=" + report.certificateSha256()
                + ":publisher=" + sanitizeReportValue(report.publisherIdentity());
    }

    private static String sanitizeReportValue(String value) {
        return value == null ? "" : value.replace(';', ',').replace(':', '_').trim();
    }

    private static SuiteModuleJarTrustReport trustReport(Path jar, PreModuleConfig config) {
        try {
            String jarSha256 = sha256(jar);
            CertificateIdentity certificate = certificateIdentity(jar);
            long size = Files.size(jar);
            String certSha256 = certificate.certificateSha256();
            String publisherIdentity = certificate.publisherIdentity();

            if (config.blockedHashes().contains(jarSha256)) {
                return report(jar, size, jarSha256, certificate, false, SuiteModuleTrustLevel.BLOCKED_HASH, config.trustMode(), "jar hash is explicitly blocked");
            }
            if (!certSha256.isBlank() && config.blockedCertificateHashes().contains(certSha256)) {
                return report(jar, size, jarSha256, certificate, false, SuiteModuleTrustLevel.BLOCKED_CERTIFICATE, config.trustMode(), "signing certificate hash is explicitly blocked");
            }
            if (!publisherIdentity.isBlank() && config.blockedPublishers().contains(normalizePublisher(publisherIdentity))) {
                return report(jar, size, jarSha256, certificate, false, SuiteModuleTrustLevel.BLOCKED_PUBLISHER, config.trustMode(), "publisher identity is explicitly blocked");
            }
            if (config.trustedHashes().contains(jarSha256)) {
                return report(jar, size, jarSha256, certificate, true, SuiteModuleTrustLevel.TRUSTED_HASH, config.trustMode(), "jar hash is explicitly trusted");
            }
            if (!certSha256.isBlank() && config.trustedCertificateHashes().contains(certSha256)) {
                return report(jar, size, jarSha256, certificate, true, SuiteModuleTrustLevel.TRUSTED_CERTIFICATE, config.trustMode(), "signing certificate hash is explicitly trusted");
            }
            if (!publisherIdentity.isBlank() && config.trustedPublishers().contains(normalizePublisher(publisherIdentity))) {
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
            return report(jar, 0L, "", CertificateIdentity.unsigned(), false, SuiteModuleTrustLevel.UNTRUSTED, config.trustMode(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private static SuiteModuleJarTrustReport report(
            Path jar,
            long size,
            String sha256,
            CertificateIdentity certificate,
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

    private static List<Path> scanJars(Path modulesDir, boolean recursive) throws IOException {
        int depth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> stream = Files.walk(modulesDir, depth)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static String sha256(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return sha256(input);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME);
        return hex(digest.digest(bytes));
    }

    private static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static CertificateIdentity certificateIdentity(Path path) {
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
                    return CertificateIdentity.unsigned();
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
                return CertificateIdentity.unsigned();
            }
            JcaX509CertificateHolder holder = new JcaX509CertificateHolder(firstCertificate);
            String subject = holder.getSubject().toString();
            String issuer = holder.getIssuer().toString();
            String certificateSha256 = sha256(firstCertificate.getEncoded());
            return new CertificateIdentity(true, certificateSha256, subject, issuer, subject);
        } catch (Exception ignored) {
            return CertificateIdentity.unsigned();
        }
    }

    private static PreModuleConfig readPreModuleConfig(Path runtimeRoot, Path configPath) {
        boolean enabled = readBoolean(System.getProperty("suite.modules.enabled"), env("SPRING_SUITE_MODULES_ENABLED"), true);
        boolean recursive = readBoolean(System.getProperty("suite.modules.recursive"), env("SPRING_SUITE_MODULES_RECURSIVE"), false);
        String dir = firstNonBlank(System.getProperty("suite.modules.dir"), env("SPRING_SUITE_MODULES_DIR"));
        SuiteModuleTrustMode trustMode = readTrustMode(System.getProperty("suite.modules.trust.mode"), env("SPRING_SUITE_MODULES_TRUST_MODE"), SuiteModuleTrustMode.WARN);
        boolean allowUnsignedLocal = readBoolean(System.getProperty("suite.modules.trust.allowUnsignedLocal"), env("SPRING_SUITE_MODULES_TRUST_ALLOW_UNSIGNED_LOCAL"), true);
        boolean requireSignature = readBoolean(System.getProperty("suite.modules.trust.requireSignature"), env("SPRING_SUITE_MODULES_TRUST_REQUIRE_SIGNATURE"), false);
        boolean allowUnpinnedSigned = readBoolean(System.getProperty("suite.modules.trust.allowUnpinnedSigned"), env("SPRING_SUITE_MODULES_TRUST_ALLOW_UNPINNED_SIGNED"), false);
        LinkedHashSet<String> trustedHashes = new LinkedHashSet<>();
        LinkedHashSet<String> blockedHashes = new LinkedHashSet<>();
        LinkedHashSet<String> trustedCertificateHashes = new LinkedHashSet<>();
        LinkedHashSet<String> blockedCertificateHashes = new LinkedHashSet<>();
        LinkedHashSet<String> trustedPublishers = new LinkedHashSet<>();
        LinkedHashSet<String> blockedPublishers = new LinkedHashSet<>();
        addCsv(trustedHashes, System.getProperty("suite.modules.trust.trustedSha256"));
        addCsv(blockedHashes, System.getProperty("suite.modules.trust.blockedSha256"));
        addCsv(trustedCertificateHashes, System.getProperty("suite.modules.trust.trustedCertificateSha256"));
        addCsv(blockedCertificateHashes, System.getProperty("suite.modules.trust.blockedCertificateSha256"));
        addCsv(trustedPublishers, System.getProperty("suite.modules.trust.trustedPublishers"));
        addCsv(blockedPublishers, System.getProperty("suite.modules.trust.blockedPublishers"));

        if (Files.isRegularFile(configPath)) {
            try {
                String yaml = Files.readString(configPath, StandardCharsets.UTF_8);
                enabled = readBoolean(findYamlScalar(yaml, "suite.modules.enabled"), Boolean.toString(enabled), enabled);
                recursive = readBoolean(findYamlScalar(yaml, "suite.modules.recursive"), Boolean.toString(recursive), recursive);
                dir = firstNonBlank(dir, findYamlScalar(yaml, "suite.modules.directory"), findYamlScalar(yaml, "suite.modules.dir"));
                trustMode = readTrustMode(findYamlScalar(yaml, "suite.modules.trust.mode"), trustMode.name(), trustMode);
                allowUnsignedLocal = readBoolean(findYamlScalar(yaml, "suite.modules.trust.allow-unsigned-local"), Boolean.toString(allowUnsignedLocal), allowUnsignedLocal);
                requireSignature = readBoolean(findYamlScalar(yaml, "suite.modules.trust.require-signature"), Boolean.toString(requireSignature), requireSignature);
                allowUnpinnedSigned = readBoolean(findYamlScalar(yaml, "suite.modules.trust.allow-unpinned-signed"), Boolean.toString(allowUnpinnedSigned), allowUnpinnedSigned);
                trustedHashes.addAll(findYamlList(yaml, "suite.modules.trust.trusted-sha256"));
                blockedHashes.addAll(findYamlList(yaml, "suite.modules.trust.blocked-sha256"));
                trustedCertificateHashes.addAll(findYamlList(yaml, "suite.modules.trust.trusted-certificate-sha256"));
                blockedCertificateHashes.addAll(findYamlList(yaml, "suite.modules.trust.blocked-certificate-sha256"));
                trustedPublishers.addAll(findYamlList(yaml, "suite.modules.trust.trusted-publishers"));
                blockedPublishers.addAll(findYamlList(yaml, "suite.modules.trust.blocked-publishers"));
            } catch (IOException ignored) {
                // Keep pre-bootstrap defaults. Full config bootstrap will handle file creation/supplement later.
            }
        }

        SuiteModulePublisherTrustStore.TrustStoreLoadResult trustStore = SuiteModulePublisherTrustStore.load(runtimeRoot);
        trustedCertificateHashes.addAll(trustStore.trustedCertificateHashes());
        blockedCertificateHashes.addAll(trustStore.blockedCertificateHashes());
        trustedPublishers.addAll(trustStore.trustedPublishers());
        blockedPublishers.addAll(trustStore.blockedPublishers());

        Path modulesDir = resolveRuntimePath(runtimeRoot, firstNonBlank(dir, "modules"));
        return new PreModuleConfig(
                enabled,
                recursive,
                modulesDir,
                trustMode,
                allowUnsignedLocal,
                requireSignature,
                allowUnpinnedSigned,
                normalizeHashes(trustedHashes),
                normalizeHashes(blockedHashes),
                normalizeHashes(trustedCertificateHashes),
                normalizeHashes(blockedCertificateHashes),
                normalizePublishers(trustedPublishers),
                normalizePublishers(blockedPublishers),
                trustStore.path(),
                trustStore.loaded(),
                trustStore.created(),
                trustStore.recordCount(),
                trustStore.trustedCount(),
                trustStore.revokedCount(),
                trustStore.expiredCount(),
                trustStore.message()
        );
    }

    private static Set<String> normalizeHashes(Set<String> hashes) {
        return hashes.stream()
                .map(SuiteModuleBootstrap::normalizeHash)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String normalizeHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
    }

    private static Set<String> normalizePublishers(Set<String> publishers) {
        return publishers.stream()
                .map(SuiteModuleBootstrap::normalizePublisher)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String normalizePublisher(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static void addCsv(Set<String> target, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                target.add(part.trim());
            }
        }
    }

    private static SuiteModuleTrustMode readTrustMode(String primary, String secondary, SuiteModuleTrustMode fallback) {
        String raw = firstNonBlank(primary, secondary);
        if (raw.isBlank()) {
            return fallback;
        }
        try {
            return SuiteModuleTrustMode.valueOf(raw.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static String findYamlScalar(String yaml, String dottedKey) {
        String[] parts = dottedKey.split("\\.");
        int expectedIndent = 0;
        for (String rawLine : yaml.split("\\R")) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = countIndent(line);
            String trimmed = line.trim();
            if (!trimmed.contains(":")) {
                continue;
            }
            String key = trimmed.substring(0, trimmed.indexOf(':')).trim();
            String value = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            if (expectedIndent < parts.length - 1 && indent == expectedIndent * 2 && key.equals(parts[expectedIndent]) && value.isBlank()) {
                expectedIndent++;
                continue;
            }
            if (expectedIndent == parts.length - 1 && indent == expectedIndent * 2 && key.equals(parts[expectedIndent])) {
                return stripQuotes(value);
            }
        }
        return "";
    }

    private static List<String> findYamlList(String yaml, String dottedKey) {
        String[] parts = dottedKey.split("\\.");
        int expectedIndent = 0;
        int listIndent = -1;
        ArrayList<String> values = new ArrayList<>();
        for (String rawLine : yaml.split("\\R")) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = countIndent(line);
            String trimmed = line.trim();
            if (listIndent >= 0) {
                if (indent < listIndent) {
                    break;
                }
                if (indent == listIndent && trimmed.startsWith("-")) {
                    values.add(stripQuotes(trimmed.substring(1).trim()));
                    continue;
                }
            }
            if (!trimmed.contains(":")) {
                continue;
            }
            String key = trimmed.substring(0, trimmed.indexOf(':')).trim();
            String value = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            if (expectedIndent < parts.length - 1 && indent == expectedIndent * 2 && key.equals(parts[expectedIndent]) && value.isBlank()) {
                expectedIndent++;
                continue;
            }
            if (expectedIndent == parts.length - 1 && indent == expectedIndent * 2 && key.equals(parts[expectedIndent])) {
                if (!value.isBlank()) {
                    addCsv(values, stripQuotes(value));
                }
                listIndent = indent + 2;
            }
        }
        return values;
    }

    private static void addCsv(List<String> target, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                target.add(part.trim());
            }
        }
    }

    private static String stripComment(String line) {
        int index = line.indexOf('#');
        return index >= 0 ? line.substring(0, index) : line;
    }

    private static int countIndent(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static boolean readBoolean(String primary, String secondary, boolean fallback) {
        String raw = firstNonBlank(primary, secondary);
        if (raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> fallback;
        };
    }

    private static Path resolveRuntimeRoot() {
        String explicit = firstNonBlank(System.getProperty("suite.home"), env("SPRING_SUITE_HOME"));
        if (!explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static Path resolveRuntimePath(Path runtimeRoot, String configuredPath) {
        Path path = Paths.get(configuredPath);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return runtimeRoot.resolve(path).toAbsolutePath().normalize();
    }

    private static String stripQuotes(String value) {
        String v = value == null ? "" : value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record CertificateIdentity(
            boolean signed,
            String certificateSha256,
            String subject,
            String issuer,
            String publisherIdentity
    ) {
        private static CertificateIdentity unsigned() {
            return new CertificateIdentity(false, "", "", "", "");
        }
    }

    private record PreModuleConfig(
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
        private PreModuleConfig {
            trustStoreMessage = trustStoreMessage == null ? "" : trustStoreMessage;
        }
    }
}
