package com.takesome.springsuite.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConfigYamlNode {
    final String key;
    String value;
    final LinkedHashMap<String, ConfigYamlNode> children = new LinkedHashMap<>();
    final List<String> listItems = new ArrayList<>();

    private ConfigYamlNode(String key) {
        this.key = key;
    }

    static ConfigYamlNode root() {
        return new ConfigYamlNode("");
    }

    static ConfigYamlNode parse(String yaml) {
        ConfigYamlNode root = root();
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(-1, root));

        for (String rawLine : yaml.split("\\R")) {
            String withoutComment = stripComment(rawLine);
            if (withoutComment.isBlank()) {
                continue;
            }

            int indent = countIndent(withoutComment);
            String line = withoutComment.stripLeading();

            while (stack.peek() != null && stack.peek().indent >= indent) {
                stack.pop();
            }
            ConfigYamlNode parent = stack.peek() == null ? root : stack.peek().node;

            if (line.startsWith("- ")) {
                parent.listItems.add(line.substring(2).trim());
                continue;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            ConfigYamlNode node = parent.children.computeIfAbsent(key, ConfigYamlNode::new);
            if (!value.isEmpty()) {
                node.value = value;
            }
            stack.push(new Frame(indent, node));
        }
        return root;
    }

    ConfigYamlNode find(String dottedKey) {
        ConfigYamlNode current = this;
        for (String part : dottedKey.split("\\.")) {
            current = current.children.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    boolean mergeMissingFrom(ConfigYamlNode defaults) {
        boolean changed = false;
        for (Map.Entry<String, ConfigYamlNode> entry : defaults.children.entrySet()) {
            ConfigYamlNode current = children.get(entry.getKey());
            if (current == null) {
                children.put(entry.getKey(), entry.getValue().deepCopy());
                changed = true;
                continue;
            }
            changed |= current.mergeMissingFrom(entry.getValue());
        }
        if ((value == null || value.isBlank())
                && listItems.isEmpty()
                && children.isEmpty()
                && defaults.value != null
                && !defaults.value.isBlank()) {
            value = defaults.value;
            changed = true;
        }
        if (listItems.isEmpty() && !defaults.listItems.isEmpty()) {
            listItems.addAll(defaults.listItems);
            changed = true;
        }
        return changed;
    }

    String toYaml() {
        StringBuilder out = new StringBuilder();
        for (ConfigYamlNode child : children.values()) {
            child.writeYaml(out, 0);
        }
        return out.toString();
    }

    private ConfigYamlNode deepCopy() {
        ConfigYamlNode copy = new ConfigYamlNode(key);
        copy.value = value;
        copy.listItems.addAll(listItems);
        for (Map.Entry<String, ConfigYamlNode> entry : children.entrySet()) {
            copy.children.put(entry.getKey(), entry.getValue().deepCopy());
        }
        return copy;
    }

    private void writeYaml(StringBuilder out, int indent) {
        String prefix = " ".repeat(indent);
        if (value != null && !value.isBlank()) {
            out.append(prefix).append(key).append(": ").append(value).append('\n');
        } else {
            out.append(prefix).append(key).append(":").append('\n');
        }
        for (String item : listItems) {
            out.append(prefix).append("  - ").append(item).append('\n');
        }
        for (ConfigYamlNode child : children.values()) {
            child.writeYaml(out, indent + 2);
        }
    }

    private static int countIndent(String value) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == ' ') {
            count++;
        }
        return count;
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

    private record Frame(int indent, ConfigYamlNode node) {
    }
}
