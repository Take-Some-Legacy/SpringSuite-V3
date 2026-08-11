package com.takesome.springsuite.module;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

final class ModuleHashing {
    private ModuleHashing() {
    }

    static String sha256(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return sha256(input);
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME);
        return hex(digest.digest(bytes));
    }

    static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
