package com.takesome.springsuite.cloudflared;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CloudflaredTunnelController {
    private final CloudflaredTunnelService tunnelService;

    public CloudflaredTunnelController(CloudflaredTunnelService tunnelService) {
        this.tunnelService = tunnelService;
    }

    @GetMapping("/api/tunnel/cloudflared/status")
    public SuiteApiResponse<CloudflaredTunnelStatus> status() {
        return SuiteApiResponse.ok(tunnelService.status());
    }

    @PostMapping("/api/tunnel/cloudflared/start")
    public SuiteApiResponse<CloudflaredTunnelStatus> start() {
        return SuiteApiResponse.ok("cloudflared start requested", tunnelService.start());
    }

    @PostMapping("/api/tunnel/cloudflared/stop")
    public SuiteApiResponse<CloudflaredTunnelStatus> stop() {
        return SuiteApiResponse.ok("cloudflared stop requested", tunnelService.stop());
    }

    @PostMapping("/api/tunnel/cloudflared/restart")
    public SuiteApiResponse<CloudflaredTunnelStatus> restart() {
        return SuiteApiResponse.ok("cloudflared restart requested", tunnelService.restart());
    }

    @GetMapping("/api/tunnel/cloudflared/logs")
    public SuiteApiResponse<List<String>> logs(@RequestParam(defaultValue = "200") int limit) {
        return SuiteApiResponse.ok(tunnelService.recentLogs(limit));
    }
}
