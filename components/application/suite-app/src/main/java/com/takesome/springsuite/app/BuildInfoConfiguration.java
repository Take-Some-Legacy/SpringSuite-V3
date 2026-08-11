package com.takesome.springsuite.app;

import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BuildInfoConfiguration {
    @Bean
    public SuiteBuildInfo suiteBuildInfo() {
        return SuiteBuildInfo.load();
    }

    @Bean
    public BuildProperties buildProperties(SuiteBuildInfo suiteBuildInfo) {
        return suiteBuildInfo.toBuildProperties();
    }
}
