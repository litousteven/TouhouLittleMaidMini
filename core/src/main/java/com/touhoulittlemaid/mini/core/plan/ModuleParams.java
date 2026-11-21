package com.touhoulittlemaid.mini.core.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed access to a step's parameters.
 *
 * <p>Values arrive as decoded JSON, so numbers may be {@link Long}, {@link Integer} or
 * {@link Double}. Accessors normalise instead of forcing every caller to repeat casts.
 */
public final class ModuleParams {

    private final Map<String, Object> values;

    private ModuleParams(Map<String, Object> values) {
        this.values = values;
    }

    public static ModuleParams of(Map<String, Object> raw) {
        return new ModuleParams(raw == null ? Map.of() : new LinkedHashMap<>(raw));
    }

    public static ModuleParams empty() {
        return new ModuleParams(Map.of());
    }

    public Map<String, Object> raw() {
        return Map.copyOf(values);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public Object get(String name) {
        return values.get(name);
    }

    public String string(String name, String fallback) {
        Object value = values.get(name);
        return value instanceof String text ? text : fallback;
    }

    public int integer(String name, int fallback) {
        Object value = values.get(name);
        if (value instanceof Number number) {
            return (int) Math.round(number.doubleValue());
        }
        if (value instanceof String text) {
            try {
                return (int) Math.round(Double.parseDouble(text.strip()));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public double number(String name, double fallback) {
        Object value = values.get(name);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean bool(String name, boolean fallback) {
        Object value = values.get(name);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.strip());
        }
        return fallback;
    }

    public List<String> strings(String name) {
        Object value = values.get(name);
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof String text) {
                    result.add(text);
                }
            }
        } else if (value instanceof String text && !text.isBlank()) {
            result.add(text);
        }
        return List.copyOf(result);
    }

    /**
     * Replaces whole-value {@code $name} references with the bound output of an earlier
     * step.
     *
     * @throws PlanFormatException when a reference names a binding that was never set
     */
    public ModuleParams resolve(Map<String, Map<String, Object>> bindings)
            throws PlanFormatException {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), bindings, entry.getKey()));
        }
        return new ModuleParams(resolved);
    }

    private static Object resolveValue(Object value, Map<String, Map<String, Object>> bindings,
            String key) throws PlanFormatException {
        if (value instanceof String text) {
            return resolveReference(text, bindings, key);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(element instanceof String text
                        ? resolveReference(text, bindings, key)
                        : element);
            }
            return List.copyOf(copy);
        }
        return value;
    }

    private static Object resolveReference(String text, Map<String, Map<String, Object>> bindings,
            String key) throws PlanFormatException {
        if (!isReference(text)) {
            return text;
        }
        String name = text.substring(1);
        Map<String, Object> bound = bindings.get(name);
        if (bound == null) {
            throw new PlanFormatException(FailureCodes.UNBOUND_BINDING,
                    "parameter " + key + " references $" + name + ", which no earlier step bound");
        }
        // A whole-object reference keeps the map; a scalar binding exposes its value.
        if (bound.size() == 1) {
            return bound.values().iterator().next();
        }
        return bound;
    }

    /** True when the whole string is a single binding reference such as {@code $crops}. */
    public static boolean isReference(String text) {
        return text.length() > 1 && text.charAt(0) == '$'
                && text.substring(1).chars().allMatch(character ->
                        Character.isLetterOrDigit(character) || character == '_');
    }
}
