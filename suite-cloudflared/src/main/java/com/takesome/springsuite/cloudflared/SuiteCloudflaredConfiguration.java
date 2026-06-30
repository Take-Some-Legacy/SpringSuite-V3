package com.takesome.springsuite.cloudflared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class SuiteCloudflaredConfiguration {
    @Bean(name = "suiteProcessTaskExecutor")
    public TaskExecutor suiteProcessTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("suite-process-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(64);
        executor.initialize();
        return executor;
    }
}
