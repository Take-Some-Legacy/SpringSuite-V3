package com.takesome.springsuite.module;

public record PublisherMutationRequest(String id, String name, String certificateSha256, String publisherIdentity, String jarPath, String expiresAt, Boolean revoked) {
}
