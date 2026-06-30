package com.takesome.springsuite.module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class SuitePublisherManagementService {
    private final ModuleArtifactPaths paths = new ModuleArtifactPaths();
    private final ModulePublisherTrustStoreFile trustStore = new ModulePublisherTrustStoreFile(paths);
    private final ModuleJarFingerprintReader fingerprintReader = new ModuleJarFingerprintReader();
    private final ModuleArtifactProcessRunner processRunner = new ModuleArtifactProcessRunner();

    public List<ModulePublisherRecord> listPublishers() {
        return trustStore.listPublishers();
    }

    public ModuleJarFingerprint fingerprint(PathRequest request) {
        return fingerprintReader.fingerprint(paths.resolvePath(request.path()));
    }

    public ModulePublisherRecord trustCertificate(PublisherMutationRequest request) {
        ModuleJarFingerprint fingerprint = fingerprintIfPresent(request.jarPath());
        String cert = paths.firstNonBlank(request.certificateSha256(), fingerprint == null ? "" : fingerprint.certificateSha256());
        String publisher = paths.firstNonBlank(request.publisherIdentity(), fingerprint == null ? "" : fingerprint.publisherIdentity());
        if (cert.isBlank()) {
            throw new IllegalArgumentException("certificateSha256 or signed jarPath is required");
        }
        ModulePublisherRecord record = new ModulePublisherRecord(
                paths.firstNonBlank(request.id(), paths.idFromPublisher(publisher, cert)),
                paths.firstNonBlank(request.name(), publisher.isBlank() ? "Trusted Certificate" : publisher),
                "trusted",
                false,
                paths.firstNonBlank(request.expiresAt(), "2099-12-31"),
                List.of(cert),
                publisher.isBlank() ? List.of() : List.of(publisher)
        );
        trustStore.upsert(record);
        return record;
    }

    public ModulePublisherRecord trustPublisher(PublisherMutationRequest request) {
        ModuleJarFingerprint fingerprint = fingerprintIfPresent(request.jarPath());
        String publisher = paths.firstNonBlank(request.publisherIdentity(), fingerprint == null ? "" : fingerprint.publisherIdentity());
        String cert = paths.firstNonBlank(request.certificateSha256(), fingerprint == null ? "" : fingerprint.certificateSha256());
        if (publisher.isBlank()) {
            throw new IllegalArgumentException("publisherIdentity or signed jarPath is required");
        }
        ModulePublisherRecord record = new ModulePublisherRecord(
                paths.firstNonBlank(request.id(), paths.idFromPublisher(publisher, cert)),
                paths.firstNonBlank(request.name(), publisher),
                "trusted",
                false,
                paths.firstNonBlank(request.expiresAt(), "2099-12-31"),
                cert.isBlank() ? List.of() : List.of(cert),
                List.of(publisher)
        );
        trustStore.upsert(record);
        return record;
    }

    public ModulePublisherRecord blockCertificate(PublisherMutationRequest request) {
        ModuleJarFingerprint fingerprint = fingerprintIfPresent(request.jarPath());
        String cert = paths.firstNonBlank(request.certificateSha256(), fingerprint == null ? "" : fingerprint.certificateSha256());
        String publisher = paths.firstNonBlank(request.publisherIdentity(), fingerprint == null ? "" : fingerprint.publisherIdentity());
        if (cert.isBlank()) {
            throw new IllegalArgumentException("certificateSha256 or signed jarPath is required");
        }
        ModulePublisherRecord record = new ModulePublisherRecord(
                paths.firstNonBlank(request.id(), paths.idFromPublisher(publisher, cert)),
                paths.firstNonBlank(request.name(), publisher.isBlank() ? "Blocked Certificate" : publisher),
                "blocked",
                true,
                paths.firstNonBlank(request.expiresAt(), ""),
                List.of(cert),
                publisher.isBlank() ? List.of() : List.of(publisher)
        );
        trustStore.upsert(record);
        return record;
    }

    public ModulePublisherRecord revoke(PublisherMutationRequest request) {
        String key = paths.firstNonBlank(request.id(), request.certificateSha256(), request.publisherIdentity());
        return trustStore.revoke(key);
    }

    public ModuleArtifactResult deploy(ModuleDeployRequest request) {
        Path source = paths.resolvePath(request.jarPath());
        if (!Files.isRegularFile(source)) {
            return new ModuleArtifactResult(false, "source jar not found", source.toString(), List.of(), null, "", "");
        }
        Path modulesDir = paths.modulesDir();
        try {
            Files.createDirectories(modulesDir);
            String targetName = paths.firstNonBlank(request.targetFileName(), source.getFileName().toString());
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
        Path source = paths.resolvePath(request.jarPath());
        Path output = paths.resolvePath(paths.firstNonBlank(request.outputPath(), request.jarPath()));
        try {
            if (!source.equals(output)) {
                Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING);
            }
            String storeToken = paths.envRequired(request.storePassEnv());
            String keyToken = paths.firstNonBlank(paths.env(request.keyPassEnv()), storeToken);
            ArrayList<String> command = new ArrayList<>(List.of(
                    "jarsigner",
                    "-keystore", paths.resolvePath(request.keystorePath()).toString(),
                    "-storepass", storeToken,
                    "-keypass", keyToken,
                    output.toString(),
                    request.alias()
            ));
            return processRunner.run(command, output.getParent(), 120, output.toString(), true);
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
        return processRunner.run(new ArrayList<>(request.command()), paths.resolvePath(request.cwd()), Math.min(Math.max(1, request.timeoutSeconds()), 900), "", false);
    }

    private ModuleJarFingerprint fingerprintIfPresent(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return fingerprintReader.fingerprint(paths.resolvePath(path));
    }
}
