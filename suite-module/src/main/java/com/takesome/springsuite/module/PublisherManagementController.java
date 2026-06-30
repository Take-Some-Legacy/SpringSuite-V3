package com.takesome.springsuite.module;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublisherManagementController {
    private final SuitePublisherManagementService service;

    public PublisherManagementController(SuitePublisherManagementService service) {
        this.service = service;
    }

    @GetMapping("/api/modules/publishers")
    public SuiteApiResponse<List<ModulePublisherRecord>> list() {
        return SuiteApiResponse.ok(service.listPublishers());
    }

    @PostMapping("/api/modules/publishers/fingerprint")
    public SuiteApiResponse<ModuleJarFingerprint> fingerprint(@RequestBody PathRequest request) {
        return SuiteApiResponse.ok(service.fingerprint(request));
    }

    @PostMapping("/api/modules/publishers/trust-cert")
    public SuiteApiResponse<ModulePublisherRecord> trustCert(@RequestBody PublisherMutationRequest request) {
        return SuiteApiResponse.ok("certificate trusted", service.trustCertificate(request));
    }

    @PostMapping("/api/modules/publishers/trust-publisher")
    public SuiteApiResponse<ModulePublisherRecord> trustPublisher(@RequestBody PublisherMutationRequest request) {
        return SuiteApiResponse.ok("publisher trusted", service.trustPublisher(request));
    }

    @PostMapping("/api/modules/publishers/block-cert")
    public SuiteApiResponse<ModulePublisherRecord> blockCert(@RequestBody PublisherMutationRequest request) {
        return SuiteApiResponse.ok("certificate blocked", service.blockCertificate(request));
    }

    @PostMapping("/api/modules/publishers/revoke")
    public SuiteApiResponse<ModulePublisherRecord> revoke(@RequestBody PublisherMutationRequest request) {
        return SuiteApiResponse.ok("publisher revoked", service.revoke(request));
    }

    @PostMapping("/api/modules/artifacts/build")
    public SuiteApiResponse<ModuleArtifactResult> build(@RequestBody ModuleBuildRequest request) {
        return SuiteApiResponse.ok(service.build(request));
    }

    @PostMapping("/api/modules/artifacts/sign")
    public SuiteApiResponse<ModuleArtifactResult> sign(@RequestBody ModuleSignRequest request) {
        return SuiteApiResponse.ok(service.sign(request));
    }

    @PostMapping("/api/modules/artifacts/deploy")
    public SuiteApiResponse<ModuleArtifactResult> deploy(@RequestBody ModuleDeployRequest request) {
        return SuiteApiResponse.ok(service.deploy(request));
    }
}
