package com.takesome.springsuite.desktop;

import java.util.Map;

/**
 * Privacy-filtered form relay port exposed to MCP and command surfaces.
 * Implementations own relay state, validation and expiry.
 */
public interface DesktopFormRelay {
    Map<String, Object> currentRequest();

    Map<String, Object> status(String relayId);

    Map<String, Object> submit(Map<String, Object> arguments);
}
