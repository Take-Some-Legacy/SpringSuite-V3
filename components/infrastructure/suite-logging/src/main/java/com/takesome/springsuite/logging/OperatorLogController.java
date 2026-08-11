package com.takesome.springsuite.logging;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class OperatorLogController {
    private final OperatorLogService logService;

    public OperatorLogController(OperatorLogService logService) {
        this.logService = logService;
    }

    @GetMapping("/api/operator/logs")
    public SuiteApiResponse<List<OperatorLogEntry>> recent(@RequestParam(defaultValue = "200") int limit) {
        return SuiteApiResponse.ok(logService.recent(limit));
    }

    @PostMapping("/api/operator/logs")
    public SuiteApiResponse<OperatorLogEntry> append(@RequestBody OperatorLogRequest request) {
        OperatorLogEntry entry = logService.append(
                request.level(),
                request.source(),
                request.message(),
                request.metadata()
        );
        return SuiteApiResponse.ok("operator log appended", entry);
    }

    @GetMapping(value = "/api/operator/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return logService.stream();
    }
}
