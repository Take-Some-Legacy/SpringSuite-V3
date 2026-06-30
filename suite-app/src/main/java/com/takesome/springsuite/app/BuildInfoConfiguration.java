package com.takesome.springsuite.app;

import java.time.Instant;
import java.util.Properties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BuildInfoConfiguration {
    @Bean
    public BuildProperties buildProperties() {
        Properties properties = new Properties();
        properties.setProperty("name", "spring-suite");
        properties.setProperty("version", "0.1.0-SNAPSHOT");
        properties.setProperty("time", Instant.now().toString());
        return new BuildProperties(properties);
    }
}
