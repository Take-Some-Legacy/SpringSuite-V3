package com.takesome.springsuite.app;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import com.takesome.springsuite.core.status.SuiteComponentStatus;
import com.takesome.springsuite.core.status.SuiteSystemStatus;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemStatusController {
    private final Instant startedAt = Instant.now();
    private final SuiteBuildInfo suiteBuildInfo;

    public SystemStatusController(SuiteBuildInfo suiteBuildInfo) {
        this.suiteBuildInfo = suiteBuildInfo;
    }

    @GetMapping("/api/system/status")
    public SuiteApiResponse<SuiteSystemStatus> status() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("java", Runtime.version().toString());
        components.put("pid", ManagementFactory.getRuntimeMXBean().getPid());
        components.put("name", suiteBuildInfo.name());
        components.put("version", suiteBuildInfo.version());
        components.put("build", suiteBuildInfo.build());
        components.put("buildTime", suiteBuildInfo.time());
        components.put("gitCommit", suiteBuildInfo.commit());
        components.put("gitBranch", suiteBuildInfo.branch());
        components.put("gitDirty", suiteBuildInfo.dirty());
        components.put("projectRoot", System.getProperty("suite.project.root", ""));
        components.put("workingDirectory", System.getProperty("suite.working.directory", ""));
        components.put("launchDirectory", System.getProperty("suite.launch.dir", ""));
        components.put("userDir", System.getProperty("user.dir", ""));
        components.put("modulesEnabled", System.getProperty("suite.modules.enabled", ""));
        components.put("modulesDir", System.getProperty("suite.modules.dir", ""));
        components.put("modulesRecursive", System.getProperty("suite.modules.recursive", ""));
        components.put("modulesCount", System.getProperty("suite.modules.count", "0"));
        components.put("modulesDiscoveredCount", System.getProperty("suite.modules.discovered.count", "0"));
        components.put("modulesBlockedCount", System.getProperty("suite.modules.blocked.count", "0"));
        components.put("modulesJars", System.getProperty("suite.modules.jars", ""));
        components.put("modulesTrustMode", System.getProperty("suite.modules.trust.mode", ""));
        components.put("modulesTrustRequireSignature", System.getProperty("suite.modules.trust.require.signature", ""));
        components.put("modulesTrustAllowUnsignedLocal", System.getProperty("suite.modules.trust.allow.unsigned.local", ""));
        components.put("modulesTrustAllowUnpinnedSigned", System.getProperty("suite.modules.trust.allow.unpinned.signed", ""));
        components.put("modulesTrustTrustedCount", System.getProperty("suite.modules.trust.trusted.count", "0"));
        components.put("modulesTrustReports", System.getProperty("suite.modules.trust.reports", ""));
        components.put("modulesTrustStorePath", System.getProperty("suite.modules.trust.store.path", ""));
        components.put("modulesTrustStoreLoaded", System.getProperty("suite.modules.trust.store.loaded", ""));
        components.put("modulesTrustStoreCreated", System.getProperty("suite.modules.trust.store.created", ""));
        components.put("modulesTrustStoreRecords", System.getProperty("suite.modules.trust.store.records", "0"));
        components.put("modulesTrustStoreTrusted", System.getProperty("suite.modules.trust.store.trusted", "0"));
        components.put("modulesTrustStoreRevoked", System.getProperty("suite.modules.trust.store.revoked", "0"));
        components.put("modulesTrustStoreExpired", System.getProperty("suite.modules.trust.store.expired", "0"));
        components.put("modulesTrustStoreMessage", System.getProperty("suite.modules.trust.store.message", ""));
        components.put("configPath", System.getProperty("suite.config.path", ""));
        components.put("configDir", System.getProperty("suite.config.dir", ""));
        components.put("configFiles", System.getProperty("suite.config.files", ""));
        components.put("configModuleCount", System.getProperty("suite.config.module.count", "0"));
        components.put("logsPath", System.getProperty("suite.logs.path", ""));
        components.put("configCreated", Boolean.getBoolean("suite.config.created"));
        components.put("configSupplemented", Boolean.getBoolean("suite.config.supplemented"));
        return SuiteApiResponse.ok(new SuiteSystemStatus(
                "spring-suite",
                SuiteComponentStatus.READY,
                startedAt,
                components
        ));
    }
}
