package com.takesome.springsuite.workspace;

import java.security.MessageDigest;
import java.util.HexFormat;

final class WorkspaceDigest {
    private WorkspaceDigest() {
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
