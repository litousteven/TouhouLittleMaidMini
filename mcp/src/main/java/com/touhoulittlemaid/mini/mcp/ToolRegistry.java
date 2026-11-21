package com.touhoulittlemaid.mini.mcp;

import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.core.ToolSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tool catalogue and dispatch for the MCP transport.
 *
 * <p>Handlers run on whatever thread the transport calls them from. Anything that
 * touches the game must hop to the server thread itself; this class never does that on
 * the handler's behalf.
 */
public final class ToolRegistry {

    /** A handler receives decoded arguments and returns a structured result. */
    @FunctionalInterface
    public interface ToolHandler {
        ToolResult invoke(Map<String, Object> arguments);
    }

    private record Entry(ToolSpec spec, ToolHandler handler) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public synchronized void register(ToolSpec spec, ToolHandler handler) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(handler, "handler");
        if (entries.containsKey(spec.name())) {
            throw new IllegalArgumentException("Tool already registered: " + spec.name());
        }
        entries.put(spec.name(), new Entry(spec, handler));
    }

    public synchronized List<ToolSpec> tools() {
        return entries.values().stream().map(Entry::spec).toList();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized Optional<ToolSpec> spec(String name) {
        Entry entry = entries.get(name);
        return entry == null ? Optional.empty() : Optional.of(entry.spec());
    }

    /**
     * Returns a new registry containing only the specs accepted by {@code predicate}.
     * Used to honour the configured generic action families without changing handlers.
     */
    public synchronized ToolRegistry filter(java.util.function.Predicate<ToolSpec> predicate) {
        ToolRegistry filtered = new ToolRegistry();
        for (Entry entry : entries.values()) {
            if (predicate.test(entry.spec())) {
                filtered.register(entry.spec(), entry.handler());
            }
        }
        return filtered;
    }

    public ToolResult invoke(String name, Map<String, Object> arguments) {
        Entry entry;
        synchronized (this) {
            entry = entries.get(name);
        }
        if (entry == null) {
            return ToolResult.unknownTool(name);
        }

        Optional<ToolResult> validationError = validate(entry.spec(), arguments);
        if (validationError.isPresent()) {
            return validationError.get();
        }
        try {
            return entry.handler().invoke(arguments);
        } catch (RuntimeException exception) {
            return ToolResult.rejected("internal_error",
                    "Tool failed: " + exception.getClass().getSimpleName());
        }
    }

    private static Optional<ToolResult> validate(ToolSpec spec, Map<String, Object> arguments) {
        for (Map.Entry<String, ToolSpec.ToolParameter> parameter : spec.parameters().entrySet()) {
            String name = parameter.getKey();
            ToolSpec.ToolParameter definition = parameter.getValue();
            Object value = arguments.get(name);

            if (value == null) {
                if (definition.required()) {
                    return Optional.of(ToolResult.rejected("invalid_params",
                            "Missing required parameter: " + name));
                }
                continue;
            }
            if (!matchesType(definition.type(), value)) {
                return Optional.of(ToolResult.rejected("invalid_params",
                        "Parameter " + name + " must be a " + definition.type().jsonName()));
            }
            if (definition.numericMinimum() != null && value instanceof Number number
                    && number.doubleValue() < definition.numericMinimum()) {
                return Optional.of(ToolResult.rejected("invalid_params",
                        "Parameter " + name + " must be >= " + definition.numericMinimum()));
            }
        }
        for (String supplied : arguments.keySet()) {
            if (!spec.parameters().containsKey(supplied)) {
                return Optional.of(ToolResult.rejected("invalid_params",
                        "Unexpected parameter: " + supplied));
            }
        }
        return Optional.empty();
    }

    private static boolean matchesType(ToolSpec.ParameterType type, Object value) {
        return switch (type) {
            case STRING -> value instanceof String;
            case STRING_ARRAY -> value instanceof java.util.List<?> list
                    && list.stream().allMatch(String.class::isInstance);
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
        };
    }
}
