package com.takesome.springsuite.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BasicKnowledgeStore {
    public static final String SCHEMA = "com.takesome.springsuite.basic-knowledge.v1";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;
    private final OperatorLogService logService;

    public BasicKnowledgeStore(ObjectMapper objectMapper, OperatorLogService logService) {
        this.objectMapper = objectMapper;
        this.logService = logService;
    }

    public synchronized BasicKnowledgeItem remember(String key, String value, List<String> tags, String source) {
        ArrayList<BasicKnowledgeItem> items = new ArrayList<>(load());
        String normalizedKey = normalizeKey(key);
        BasicKnowledgeItem next;
        int existingIndex = indexOfKey(items, normalizedKey);
        if (existingIndex >= 0) {
            next = items.get(existingIndex).withValue(value, tags, source);
            items.set(existingIndex, next);
        } else {
            next = new BasicKnowledgeItem(newId(normalizedKey), normalizedKey, value, tags, source, Instant.now(), Instant.now());
            items.add(next);
        }
        save(items);
        logService.append(OperatorLogLevel.INFO, "basicKnowledge", "basic knowledge item saved", Map.of(
                "key", next.key(),
                "id", next.id(),
                "path", storagePath().toString()
        ));
        return next;
    }

    public synchronized List<BasicKnowledgeItem> list() {
        return load().stream().sorted(Comparator.comparing(BasicKnowledgeItem::key)).toList();
    }

    public synchronized List<BasicKnowledgeItem> search(String query) {
        return list().stream().filter(item -> item.matches(query)).toList();
    }

    public synchronized BasicKnowledgeItem get(String idOrKey) {
        String lookup = normalizeKey(idOrKey);
        return list().stream()
                .filter(item -> item.id().equalsIgnoreCase(lookup) || item.key().equalsIgnoreCase(lookup))
                .findFirst()
                .orElse(null);
    }

    public Path storagePath() {
        return runtimeRoot().resolve(".springsuite/basic-knowledge.json").normalize();
    }

    public synchronized Map<String, Object> dump() {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("schema", SCHEMA);
        data.put("path", storagePath().toString());
        data.put("count", list().size());
        data.put("items", list());
        return data;
    }

    private List<BasicKnowledgeItem> load() {
        Path path = storagePath();
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            LinkedHashMap<String, Object> data = objectMapper.readValue(path.toFile(), MAP_TYPE);
            Object rawItems = data.get("items");
            if (!(rawItems instanceof List<?> list)) {
                return List.of();
            }
            ArrayList<BasicKnowledgeItem> items = new ArrayList<>();
            for (Object raw : list) {
                BasicKnowledgeItem item = objectMapper.convertValue(raw, BasicKnowledgeItem.class);
                if (!item.key().isBlank()) {
                    items.add(item);
                }
            }
            return List.copyOf(items);
        } catch (Exception ex) {
            logService.append(OperatorLogLevel.WARN, "basicKnowledge", "basic knowledge load failed", Map.of(
                    "path", path.toString(),
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
            return List.of();
        }
    }

    private void save(List<BasicKnowledgeItem> items) {
        Path path = storagePath();
        try {
            Files.createDirectories(path.getParent());
            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("schema", SCHEMA);
            data.put("updatedAt", Instant.now().toString());
            data.put("items", items.stream().sorted(Comparator.comparing(BasicKnowledgeItem::key)).toList());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), data);
        } catch (IOException ex) {
            throw new IllegalStateException("basic knowledge save failed: " + ex.getMessage(), ex);
        }
    }

    private int indexOfKey(List<BasicKnowledgeItem> items, String key) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equalsIgnoreCase(key)) {
                return i;
            }
        }
        return -1;
    }

    private String newId(String key) {
        String base = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "knowledge";
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim();
    }

    private Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("user.dir"))).toAbsolutePath().normalize();
    }
}
