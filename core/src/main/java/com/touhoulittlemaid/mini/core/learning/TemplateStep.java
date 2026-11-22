package com.touhoulittlemaid.mini.core.learning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One step of a template: a module and parameters whose variable parts became
 * {@code ${name}} placeholders. Defaults keep the concrete values from the plan that
 * inspired it, so a template can be materialised without any binding at all.
 */
public record TemplateStep(String module, Map<String, Object> params,
        Map<String, Object> defaults) {

    public TemplateStep {
        module = module == null ? "" : module.strip();
        params = copy(params);
        defaults = copy(defaults);
    }

    public static TemplateStep of(String module, Map<String, Object> params) {
        return new TemplateStep(module, params, Map.of());
    }

    /** Resolves placeholders from bindings, falling back to the captured defaults. */
    public Map<String, Object> materialise(Map<String, Object> bindings) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> row : params.entrySet()) {
            Object value = row.getValue();
            if (value instanceof String text && text.startsWith("${")
                    && text.endsWith("}")) {
                String key = text.substring(2, text.length() - 1);
                Object bound = bindings == null ? null : bindings.get(key);
                if (bound == null) {
                    bound = defaults.get(row.getKey());
                }
                out.put(row.getKey(), bound == null ? "" : bound);
            } else {
                out.put(row.getKey(), value);
            }
        }
        return out;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
