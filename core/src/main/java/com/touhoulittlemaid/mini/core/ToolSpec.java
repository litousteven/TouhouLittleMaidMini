package com.touhoulittlemaid.mini.core;

import java.util.Map;
import java.util.Objects;

/**
 * A tool exposed to an external agent.
 *
 * <p>The transport layer only sees this description. It must never reach into the game
 * to decide what a tool does.
 */
public record ToolSpec(
        String name,
        String title,
        String description,
        Map<String, ToolParameter> parameters,
        boolean readOnly,
        boolean destructive,
        boolean idempotent) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        if (name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
    }

    /** JSON type used by the tool input schema. */
    public enum ParameterType {
        STRING("string"),
        INTEGER("integer"),
        NUMBER("number"),
        BOOLEAN("boolean");

        private final String jsonName;

        ParameterType(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }
    }

    /**
     * One named input field.
     *
     * @param required whether the agent must supply the field
     * @param numericMinimum inclusive lower bound for numeric fields, when present
     */
    public record ToolParameter(
            ParameterType type,
            String description,
            boolean required,
            Double numericMinimum) {

        public ToolParameter {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(description, "description");
        }

        public static ToolParameter required(ParameterType type, String description) {
            return new ToolParameter(type, description, true, null);
        }

        public static ToolParameter optional(ParameterType type, String description) {
            return new ToolParameter(type, description, false, null);
        }

        public static ToolParameter requiredString(String description) {
            return required(ParameterType.STRING, description);
        }

        public static ToolParameter requiredInteger(String description) {
            return required(ParameterType.INTEGER, description);
        }

        public static ToolParameter requiredIntegerWithMinimum(String description, double minimum) {
            return new ToolParameter(ParameterType.INTEGER, description, true, minimum);
        }

        public static ToolParameter optionalIntegerWithMinimum(String description, double minimum) {
            return new ToolParameter(ParameterType.INTEGER, description, false, minimum);
        }

        public static ToolParameter requiredNumberWithMinimum(String description, double minimum) {
            return new ToolParameter(ParameterType.NUMBER, description, true, minimum);
        }
    }
}
