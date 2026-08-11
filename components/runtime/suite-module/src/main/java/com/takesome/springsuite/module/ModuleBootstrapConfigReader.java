package com.takesome.springsuite.module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;

final class ModuleBootstrapConfigReader {
    private static final String MODULE_CONFIG_FILE = "suite-modules.yml";

    private ModuleBootstrapConfigReader() {
    }

    static ModuleBootstrapConfig read(Path runtimeRoot) {
        Path configPath = runtimeRoot.resolve("config").resolve(MODULE_CONFIG_FILE).toAbsolutePath().normalize();
        boolean enabled = ModuleTrustPolicyValues.readBoolean(System.getProperty("suite.modules.enabled"), ModuleBootstrapPaths.env("SPRING_SUITE_MODULES_ENABLED"), true);
        boolean recursive = ModuleTrustPolicyValues.readBoolean(System.getProperty("suite.modules.recursive"), ModuleBootstrapPaths.env("SPRING_SUITE_MODULES_RECURSIVE"), false);
        String dir = ModuleBootstrapPaths.firstNonBlank(System.getProperty("suite.modules.dir"), ModuleBootstrapPaths.env("SPRING_SUITE_MODULES_DIR"));
        SuiteModuleTrustMode trustMode = ModuleTrustPolicyValues.readTrustMode(System.getProperty("suite.modules.trust.mode"), ModuleBootstrapPaths.env("SPRING_SUITE_MODULES_TRUST_MODE"), SuiteModuleTrustMode.WARN);
        boolean allowUnsignedLocal = ModuleTrustPolicyValues.readBoolean(System.getProperty("suite.modules.trust.allowUnsignedLocal"), ModuleBootstrapPaths.env("SPRING_SUITE_MODULES_TRUST_ALLOW_UNSIGNED_LOCAL"), true);
        boolean requireSignature = ModuleTrustPolicyValues.readBoolean(System.getProperty("suite.modules.trust.requireSignature"), ModuleBootstrapPaths.env("SPRING_SUITE_MODULES_TRUST_REQUIRE_SIGNATURE"), false);
        boolean allowUnpinnedSigned = ModuleTrustPolicyValues.readBoolean(System.getProperty("suite.modules.trust.allowUnpinnedSigned"), ModuleBootstrapPaths.env("SPRING_SUITE_MODULES_TRUST_ALLOW_UNPINNED_SIGNED"), false);
        LinkedHashSet<String> trustedHashes = new LinkedHashSet<>();
        LinkedHashSet<String> blockedHashes = new LinkedHashSet<>();
        LinkedHashSet<String> trustedCertificateHashes = new LinkedHashSet<>();
        LinkedHashSet<String> blockedCertificateHashes = new LinkedHashSet<>();
        LinkedHashSet<String> trustedPublishers = new LinkedHashSet<>();
        LinkedHashSet<String> blockedPublishers = new LinkedHashSet<>();
        ModuleTrustPolicyValues.addCsv(trustedHashes, System.getProperty("suite.modules.trust.trustedSha256"));
        ModuleTrustPolicyValues.addCsv(blockedHashes, System.getProperty("suite.modules.trust.blockedSha256"));
        ModuleTrustPolicyValues.addCsv(trustedCertificateHashes, System.getProperty("suite.modules.trust.trustedCertificateSha256"));
        ModuleTrustPolicyValues.addCsv(blockedCertificateHashes, System.getProperty("suite.modules.trust.blockedCertificateSha256"));
        ModuleTrustPolicyValues.addCsv(trustedPublishers, System.getProperty("suite.modules.trust.trustedPublishers"));
        ModuleTrustPolicyValues.addCsv(blockedPublishers, System.getProperty("suite.modules.trust.blockedPublishers"));

        if (Files.isRegularFile(configPath)) {
            try {
                String yaml = Files.readString(configPath, StandardCharsets.UTF_8);
                enabled = ModuleTrustPolicyValues.readBoolean(ModuleBootstrapYaml.findScalar(yaml, "suite.modules.enabled"), Boolean.toString(enabled), enabled);
                recursive = ModuleTrustPolicyValues.readBoolean(ModuleBootstrapYaml.findScalar(yaml, "suite.modules.recursive"), Boolean.toString(recursive), recursive);
                dir = ModuleBootstrapPaths.firstNonBlank(dir, ModuleBootstrapYaml.findScalar(yaml, "suite.modules.directory"), ModuleBootstrapYaml.findScalar(yaml, "suite.modules.dir"));
                trustMode = ModuleTrustPolicyValues.readTrustMode(ModuleBootstrapYaml.findScalar(yaml, "suite.modules.trust.mode"), trustMode.name(), trustMode);
                allowUnsignedLocal = ModuleTrustPolicyValues.readBoolean(ModuleBootstrapYaml.findScalar(yaml, "suite.modules.trust.allow-unsigned-local"), Boolean.toString(allowUnsignedLocal), allowUnsignedLocal);
                requireSignature = ModuleTrustPolicyValues.readBoolean(ModuleBootstrapYaml.findScalar(yaml, "suite.modules.trust.require-signature"), Boolean.toString(requireSignature), requireSignature);
                allowUnpinnedSigned = ModuleTrustPolicyValues.readBoolean(ModuleBootstrapYaml.findScalar(yaml, "suite.modules.trust.allow-unpinned-signed"), Boolean.toString(allowUnpinnedSigned), allowUnpinnedSigned);
                trustedHashes.addAll(ModuleBootstrapYaml.findList(yaml, "suite.modules.trust.trusted-sha256"));
                blockedHashes.addAll(ModuleBootstrapYaml.findList(yaml, "suite.modules.trust.blocked-sha256"));
                trustedCertificateHashes.addAll(ModuleBootstrapYaml.findList(yaml, "suite.modules.trust.trusted-certificate-sha256"));
                blockedCertificateHashes.addAll(ModuleBootstrapYaml.findList(yaml, "suite.modules.trust.blocked-certificate-sha256"));
                trustedPublishers.addAll(ModuleBootstrapYaml.findList(yaml, "suite.modules.trust.trusted-publishers"));
                blockedPublishers.addAll(ModuleBootstrapYaml.findList(yaml, "suite.modules.trust.blocked-publishers"));
            } catch (IOException ignored) {
                // Keep pre-bootstrap defaults. Full config bootstrap will handle file creation/supplement later.
            }
        }

        SuiteModulePublisherTrustStore.TrustStoreLoadResult trustStore = SuiteModulePublisherTrustStore.load(runtimeRoot);
        trustedCertificateHashes.addAll(trustStore.trustedCertificateHashes());
        blockedCertificateHashes.addAll(trustStore.blockedCertificateHashes());
        trustedPublishers.addAll(trustStore.trustedPublishers());
        blockedPublishers.addAll(trustStore.blockedPublishers());

        Path modulesDir = ModuleBootstrapPaths.resolveRuntimePath(runtimeRoot, ModuleBootstrapPaths.firstNonBlank(dir, "modules"));
        return new ModuleBootstrapConfig(
                enabled,
                recursive,
                modulesDir,
                trustMode,
                allowUnsignedLocal,
                requireSignature,
                allowUnpinnedSigned,
                ModuleTrustPolicyValues.normalizeHashes(trustedHashes),
                ModuleTrustPolicyValues.normalizeHashes(blockedHashes),
                ModuleTrustPolicyValues.normalizeHashes(trustedCertificateHashes),
                ModuleTrustPolicyValues.normalizeHashes(blockedCertificateHashes),
                ModuleTrustPolicyValues.normalizePublishers(trustedPublishers),
                ModuleTrustPolicyValues.normalizePublishers(blockedPublishers),
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
}
