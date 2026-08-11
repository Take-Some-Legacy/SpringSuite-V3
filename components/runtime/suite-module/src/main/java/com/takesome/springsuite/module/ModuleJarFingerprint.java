package com.takesome.springsuite.module;

public record ModuleJarFingerprint(String path, String fileName, long sizeBytes, String jarSha256, boolean signed, String certificateSha256, String certificateSubject, String certificateIssuer, String publisherIdentity) {
}
