package com.takesome.springsuite.agent.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class AuthJsonStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public AuthJsonStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeJson(Path path, Map<String, Object> payload) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write auth JSON: " + path, ex);
        }
    }

    public Optional<Map<String, Object>> readJson(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), MAP_TYPE));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public void writeTextSecret(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write secret: " + path, ex);
        }
    }

    public String objectToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    public String quoteJson(String value) {
        return objectToJson(value);
    }
}
