package com.takesome.springsuite.module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class SuiteModulePublisherTrustStore {
    private static final String TRUST_STORE_PATH = "config/trust/publishers.yml";

    private SuiteModulePublisherTrustStore() {
    }

    static TrustStoreLoadResult load(Path runtimeRoot) {
        Path trustStorePath = runtimeRoot.resolve(TRUST_STORE_PATH).toAbsolutePath().normalize();
        boolean created = false;
        try {
            Files.createDirectories(trustStorePath.getParent());
            if (!Files.exists(trustStorePath)) {
                Files.writeString(trustStorePath, defaultTrustStore(), StandardCharsets.UTF_8);
                created = true;
            }
            String yaml = Files.readString(trustStorePath, StandardCharsets.UTF_8);
            List<PublisherRecord> records = parsePublisherRecords(yaml);
            LinkedHashSet<String> trustedCertificateHashes = new LinkedHashSet<>();
            LinkedHashSet<String> blockedCertificateHashes = new LinkedHashSet<>();
            LinkedHashSet<String> trustedPublishers = new LinkedHashSet<>();
            LinkedHashSet<String> blockedPublishers = new LinkedHashSet<>();
            int trusted = 0;
            int revoked = 0;
            int expired = 0;

            Instant now = Instant.now();
            for (PublisherRecord record : records) {
                boolean recordExpired = record.isExpired(now);
                boolean recordRevoked = record.revoked();
                if (recordRevoked) {
                    revoked++;
                }
                if (recordExpired) {
                    expired++;
                }

                PublisherTrustState trustState = record.trustState();
                if (recordRevoked || recordExpired || trustState == PublisherTrustState.BLOCKED) {
                    blockedCertificateHashes.addAll(record.normalizedCertificateHashes());
                    blockedPublishers.addAll(record.normalizedPublisherIdentities());
                    continue;
                }

                if (trustState == PublisherTrustState.TRUSTED) {
                    trusted++;
                    trustedCertificateHashes.addAll(record.normalizedCertificateHashes());
                    trustedPublishers.addAll(record.normalizedPublisherIdentities());
                }
            }

            return new TrustStoreLoadResult(
                    trustStorePath,
                    true,
                    created,
                    records.size(),
                    trusted,
                    revoked,
                    expired,
                    trustedCertificateHashes,
                    blockedCertificateHashes,
                    trustedPublishers,
                    blockedPublishers,
                    "ok"
            );
        } catch (Exception ex) {
            return new TrustStoreLoadResult(
                    trustStorePath,
                    false,
                    created,
                    0,
                    0,
                    0,
                    0,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            );
        }
    }

    private static String defaultTrustStore() {
        return "# SpringSuite module publisher trust store.\n"
                + "# Owned by suite-module. Read before external /modules JARs are loaded.\n"
                + "# Add trusted or blocked publishers here instead of placing certificate pins in suite-modules.yml.\n"
                + "#\n"
                + "# Example:\n"
                + "# publishers:\n"
                + "#   - id: take-some-dev\n"
                + "#     name: Take Some Development\n"
                + "#     trust-level: trusted\n"
                + "#     revoked: false\n"
                + "#     expires-at: 2027-12-31\n"
                + "#     certificate-sha256:\n"
                + "#       - deadbeef...\n"
                + "#     publisher-identities:\n"
                + "#       - \"C=LV,O=Take Some,CN=Suite Module Publisher\"\n"
                + "#     metadata:\n"
                + "#       owner: SuiteLab\n"
                + "\n"
                + "version: 1\n"
                + "publishers:\n";
    }

    private static List<PublisherRecord> parsePublisherRecords(String yaml) {
        ArrayList<PublisherRecordBuilder> builders = new ArrayList<>();
        PublisherRecordBuilder current = null;
        String currentList = "";
        boolean inPublishers = false;

        for (String rawLine : yaml.split("\\R")) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = countIndent(line);
            String trimmed = line.trim();
            if (indent == 0 && trimmed.equals("publishers:")) {
                inPublishers = true;
                continue;
            }
            if (!inPublishers) {
                continue;
            }

            if (indent <= 1 && !trimmed.startsWith("-")) {
                currentList = "";
                continue;
            }

            if (trimmed.startsWith("- ")) {
                String item = trimmed.substring(2).trim();
                if (!currentList.isBlank() && current != null && !looksLikeKeyValue(item)) {
                    current.addListValue(currentList, stripQuotes(item));
                    continue;
                }

                current = new PublisherRecordBuilder();
                builders.add(current);
                currentList = "";
                if (looksLikeKeyValue(item)) {
                    setKeyValue(current, item);
                }
                continue;
            }

            if (current == null) {
                continue;
            }

            if (looksLikeKeyValue(trimmed)) {
                String key = key(trimmed);
                String value = value(trimmed);
                if (isListKey(key) && value.isBlank()) {
                    currentList = key;
                    continue;
                }
                currentList = "";
                current.setScalar(key, stripQuotes(value));
            } else if (!currentList.isBlank() && trimmed.startsWith("-")) {
                current.addListValue(currentList, stripQuotes(trimmed.substring(1).trim()));
            }
        }

        return builders.stream()
                .map(PublisherRecordBuilder::build)
                .collect(Collectors.toList());
    }

    private static boolean looksLikeKeyValue(String value) {
        int index = value.indexOf(':');
        return index > 0;
    }

    private static void setKeyValue(PublisherRecordBuilder builder, String raw) {
        builder.setScalar(key(raw), stripQuotes(value(raw)));
    }

    private static String key(String raw) {
        return raw.substring(0, raw.indexOf(':')).trim();
    }

    private static String value(String raw) {
        return raw.substring(raw.indexOf(':') + 1).trim();
    }

    private static boolean isListKey(String key) {
        return key.equals("certificate-sha256")
                || key.equals("certificates")
                || key.equals("publisher-identities")
                || key.equals("subjects");
    }

    private static String stripComment(String line) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
            } else if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            } else if (ch == '#' && !singleQuoted && !doubleQuoted) {
                if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
                    return line.substring(0, i).stripTrailing();
                }
            }
        }
        return line.stripTrailing();
    }

    private static int countIndent(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static String stripQuotes(String value) {
        String v = value == null ? "" : value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String normalizeHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
    }

    private static String normalizePublisher(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private enum PublisherTrustState {
        TRUSTED,
        BLOCKED,
        UNTRUSTED
    }

    private record PublisherRecord(
            String id,
            String name,
            PublisherTrustState trustState,
            boolean revoked,
            String expiresAt,
            List<String> certificateSha256,
            List<String> publisherIdentities
    ) {
        boolean isExpired(Instant now) {
            if (expiresAt == null || expiresAt.isBlank()) {
                return false;
            }
            try {
                Instant expires = LocalDate.parse(expiresAt.trim()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                return now.isAfter(expires);
            } catch (DateTimeParseException ignored) {
                return false;
            }
        }

        Set<String> normalizedCertificateHashes() {
            return certificateSha256.stream()
                    .map(SuiteModulePublisherTrustStore::normalizeHash)
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<String> normalizedPublisherIdentities() {
            return publisherIdentities.stream()
                    .map(SuiteModulePublisherTrustStore::normalizePublisher)
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private static final class PublisherRecordBuilder {
        private String id = "";
        private String name = "";
        private String trustLevel = "untrusted";
        private boolean revoked;
        private String expiresAt = "";
        private final ArrayList<String> certificateSha256 = new ArrayList<>();
        private final ArrayList<String> publisherIdentities = new ArrayList<>();

        void setScalar(String key, String value) {
            switch (key) {
                case "id" -> id = value;
                case "name" -> name = value;
                case "trust-level", "trustLevel", "state" -> trustLevel = value;
                case "revoked" -> revoked = value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes") || value.equals("1");
                case "expires-at", "expiresAt", "expires" -> expiresAt = value;
                case "certificate-sha256", "certificates" -> addCsv(certificateSha256, value);
                case "publisher-identity", "publisherIdentity", "subject" -> addCsv(publisherIdentities, value);
                default -> {
                    // Metadata and unknown fields are intentionally ignored by bootstrap parser.
                }
            }
        }

        void addListValue(String key, String value) {
            if (key.equals("certificate-sha256") || key.equals("certificates")) {
                addCsv(certificateSha256, value);
            } else if (key.equals("publisher-identities") || key.equals("subjects")) {
                addCsv(publisherIdentities, value);
            }
        }

        PublisherRecord build() {
            return new PublisherRecord(id, name, parseTrustState(trustLevel), revoked, expiresAt, List.copyOf(certificateSha256), List.copyOf(publisherIdentities));
        }

        private static PublisherTrustState parseTrustState(String value) {
            String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return switch (raw) {
                case "trusted", "trust", "allow", "allowed" -> PublisherTrustState.TRUSTED;
                case "blocked", "block", "deny", "denied", "revoked" -> PublisherTrustState.BLOCKED;
                default -> PublisherTrustState.UNTRUSTED;
            };
        }

        private static void addCsv(List<String> target, String raw) {
            if (raw == null || raw.isBlank()) {
                return;
            }
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    target.add(stripQuotes(part.trim()));
                }
            }
        }
    }

    record TrustStoreLoadResult(
            Path path,
            boolean loaded,
            boolean created,
            int recordCount,
            int trustedCount,
            int revokedCount,
            int expiredCount,
            Set<String> trustedCertificateHashes,
            Set<String> blockedCertificateHashes,
            Set<String> trustedPublishers,
            Set<String> blockedPublishers,
            String message
    ) {
        TrustStoreLoadResult {
            trustedCertificateHashes = trustedCertificateHashes == null ? Set.of() : Set.copyOf(trustedCertificateHashes);
            blockedCertificateHashes = blockedCertificateHashes == null ? Set.of() : Set.copyOf(blockedCertificateHashes);
            trustedPublishers = trustedPublishers == null ? Set.of() : Set.copyOf(trustedPublishers);
            blockedPublishers = blockedPublishers == null ? Set.of() : Set.copyOf(blockedPublishers);
            message = message == null ? "" : message;
        }
    }
}
