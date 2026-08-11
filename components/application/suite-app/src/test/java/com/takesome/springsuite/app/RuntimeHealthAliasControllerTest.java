package com.takesome.springsuite.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeHealthAliasControllerTest {
    @Test
    void reportsUpForLegacyRuntimePortProbe() {
        Map<String, Object> health = new RuntimeHealthAliasController().health();
        assertThat(health).containsEntry("status", "UP");
    }
}
