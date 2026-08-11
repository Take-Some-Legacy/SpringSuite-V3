package com.takesome.springsuite.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record BasicKnowledgeItem(
        String id,
        String key,
        String value,
        List<String> tags,
        String source,
        Instant createdAt,
        Instant updatedAt
) {
    public BasicKnowledgeItem {
        id = normalize(id);
        key = normalize(key);
        value = value == null ? "" : value.trim();
        tags = tags == null ? List.of() : List.copyOf(tags.stream()
                .map(BasicKnowledgeItem::normalize)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList());
        source = normalize(source);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public BasicKnowledgeItem withValue(String nextValue, List<String> nextTags, String nextSource) {
        ArrayList<String> mergedTags = new ArrayList<>(tags);
        if (nextTags != null) {
            for (String tag : nextTags) {
                String normalized = normalize(tag);
                if (!normalized.isBlank() && !mergedTags.contains(normalized)) {
                    mergedTags.add(normalized);
                }
            }
        }
        return new BasicKnowledgeItem(id, key, nextValue, mergedTags, nextSource, createdAt, Instant.now());
    }

    public boolean matches(String query) {
        String q = normalize(query).toLowerCase();
        if (q.isBlank()) {
            return true;
        }
        return id.toLowerCase().contains(q)
                || key.toLowerCase().contains(q)
                || value.toLowerCase().contains(q)
                || source.toLowerCase().contains(q)
                || tags.stream().anyMatch(tag -> tag.toLowerCase().contains(q));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
