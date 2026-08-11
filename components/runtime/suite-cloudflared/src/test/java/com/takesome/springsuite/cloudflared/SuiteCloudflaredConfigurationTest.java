package com.takesome.springsuite.cloudflared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

class SuiteCloudflaredConfigurationTest {
    @Test
    void processExecutorIsNotManagedAsSmartLifecycle() throws Exception {
        ExecutorService executor = new SuiteCloudflaredConfiguration().suiteProcessTaskExecutor();
        try {
            assertThat(executor).isNotInstanceOf(SmartLifecycle.class);

            CountDownLatch executed = new CountDownLatch(1);
            executor.execute(executed::countDown);

            assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
