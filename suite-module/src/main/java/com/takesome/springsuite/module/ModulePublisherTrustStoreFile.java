package com.takesome.springsuite.module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ModulePublisherTrustStoreFile {
    private final ModuleArtifactPaths paths;

    ModulePublisherTrustStoreFile(ModuleArtifactPaths paths) {
        this.paths = paths;
    }

    List<ModulePublisherRecord> listPublishers() {
        ensureTrustStore();
        return parsePublishers(readTrustStore());
    }

    void upsert(ModulePublisherRecord record) {
        ArrayList<ModulePublisherRecord> records = new ArrayList<>(listPublishers());
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equalsIgnoreCase(record.id())) {
                records.set(i, record);
                writePublishers(records);
                return;
            }
        }
        records.add(record);
        writePublishers(records);
    }

    ModulePublisherRecord revoke(String key) {
        if (key.isBlank()) {
            throw new IllegalArgumentException("id, certificateSha256, or publisherIdentity is required");
        }
        ArrayList<ModulePublisherRecord> records = new ArrayList<>(listPublishers());
        for (int i = 0; i < records.size(); i++) {
            ModulePublisherRecord record = records.get(i);
            if (matches(record, key)) {
                ModulePublisherRecord revoked = new ModulePublisherRecord(
                        record.id(),
                        record.name(),
                        "blocked",
                        true,
                        record.expiresAt(),
                        record.certificateSha256(),
                        record.publisherIdentities()
                );
                records.set(i, revoked);
                writePublishers(records);
                return revoked;
            }
        }
        throw new IllegalArgumentException("publisher record not found: " + key);
    }

    private boolean matches(ModulePublisherRecord record, String key) {
        String n = normalize(key);
        return normalize(record.id()).equals(n)
                || record.certificateSha256().stream().map(this::normalizeHash).anyMatch(n::equals)
                || record.publisherIdentities().stream().map(this::normalize).anyMatch(n::equals);
    }

    private List<ModulePublisherRecord> parsePublishers(String yaml) {
        ArrayList<ModulePublisherRecord> records = new ArrayList<>();
        String id = "";
        String name = "";
        String trust = "untrusted";
        boolean revoked = false;
        String expires = "";
        ArrayList<String> certs = new ArrayList<>();
        ArrayList<String> pubs = new ArrayList<>();
        String currentList = "";
        boolean inPublishers = false;
        boolean currentOpen = false;
        for (String raw : yaml.split("\\R")) {
            String line = raw.split("#", 2)[0];
            if (line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.equals("publishers:")) {
                inPublishers = true;
                continue;
            }
            if (!inPublishers) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                if (currentOpen) {
                    records.add(new ModulePublisherRecord(id, name, trust, revoked, expires, certs, pubs));
                }
                id = name = expires = currentList = "";
                trust = "untrusted";
                revoked = false;
                certs = new ArrayList<>();
                pubs = new ArrayList<>();
                currentOpen = true;
                String item = trimmed.substring(2).trim();
                if (item.contains(":")) {
                    String[] kv = item.split(":", 2);
                    id = unquote(kv[1].trim());
                }
                continue;
            }
            if (!currentOpen) {
                continue;
            }
            if (trimmed.startsWith("-")) {
                String value = unquote(trimmed.substring(1).trim());
                if (currentList.equals("certificate-sha256")) {
                    certs.add(value);
                } else if (currentList.equals("publisher-identities")) {
                    pubs.add(value);
                }
                continue;
            }
            if (!trimmed.contains(":")) {
                continue;
            }
            String[] kv = trimmed.split(":", 2);
            String key = kv[0].trim();
            String value = unquote(kv[1].trim());
            currentList = "";
            switch (key) {
                case "id" -> id = value;
                case "name" -> name = value;
                case "trust-level" -> trust = value;
                case "revoked" -> revoked = value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes");
                case "expires-at" -> expires = value;
                case "certificate-sha256" -> {
                    currentList = key;
                    if (!value.isBlank()) {
                        certs.add(value);
                    }
                }
                case "publisher-identities" -> {
                    currentList = key;
                    if (!value.isBlank()) {
                        pubs.add(value);
                    }
                }
                default -> {
                }
            }
        }
        if (currentOpen) {
            records.add(new ModulePublisherRecord(id, name, trust, revoked, expires, certs, pubs));
        }
        return records;
    }

    private void writePublishers(List<ModulePublisherRecord> records) {
        ensureTrustStore();
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 1\n");
        yaml.append("publishers:\n");
        for (ModulePublisherRecord record : records) {
            yaml.append("  - id: ").append(record.id()).append("\n");
            yaml.append("    name: ").append(quote(record.name())).append("\n");
            yaml.append("    trust-level: ").append(record.trustLevel()).append("\n");
            yaml.append("    revoked: ").append(record.revoked()).append("\n");
            if (!record.expiresAt().isBlank()) {
                yaml.append("    expires-at: ").append(record.expiresAt()).append("\n");
            }
            yaml.append("    certificate-sha256:\n");
            for (String cert : record.certificateSha256()) {
                yaml.append("      - ").append(normalizeHash(cert)).append("\n");
            }
            yaml.append("    publisher-identities:\n");
            for (String publisher : record.publisherIdentities()) {
                yaml.append("      - ").append(quote(publisher)).append("\n");
            }
        }
        try {
            Files.writeString(paths.trustStorePath(), yaml.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write publisher trust store", ex);
        }
    }

    private void ensureTrustStore() {
        SuiteModulePublisherTrustStore.load(paths.runtimeRoot());
    }

    private String readTrustStore() {
        try {
            return Files.readString(paths.trustStorePath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read publisher trust store", ex);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String normalizeHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
    }

    private String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\\\"")) + "\"";
    }

    private String unquote(String value) {
        String v = value == null ? "" : value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
