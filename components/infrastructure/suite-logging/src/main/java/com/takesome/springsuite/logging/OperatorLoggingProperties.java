package com.takesome.springsuite.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.operator.logging")
public class OperatorLoggingProperties {
    private int ringBufferSize = 0;

    public int getRingBufferSize() {
        return ringBufferSize;
    }

    public void setRingBufferSize(int ringBufferSize) {
        this.ringBufferSize = Math.max(0, ringBufferSize);
    }
}
