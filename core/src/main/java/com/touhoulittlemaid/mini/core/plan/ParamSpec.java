package com.touhoulittlemaid.mini.core.plan;

import java.util.List;

/**
 * One declared module parameter.
 *
 * <p>The spec is the only place parameter types live, so the gate, the client-side quick
 * check and the module catalogue all read the same description.
 */
public record ParamSpec(
        String name,
        ParamType type,
        boolean required,
        Object defaultValue,
        Double min,
        Double max,
        List<String> allowed,
        String description) {

    public enum ParamType {
        STRING,
        INT,
        DOUBLE,
        BOOLEAN,
        STRING_LIST
    }

    public ParamSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("parameter name is required");
        }
        type = type == null ? ParamType.STRING : type;
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
        description = description == null ? "" : description;
    }

    public static ParamSpec string(String name, boolean required, String description) {
        return new ParamSpec(name, ParamType.STRING, required, null, null, null, List.of(),
                description);
    }

    public static ParamSpec string(String name, String defaultValue, String description) {
        return new ParamSpec(name, ParamType.STRING, false, defaultValue, null, null, List.of(),
                description);
    }

    public static ParamSpec intRange(String name, int min, int max, Integer defaultValue,
            String description) {
        return new ParamSpec(name, ParamType.INT, defaultValue == null,
                defaultValue, (double) min, (double) max, List.of(), description);
    }

    public static ParamSpec number(String name, double min, double max, Double defaultValue,
            String description) {
        return new ParamSpec(name, ParamType.DOUBLE, defaultValue == null,
                defaultValue, min, max, List.of(), description);
    }

    /** Optional integer: absent means "not supplied", which modules read with has(). */
    public static ParamSpec optionalInt(String name, int min, int max, String description) {
        return new ParamSpec(name, ParamType.INT, false, null, (double) min, (double) max,
                List.of(), description);
    }

    public static ParamSpec bool(String name, boolean defaultValue, String description) {
        return new ParamSpec(name, ParamType.BOOLEAN, false, defaultValue, null, null,
                List.of(), description);
    }

    public static ParamSpec stringList(String name, boolean required, String description) {
        return new ParamSpec(name, ParamType.STRING_LIST, required, null, null, null,
                List.of(), description);
    }

    public static ParamSpec enumeration(String name, boolean required, List<String> values,
            String description) {
        return new ParamSpec(name, ParamType.STRING, required, null, null, null, values,
                description);
    }
}
