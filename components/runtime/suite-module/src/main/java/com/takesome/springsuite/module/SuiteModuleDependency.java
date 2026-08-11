package com.takesome.springsuite.module;

public record SuiteModuleDependency(
        String moduleId,
        String operator,
        String version,
        boolean optional,
        String raw
) {
    public SuiteModuleDependency {
        moduleId = moduleId == null ? "" : moduleId.trim();
        operator = operator == null || operator.isBlank() ? ">=" : operator.trim();
        version = version == null ? "" : version.trim();
        raw = raw == null ? moduleId : raw.trim();
    }

    public boolean hasVersionConstraint() {
        return !version.isBlank();
    }

    public static SuiteModuleDependency parse(String raw, boolean optional) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            return new SuiteModuleDependency("", ">=", "", optional, raw);
        }

        String[] operators = new String[] {">=", "<=", "==", "!=", ">", "<", "=", "@"};
        for (String op : operators) {
            int index = value.indexOf(op);
            if (index > 0) {
                String id = value.substring(0, index).trim();
                String version = value.substring(index + op.length()).trim();
                String normalizedOperator = op.equals("@") ? ">=" : (op.equals("=") ? "==" : op);
                return new SuiteModuleDependency(id, normalizedOperator, version, optional, value);
            }
        }
        return new SuiteModuleDependency(value, ">=", "", optional, value);
    }
}
