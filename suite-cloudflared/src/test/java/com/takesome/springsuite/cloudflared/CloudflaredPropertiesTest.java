package com.takesome.springsuite.cloudflared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CloudflaredPropertiesTest {
    @Test
    void defaultsEnableNamedTunnelAutostart() {
        CloudflaredProperties properties = new CloudflaredProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isAutoStart()).isTrue();
        assertThat(properties.getExecutable()).isEqualTo("cloudflared");
        assertThat(properties.getTargetUrl()).isEqualTo("http://localhost:8090");
        assertThat(properties.getTunnelName()).isEqualTo("spring-suite-test");
        assertThat(properties.getHostname()).isEqualTo("testspring.kaylas-systems.ru");
        assertThat(properties.getExtraArgs()).containsExactly("--no-autoupdate");
        assertThat(properties.getUserProfile()).isEmpty();
        assertThat(properties.getConfigPath()).isEmpty();
        assertThat(properties.getCredentialsFile()).isEmpty();
    }

    @Test
    void explicitConfigurationCanStillDisableAutostart() {
        CloudflaredProperties properties = new CloudflaredProperties();
        properties.setEnabled(false);
        properties.setAutoStart(false);
        properties.setExtraArgs(List.of());

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isAutoStart()).isFalse();
        assertThat(properties.getExtraArgs()).isEmpty();
    }
}
