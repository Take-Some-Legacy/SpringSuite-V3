package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;

@FunctionalInterface
public interface DesktopSnapshotConsumer {
    void acceptSnapshot(DesktopSnapshot snapshot);
}
