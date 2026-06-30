package com.takesome.springsuite.module;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Service;

@Service
public class SuitePublisherManagementService {
    public List<ModulePublisherRecord> listPublishers() {
        ensureTrustStore();
        return parsePublishers(readTrustStore());
    }

    public ModuleJarFingerprint fingerprint(PathRequest request) {
        return fingerprint(resolvePath(request.path()));
    }

    public ModulePublisherRecord trustCertificate(PublisherMutationRequest request) {
        ModuleJarFingerprint fingerprint = fingerprintIfPresent(request.jarPath());
        String cert = firstNonBlank(request.certificateSha256(), fingerprint == null ? "" : fingerprint.certificateSha256());
        String publisher = firstNonBlank(request.publisherIdentity(), fingerprint == null ? "" : fingerprint.publisherIdentity());
        if (cert.isBlank()) {
            throw new IllegalArgumentException("certificateSha256 or signed jarPath is required");
        }
        ModulePublisherRecord record = new ModulePublisherRecord(
                firstNonBlank(request.id(), idFromPublisher(publisher, cert)),
                firstNonBlank(request.name(), publisher.isBlank() ? "Trusted Certificate" : publisher),
                "trusted",
                false,
                firstNonBlank(request.expiresAt(), "2099-12-31"),
                List.of(cert),
                publisher.isBlank() ? List.of() : List.of(publisher)
        );
        upsert(record);
        return record;
    }

    public ModulePublisherRecord trustPublisher(PublisherMutationRequest request) {
        ModuleJarFingerprint fingerprint = fingerprintIfPresent(request.jarPath());
        String publisher = firstNonBlank(request.publisherIdentity(), fingerprint == null ? "" : fingerprint.publisherIdentity());
        String cert = firstNonBlank(request.certificateSha256(), fingerprint == null ? "" : fingerprint.certificateSha256());
        if (publisher.isBlank()) {
            throw new IllegalArgumentException("publisherIdentity or signed jarPath is required");
        }
        ModulePublisherRecord record = new ModulePublisherRecord(
                firstNonBlank(request.id(), idFromPublisher(publisher, cert)),
                firstNonBlank(request.name(), publisher),
                "trusted",
                false,
                firstNonBlank(request.expiresAt(), "2099-12-31"),
                cert.isBlank() ? List.of() : List.of(cert),
                List.of(publisher)
        );
        upsert(record);
        return record;
    }

    public ModulePublisherRecord blockCertificate(PublisherMutationRequest request) {
        ModuleJarFingerprint fingerprint = fingerprintIfPresent(request.jarPath());
        String cert = firstNonBlank(request.certificateSha256(), fingerprint == null ? "" : fingerprint.certificateSha256());
        String publisher = firstNonBlank(request.publisherIdentity(), fingerprint == null ? "" : fingerprint.publisherIdentity());
        if (cert.isBlank()) {
            throw new IllegalArgumentException("certificateSha256 or signed jarPath is required");
        }
        ModulePublisherRecord record = new ModulePublisherRecord(
                firstNonBlank(request.id(), idFromPublisher(publisher, cert)),
                firstNonBlank(request.name(), publisher.isBlank() ? "Blocked Certificate" : publisher),
                "blocked",
                true,
                firstNonBlank(request.expiresAt(), ""),
                List.of(cert),
                publisher.isBlank() ? List.of() : List.of(publisher)
        );
        upsert(record);
        return record;
    }

    public ModulePublisherRecord revoke(PublisherMutationRequest request) {
        String key = firstNonBlank(request.id(), request.certificateSha256(), request.publisherIdentity());
        if (key.isBlank()) {
            throw new IllegalArgumentException("id, certificateSha256, or publisherIdentity is required");
        }
        ArrayList<ModulePublisherRecord> records = new ArrayList<>(listPublishers());
        for (int i = 0; i < records.size(); i++) {
            ModulePublisherRecord record = records.get(i);
            if (matches(record, key)) {
                ModulePublisherRecord revoked = new ModulePublisherRecord(
                        record.id(),
                        record.name(),
                        "blocked",
                        true,
                        record.expiresAt(),
                        record.certificateSha256(),
                        record.publisherIdentities()
                );
                records.set(i, revoked);
                writePublishers(records);
                return revoked;
            }
        }
        throw new IllegalArgumentException("publisher record not found: " + key);
    }

    public ModuleArtifactResult deploy(ModuleDeployRequest request) {
        Path source = resolvePath(request.jarPath());
        if (!Files.isRegularFile(source)) {
            return new ModuleArtifactResult(false, "source jar not found", source.toString(), List.of(), null, "", "");
        }
        Path modulesDir = modulesDir();
        try {
            Files.createDirectories(modulesDir);
            String targetName = firstNonBlank(request.targetFileName(), source.getFileName().toString());
            if (!targetName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                targetName += ".jar";
            }
            Path target = modulesDir.resolve(targetName).toAbsolutePath().normalize();
            if (Files.exists(target) && !Boolean.TRUE.equals(request.overwrite())) {
                return new ModuleArtifactResult(false, "target exists", target.toString(), List.of(), null, "", "");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return new ModuleArtifactResult(true, "module deployed; restart required to load it", target.toString(), List.of(), null, "", "");
        } catch (IOException ex) {
            return new ModuleArtifactResult(false, ex.getMessage(), "", List.of(), null, "", "");
        }
    }

    public ModuleArtifactResult sign(ModuleSignRequest request) {
        if (!Boolean.getBoolean("suite.modules.agent.sign.enabled")) {
            return new ModuleArtifactResult(false, "module signing disabled; set -Dsuite.modules.agent.sign.enabled=true", "", List.of(), null, "", "");
        }
        Path source = resolvePath(request.jarPath());
        Path output = resolvePath(firstNonBlank(request.outputPath(), request.jarPath()));
        try {
            if (!source.equals(output)) {
                Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING);
            }
            String storeToken = envRequired(request.storePassEnv());
            String keyToken = firstNonBlank(env(request.keyPassEnv()), storeToken);
            ArrayList<String> command = new ArrayList<>(List.of(
                    "jarsigner",
                    "-keystore", resolvePath(request.keystorePath()).toString(),
                    "-storepass", storeToken,
                    "-keypass", keyToken,
                    output.toString(),
                    request.alias()
            ));
            return runProcess(command, output.getParent(), 120, output.toString(), true);
        } catch (Exception ex) {
            return new ModuleArtifactResult(false, ex.getMessage(), output.toString(), List.of(), null, "", "");
        }
    }

    public ModuleArtifactResult build(ModuleBuildRequest request) {
        if (!Boolean.getBoolean("suite.modules.agent.build.enabled")) {
            return new ModuleArtifactResult(false, "module build disabled; set -Dsuite.modules.agent.build.enabled=true", "", request.command(), null, "", "");
        }
        if (request.command().isEmpty()) {
            return new ModuleArtifactResult(false, "build command is empty", "", List.of(), null, "", "");
        }
        return runProcess(new ArrayList<>(request.command()), resolvePath(request.cwd()), Math.min(Math.max(1, request.timeoutSeconds()), 900), "", false);
    }

    private ModuleArtifactResult runProcess(List<String> command, Path cwd, int timeoutSeconds, String path, boolean redact) {
        long start = System.nanoTime();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            process = builder.start();
            process.getOutputStream().close();
            boolean done = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return new ModuleArtifactResult(false, "process timed out", path, preview(command, redact), null, "", "");
            }
            String stdout = readBounded(process.getInputStream(), 8000);
            String stderr = readBounded(process.getErrorStream(), 4000);
            return new ModuleArtifactResult(process.exitValue() == 0, process.exitValue() == 0 ? "ok" : "non-zero exit", path, preview(command, redact), process.exitValue(), stdout, stderr);
        } catch (Exception ex) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return new ModuleArtifactResult(false, ex.getMessage(), path, preview(command, redact), null, "", "");
        }
    }

    private List<String> preview(List<String> command, boolean redact) {
        if (!redact) {
            return command;
        }
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < command.size(); i++) {
            String value = command.get(i);
            out.add(value);
            if ((value.equals("-storepass") || value.equals("-keypass")) && i + 1 < command.size()) {
                out.add("***");
                i++;
            }
        }
        return out;
    }

    private String readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0 && total < maxBytes) {
            int allowed = Math.min(read, maxBytes - total);
            out.write(buffer, 0, allowed);
            total += allowed;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private ModuleJarFingerprint fingerprintIfPresent(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return fingerprint(resolvePath(path));
    }

    private ModuleJarFingerprint fingerprint(Path jar) {
        try {
            String jarHash = sha256(Files.newInputStream(jar));
            CertificateIdentity cert = certificateIdentity(jar);
            return new ModuleJarFingerprint(jar.toString(), jar.getFileName().toString(), Files.size(jar), jarHash, cert.signed(), cert.certificateSha256(), cert.subject(), cert.issuer(), cert.publisherIdentity());
        } catch (Exception ex) {
            throw new IllegalArgumentException("fingerprint failed: " + ex.getMessage(), ex);
        }
    }

    private CertificateIdentity certificateIdentity(Path path) {
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
                try (InputStream input = jar.getInputStream(entry)) {
                    while (input.read(buffer) >= 0) {
                    }
                }
                if (entry.getName().toUpperCase(Locale.ROOT).startsWith("META-INF/")) {
                    continue;
                }
                Certificate[] certificates = entry.getCertificates();
                if (certificates == null || certificates.length == 0) {
                    return CertificateIdentity.unsigned();
                }
                signedEntrySeen = true;
                if (firstCertificate == null) {
                    for (Certificate certificate : certificates) {
                        if (certificate instanceof X509Certificate x509) {
                            firstCertificate = x509;
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
            return new CertificateIdentity(true, sha256(firstCertificate.getEncoded()), subject, issuer, subject);
        } catch (Exception ignored) {
            return CertificateIdentity.unsigned();
        }
    }

    private String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME);
        try (input) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME);
        return hex(digest.digest(bytes));
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private void upsert(ModulePublisherRecord record) {
        ArrayList<ModulePublisherRecord> records = new ArrayList<>(listPublishers());
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equalsIgnoreCase(record.id())) {
                records.set(i, record);
                writePublishers(records);
                return;
            }
        }
        records.add(record);
        writePublishers(records);
    }

    private boolean matches(ModulePublisherRecord record, String key) {
        String n = normalize(key);
        return normalize(record.id()).equals(n)
                || record.certificateSha256().stream().map(this::normalizeHash).anyMatch(n::equals)
                || record.publisherIdentities().stream().map(this::normalize).anyMatch(n::equals);
    }

    private List<ModulePublisherRecord> parsePublishers(String yaml) {
        ArrayList<ModulePublisherRecord> records = new ArrayList<>();
        String id = "";
        String name = "";
        String trust = "untrusted";
        boolean revoked = false;
        String expires = "";
        ArrayList<String> certs = new ArrayList<>();
        ArrayList<String> pubs = new ArrayList<>();
        String currentList = "";
        boolean inPublishers = false;
        boolean currentOpen = false;
        for (String raw : yaml.split("\\R")) {
            String line = raw.split("#", 2)[0];
            if (line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.equals("publishers:")) {
                inPublishers = true;
                continue;
            }
            if (!inPublishers) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                if (currentOpen) {
                    records.add(new ModulePublisherRecord(id, name, trust, revoked, expires, certs, pubs));
                }
                id = name = expires = currentList = "";
                trust = "untrusted";
                revoked = false;
                certs = new ArrayList<>();
                pubs = new ArrayList<>();
                currentOpen = true;
                String item = trimmed.substring(2).trim();
                if (item.contains(":")) {
                    String[] kv = item.split(":", 2);
                    id = unquote(kv[1].trim());
                }
                continue;
            }
            if (!currentOpen) {
                continue;
            }
            if (trimmed.startsWith("-")) {
                String value = unquote(trimmed.substring(1).trim());
                if (currentList.equals("certificate-sha256")) {
                    certs.add(value);
                } else if (currentList.equals("publisher-identities")) {
                    pubs.add(value);
                }
                continue;
            }
            if (!trimmed.contains(":")) {
                continue;
            }
            String[] kv = trimmed.split(":", 2);
            String key = kv[0].trim();
            String value = unquote(kv[1].trim());
            currentList = "";
            switch (key) {
                case "id" -> id = value;
                case "name" -> name = value;
                case "trust-level" -> trust = value;
                case "revoked" -> revoked = value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes");
                case "expires-at" -> expires = value;
                case "certificate-sha256" -> { currentList = key; if (!value.isBlank()) certs.add(value); }
                case "publisher-identities" -> { currentList = key; if (!value.isBlank()) pubs.add(value); }
                default -> { }
            }
        }
        if (currentOpen) {
            records.add(new ModulePublisherRecord(id, name, trust, revoked, expires, certs, pubs));
        }
        return records;
    }

    private void writePublishers(List<ModulePublisherRecord> records) {
        ensureTrustStore();
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 1\n");
        yaml.append("publishers:\n");
        for (ModulePublisherRecord record : records) {
            yaml.append("  - id: ").append(record.id()).append("\n");
            yaml.append("    name: ").append(quote(record.name())).append("\n");
            yaml.append("    trust-level: ").append(record.trustLevel()).append("\n");
            yaml.append("    revoked: ").append(record.revoked()).append("\n");
            if (!record.expiresAt().isBlank()) {
                yaml.append("    expires-at: ").append(record.expiresAt()).append("\n");
            }
            yaml.append("    certificate-sha256:\n");
            for (String cert : record.certificateSha256()) {
                yaml.append("      - ").append(normalizeHash(cert)).append("\n");
            }
            yaml.append("    publisher-identities:\n");
            for (String publisher : record.publisherIdentities()) {
                yaml.append("      - ").append(quote(publisher)).append("\n");
            }
        }
        try {
            Files.writeString(trustStorePath(), yaml.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write publisher trust store", ex);
        }
    }

    private void ensureTrustStore() {
        SuiteModulePublisherTrustStore.load(runtimeRoot());
    }

    private String readTrustStore() {
        try {
            return Files.readString(trustStorePath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read publisher trust store", ex);
        }
    }

    private Path trustStorePath() {
        String explicit = System.getProperty("suite.modules.trust.store.path", "");
        if (!explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        return runtimeRoot().resolve("config").resolve("trust").resolve("publishers.yml").toAbsolutePath().normalize();
    }

    private Path modulesDir() {
        return Paths.get(System.getProperty("suite.modules.dir", runtimeRoot().resolve("modules").toString())).toAbsolutePath().normalize();
    }

    private Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("suite.home", System.getProperty("user.dir")))).toAbsolutePath().normalize();
    }

    private Path resolvePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return runtimeRoot();
        }
        Path path = Paths.get(raw);
        return path.isAbsolute() ? path.toAbsolutePath().normalize() : runtimeRoot().resolve(path).toAbsolutePath().normalize();
    }

    private String idFromPublisher(String publisher, String cert) {
        String source = firstNonBlank(publisher, cert, "publisher").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        source = source.replaceAll("(^-|-$)", "");
        return source.isBlank() ? "publisher" : source;
    }

    private String envRequired(String name) {
        String value = env(name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("environment variable is required: " + name);
        }
        return value;
    }

    private String env(String name) {
        return name == null || name.isBlank() ? "" : System.getenv().getOrDefault(name, "");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String normalizeHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
    }

    private String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\\\"")) + "\"";
    }

    private String unquote(String value) {
        String v = value == null ? "" : value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private record CertificateIdentity(boolean signed, String certificateSha256, String subject, String issuer, String publisherIdentity) {
        private static CertificateIdentity unsigned() {
            return new CertificateIdentity(false, "", "", "", "");
        }
    }
}
