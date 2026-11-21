package com.touhoulittlemaid.mini.core.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Declared surface of one action module.
 *
 * <p>{@code summary} is what the model sees in the 12-line module catalogue; the full
 * parameter list is only sent when it asks for a module description.
 */
public record ModuleSpec(String id, String summary, List<ParamSpec> params) {

    public ModuleSpec {
        Objects.requireNonNull(id, "id");
        summary = summary == null ? "" : summary;
        params = params == null ? List.of() : List.copyOf(params);
    }

    public ParamSpec param(String name) {
        for (ParamSpec spec : params) {
            if (spec.name().equals(name)) {
                return spec;
            }
        }
        return null;
    }

    /** Effective parameters: declared defaults plus the caller's values. */
    public Map<String, Object> applyDefaults(Map<String, Object> raw) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ParamSpec spec : params) {
            if (spec.defaultValue() != null) {
                values.put(spec.name(), spec.defaultValue());
            }
        }
        if (raw != null) {
            values.putAll(raw);
        }
        return values;
    }

    /** One line per parameter, used by {@code describe_module} and debug logs. */
    public List<String> describeParams() {
        List<String> lines = new ArrayList<>();
        for (ParamSpec spec : params) {
            StringBuilder line = new StringBuilder(spec.name())
                    .append(':').append(spec.type().name().toLowerCase(java.util.Locale.ROOT));
            if (spec.required()) {
                line.append(" required");
            }
            if (spec.min() != null || spec.max() != null) {
                line.append(" range=").append(spec.min()).append("..").append(spec.max());
            }
            if (!spec.allowed().isEmpty()) {
                line.append(" oneOf=").append(spec.allowed());
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
