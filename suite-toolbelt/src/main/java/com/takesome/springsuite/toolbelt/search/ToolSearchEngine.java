package com.takesome.springsuite.toolbelt.search;

import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.ToolIndexEntry;
import com.takesome.springsuite.toolbelt.support.ToolDescriptorValues;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ToolSearchEngine {
    public List<ToolIndexEntry> index(List<ToolDescriptor> tools) {
        return tools.stream()
                .map(this::indexEntry)
                .sorted(Comparator.comparing(ToolIndexEntry::id))
                .toList();
    }

    public List<ToolDescriptor> search(String query, int limit, String source, String kind, Boolean available, String tag, List<ToolDescriptor> tools) {
        int safeLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
        List<String> terms = tokenize(query == null ? "" : query);
        String sourceFilter = ToolDescriptorValues.normalize(source);
        String kindFilter = ToolDescriptorValues.normalize(kind);
        String tagFilter = ToolDescriptorValues.normalize(tag);
        return tools.stream()
                .filter(tool -> sourceFilter.isBlank() || ToolDescriptorValues.normalize(tool.source()).equals(sourceFilter))
                .filter(tool -> kindFilter.isBlank() || ToolDescriptorValues.normalize(tool.kind()).equals(kindFilter))
                .filter(tool -> available == null || tool.available() == available)
                .filter(tool -> tagFilter.isBlank() || hasTag(tool, tagFilter))
                .map(tool -> new ScoredTool(tool, score(tool, terms)))
                .filter(scored -> scored.score() >= 0)
                .sorted(Comparator.comparingInt(ScoredTool::score).reversed()
                        .thenComparing(scored -> scored.tool().id()))
                .limit(safeLimit)
                .map(ScoredTool::tool)
                .toList();
    }

    public boolean matchesIdentity(ToolDescriptor tool, String normalized) {
        return ToolDescriptorValues.normalize(tool.id()).equals(normalized)
                || ToolDescriptorValues.normalize(tool.name()).equals(normalized)
                || ToolDescriptorValues.normalize(tool.title()).equals(normalized)
                || ToolDescriptorValues.normalize(publicToolName(tool.id())).equals(normalized);
    }

    public static String publicToolName(String descriptorId) {
        String raw = descriptorId == null ? "" : descriptorId.trim();
        String text = raw.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
        text = text.replaceAll("^_+|_+$", "");
        if (text.isBlank() || !Character.isAlphabetic(text.charAt(0))) {
            text = "tool" + (text.isBlank() ? "" : "_" + text);
        }
        if (!text.startsWith("tool_")) {
            text = "tool_" + text;
        }
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    private ToolIndexEntry indexEntry(ToolDescriptor tool) {
        List<String> terms = tokenize(searchableText(tool)).stream()
                .distinct()
                .toList();
        return new ToolIndexEntry(
                tool.id(),
                publicToolName(tool.id()),
                tool.name(),
                tool.title(),
                tool.source(),
                tool.kind(),
                tool.descriptorPath(),
                tool.available(),
                terms
        );
    }

    private int score(ToolDescriptor tool, List<String> terms) {
        if (terms.isEmpty()) {
            return 1;
        }
        String id = ToolDescriptorValues.normalize(tool.id());
        String publicName = ToolDescriptorValues.normalize(publicToolName(tool.id()));
        String name = ToolDescriptorValues.normalize(tool.name());
        String title = ToolDescriptorValues.normalize(tool.title());
        String description = ToolDescriptorValues.normalize(tool.description());
        String searchable = ToolDescriptorValues.normalize(searchableText(tool));
        int score = 0;
        for (String term : terms) {
            if (!searchable.contains(term)) {
                return -1;
            }
            if (id.equals(term) || publicName.equals(term)) {
                score += 1000;
            } else if (name.equals(term) || title.equals(term)) {
                score += 600;
            } else if (id.contains(term) || publicName.contains(term)) {
                score += 250;
            } else if (name.contains(term) || title.contains(term)) {
                score += 180;
            } else if (description.contains(term)) {
                score += 80;
            } else {
                score += 20;
            }
        }
        return score;
    }

    private String searchableText(ToolDescriptor tool) {
        StringBuilder builder = new StringBuilder(1024);
        append(builder, tool.id());
        append(builder, publicToolName(tool.id()));
        append(builder, tool.name());
        append(builder, tool.title());
        append(builder, tool.source());
        append(builder, tool.kind());
        append(builder, tool.description());
        append(builder, tool.schema());
        append(builder, tool.owner());
        append(builder, tool.maturity());
        append(builder, tool.sourceType());
        append(builder, tool.descriptorPath());
        append(builder, tool.executable());
        appendList(builder, tool.commandTemplate());
        appendList(builder, tool.safeCommandIds());
        appendList(builder, tool.tags());
        appendList(builder, tool.defaultArgs());
        appendList(builder, tool.validationArgs());
        appendList(builder, tool.capabilities());
        appendList(builder, tool.formats());
        appendList(builder, tool.contentKinds());
        appendRawValues(builder, tool.raw(), 0);
        return builder.toString();
    }

    private boolean hasTag(ToolDescriptor tool, String normalizedTag) {
        return ToolDescriptorValues.mergeLists(tool.tags(), tool.capabilities(), tool.formats(), tool.contentKinds()).stream()
                .map(ToolDescriptorValues::normalize)
                .anyMatch(value -> value.equals(normalizedTag) || value.contains(normalizedTag));
    }

    private void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(' ').append(value);
        }
    }

    private void appendList(StringBuilder builder, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            append(builder, value);
        }
    }

    private void appendRawValues(StringBuilder builder, Object value, int depth) {
        if (value == null || depth > 5) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                append(builder, String.valueOf(entry.getKey()));
                appendRawValues(builder, entry.getValue(), depth + 1);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                appendRawValues(builder, item, depth + 1);
            }
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            append(builder, String.valueOf(value));
        }
    }

    private List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}._-]+");
        ArrayList<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result;
    }

    private record ScoredTool(ToolDescriptor tool, int score) {
    }
}
