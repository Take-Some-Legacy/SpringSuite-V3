package com.takesome.springsuite.desktop;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomIngestResult;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomSnapshotRequest;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BrowserDomController {
    private static final List<String> PROXY_HEADERS = List.of(
            "Forwarded",
            "X-Forwarded-For",
            "X-Real-IP",
            "CF-Connecting-IP",
            "True-Client-IP",
            "CF-Ray"
    );

    private final BrowserDomService browserDomService;

    public BrowserDomController(BrowserDomService browserDomService) {
        this.browserDomService = browserDomService;
    }

    @GetMapping(BrowserDomProperties.STATUS_ENDPOINT)
    public ResponseEntity<SuiteApiResponse<BrowserDomStatus>> status(
            @RequestHeader(value = "X-SpringSuite-Browser-Token", required = false) String token,
            HttpServletRequest servletRequest
    ) {
        if (!isDirectLoopbackRequest(servletRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(SuiteApiResponse.failed(
                    "browser_dom_loopback_required",
                    "Browser DOM bridge accepts direct loopback requests only.",
                    null
            ));
        }
        if (!browserDomService.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(SuiteApiResponse.failed(
                    "browser_dom_unauthorized",
                    "Browser DOM token is missing or invalid.",
                    null
            ));
        }
        return ResponseEntity.ok(SuiteApiResponse.ok(browserDomService.status()));
    }

    @PostMapping(BrowserDomProperties.SNAPSHOT_ENDPOINT)
    public ResponseEntity<SuiteApiResponse<BrowserDomIngestResult>> snapshot(
            @RequestBody(required = false) BrowserDomSnapshotRequest request,
            @RequestHeader(value = "X-SpringSuite-Browser-Token", required = false) String token,
            @RequestHeader(value = "Origin", required = false) String origin,
            HttpServletRequest servletRequest
    ) {
        if (!isDirectLoopbackRequest(servletRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(SuiteApiResponse.failed(
                    "browser_dom_loopback_required",
                    "Browser DOM bridge accepts direct loopback requests only.",
                    null
            ));
        }

        BrowserDomIngestResult result = browserDomService.ingest(request, token, origin);
        SuiteApiResponse<BrowserDomIngestResult> body = result.ok()
                ? SuiteApiResponse.ok(result.message(), result)
                : SuiteApiResponse.failed(result.code(), result.message(), result);
        return ResponseEntity.status(httpStatus(result)).body(body);
    }

    private HttpStatus httpStatus(BrowserDomIngestResult result) {
        if (result.ok()) {
            return HttpStatus.OK;
        }
        return switch (result.code()) {
            case "browser_dom_unauthorized" -> HttpStatus.UNAUTHORIZED;
            case "browser_dom_disabled", "browser_dom_token_unconfigured" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "browser_dom_payload_missing", "browser_dom_url_invalid", "browser_dom_timestamp_invalid" -> HttpStatus.BAD_REQUEST;
            case "browser_dom_form_limit" -> HttpStatus.PAYLOAD_TOO_LARGE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private boolean isDirectLoopbackRequest(HttpServletRequest request) {
        if (request == null || !isLoopbackAddress(request.getRemoteAddr())) {
            return false;
        }
        for (String header : PROXY_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean isLoopbackAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(value.trim()).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
