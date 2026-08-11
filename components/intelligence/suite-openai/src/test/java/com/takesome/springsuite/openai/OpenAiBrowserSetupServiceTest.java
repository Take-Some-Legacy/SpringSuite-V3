package com.takesome.springsuite.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class OpenAiBrowserSetupServiceTest {
    private OpenAiBrowserSetupService service;

    @BeforeEach
    void setUp() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.getBrowserSetup().setEnabled(true);
        properties.getBrowserSetup().setLocalOnly(true);
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "8090");
        service = new OpenAiBrowserSetupService(properties, environment, mock(OpenAiAuditService.class));
    }

    @Test
    void trustsSameOriginLoopbackFormAfterTokenRotation() {
        MockHttpServletRequest request = localRequest();
        request.addHeader("Origin", "http://localhost:8090");

        assertThat(service.trustedLocalMutationRequest(request)).isTrue();
    }

    @Test
    void rejectsCrossSiteFormEvenWhenTargetIsLoopback() {
        MockHttpServletRequest request = localRequest();
        request.addHeader("Origin", "https://evil.example");
        request.addHeader("Sec-Fetch-Site", "cross-site");

        assertThat(service.trustedLocalMutationRequest(request)).isFalse();
    }

    @Test
    void acceptsSameOriginFetchMetadataWhenOriginHeaderIsMissing() {
        MockHttpServletRequest request = localRequest();
        request.addHeader("Sec-Fetch-Site", "same-origin");

        assertThat(service.trustedLocalMutationRequest(request)).isTrue();
    }

    private MockHttpServletRequest localRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:8090");
        return request;
    }
}
