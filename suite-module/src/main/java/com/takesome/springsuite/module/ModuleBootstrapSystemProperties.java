package com.takesome.springsuite.module;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

final class ModuleBootstrapSystemProperties {
    private ModuleBootstrapSystemProperties() {
    }

    static void publish(
            Path runtimeRoot,
            ModuleBootstrapConfig config,
            List<Path> discoveredJars,
            List<Path> loadableJars,
            List<SuiteModuleJarTrustReport> trustReports
    ) {
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
        System.setProperty("suite.modules.trust.trusted.count", Long.toString(trustReports.stream().filter(ModuleJarTrustReporter::isPinnedTrust).count()));
        System.setProperty("suite.modules.trust.reports", trustReports.stream()
                .map(ModuleJarTrustReporter::compactReport)
                .collect(Collectors.joining(";")));
        System.setProperty("suite.modules.trust.store.path", config.trustStorePath().toString());
        System.setProperty("suite.modules.trust.store.loaded", Boolean.toString(config.trustStoreLoaded()));
        System.setProperty("suite.modules.trust.store.created", Boolean.toString(config.trustStoreCreated()));
        System.setProperty("suite.modules.trust.store.records", Integer.toString(config.trustStoreRecordCount()));
        System.setProperty("suite.modules.trust.store.trusted", Integer.toString(config.trustStoreTrustedCount()));
        System.setProperty("suite.modules.trust.store.revoked", Integer.toString(config.trustStoreRevokedCount()));
        System.setProperty("suite.modules.trust.store.expired", Integer.toString(config.trustStoreExpiredCount()));
        System.setProperty("suite.modules.trust.store.message", config.trustStoreMessage());
        System.setProperty("suite.modules.runtime.root", runtimeRoot.toString());
    }
}
