package com.touhoulittlemaid.mini.core.learning;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a successful plan into a proposal draft: the module sequence plus parameter
 * placeholders. Values that vary between runs (positions, item ids, counts, text) become
 * {@code ${name}}; everything else stays as it was, so the template is still runnable.
 */
public record TemplateDraft(String name, String goal, String description,
        List<TemplateStep> steps) {

    /** A one-step plan is not worth learning, and a say-only plan teaches nothing. */
    public static final int MIN_STEPS = 2;
    public static final int MAX_STEPS = 24;

    private static final Set<String> VARIABLE_PARAMS = Set.of(
            "x", "y", "z", "item", "seed", "input", "fuel", "ids", "text", "material");

    private static final Gson GSON = new Gson();

    public static TemplateDraft fromPlanJson(String planJson, String sourcePlanId) {
        if (planJson == null || planJson.isBlank()) {
            return null;
        }
        JsonObject root;
        JsonArray steps;
        try {
            JsonElement parsed = JsonParser.parseString(planJson);
            if (!parsed.isJsonObject()) {
                return null;
            }
            root = parsed.getAsJsonObject();
            if (!root.has("steps") || !root.get("steps").isJsonArray()) {
                return null;
            }
            steps = root.getAsJsonArray("steps");
        } catch (RuntimeException exception) {
            return null;
        }
        if (steps.size() < MIN_STEPS || steps.size() > MAX_STEPS) {
            return null;
        }
        String goal = text(root.get("goal"));
        List<TemplateStep> converted = new ArrayList<>();
        List<String> modules = new ArrayList<>();
        for (JsonElement element : steps) {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject step = element.getAsJsonObject();
            String module = text(step.get("module"));
            if (module.isBlank()) {
                return null;
            }
            modules.add(module);
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, Object> defaults = new LinkedHashMap<>();
            if (step.has("params") && step.get("params").isJsonObject()) {
                for (Map.Entry<String, JsonElement> row
                        : step.getAsJsonObject("params").entrySet()) {
                    Object value = GSON.fromJson(row.getValue(), Object.class);
                    if (VARIABLE_PARAMS.contains(row.getKey())) {
                        params.put(row.getKey(), "${" + row.getKey() + "}");
                        defaults.put(row.getKey(), value);
                    } else {
                        params.put(row.getKey(), value);
                    }
                }
            }
            converted.add(new TemplateStep(module, params, defaults));
        }
        if (modules.stream().allMatch("say"::equals)) {
            return null;
        }
        String name = slug(goal.isBlank() ? String.join("-", modules) : goal);
        if (name.isBlank()) {
            name = "template-" + String.join("-", modules);
        }
        String description = "从成功计划 " + (sourcePlanId == null ? "" : sourcePlanId)
                + " 提炼：" + String.join(" → ", modules);
        return new TemplateDraft(name, goal, description, List.copyOf(converted));
    }

    /** Builds a plan JSON from a template, substituting placeholders where bindings exist. */
    public static String planJson(PlanTemplate template, Map<String, Object> bindings) {
        JsonArray steps = new JsonArray();
        for (TemplateStep step : template.steps()) {
            JsonObject object = new JsonObject();
            object.addProperty("module", step.module());
            JsonObject params = new JsonObject();
            for (Map.Entry<String, Object> row : step.materialise(bindings).entrySet()) {
                params.add(row.getKey(), GSON.toJsonTree(row.getValue()));
            }
            object.add("params", params);
            steps.add(object);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("goal", "template:" + template.id());
        root.add("steps", steps);
        return GSON.toJson(root);
    }

    private static String text(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString().strip() : "";
    }

    private static String slug(String value) {
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\-]+", "-")
                .replaceAll("-+", "-").replaceAll("^-|-$", "");
        return cleaned.length() > 40 ? cleaned.substring(0, 40).replaceAll("-?$", "")
                : cleaned;
    }
}
