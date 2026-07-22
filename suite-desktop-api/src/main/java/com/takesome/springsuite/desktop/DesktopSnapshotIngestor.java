package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshotResult;
import java.util.Map;

@FunctionalInterface
public interface DesktopSnapshotIngestor {
    DesktopSnapshotResult ingest(Map<String, Object> body);
}
