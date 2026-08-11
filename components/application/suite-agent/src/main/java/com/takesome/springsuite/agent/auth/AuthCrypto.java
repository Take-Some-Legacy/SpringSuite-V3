package com.takesome.springsuite.agent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AuthCrypto {
    public String encodedChallenge(String verifier) {
        byte[] digest = sha256Bytes(verifier == null ? "" : verifier);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    public String generateToken(int bytes) {
        byte[] data = new byte[Math.max(16, bytes)];
        new java.security.SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public String fingerprint(String token) {
        return "sha256:" + sha256(token).substring(0, 16);
    }

    public String sha256(String text) {
        return HexFormat.of().formatHex(sha256Bytes(text));
    }

    public boolean constantTimeEquals(String supplied, String expected) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] key = "spring-suite-auth".getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] a = mac.doFinal(supplied.getBytes(StandardCharsets.UTF_8));
            byte[] b = mac.doFinal(expected.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(a, b);
        } catch (Exception ex) {
            return supplied.equals(expected);
        }
    }

    private byte[] sha256Bytes(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
