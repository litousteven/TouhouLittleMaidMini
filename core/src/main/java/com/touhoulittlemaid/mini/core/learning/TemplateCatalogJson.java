package com.touhoulittlemaid.mini.core.learning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict round trip for the learning file.
 *
 * <p>A bad row is recorded as a problem and dropped; it never stops the other rows from
 * loading, and a malformed file never turns into "trust" by accident. The on-disk shape is
 * deliberately plain JSON so an operator can read and edit it by hand.
 */
public final class TemplateCatalogJson {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Loaded(TemplateCatalog catalog, List<String> problems) {
    }

    private TemplateCatalogJson() {
    }

    public static String encode(TemplateCatalog catalog) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray proposals = new JsonArray();
        for (TemplateProposal proposal : catalog.proposals()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", proposal.id());
            row.addProperty("name", proposal.name());
            row.addProperty("goal", proposal.goal());
            row.addProperty("description", proposal.description());
            row.addProperty("source_plan_id", proposal.sourcePlanId());
            row.addProperty("created_at", proposal.createdAt());
            row.addProperty("status", proposal.status().id());
            row.add("steps", steps(proposal.steps()));
            proposals.add(row);
        }
        JsonArray templates = new JsonArray();
        for (PlanTemplate template : catalog.templates()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", template.id());
            row.addProperty("name", template.name());
            row.addProperty("goal", template.goal());
            row.addProperty("trust", template.trust().id());
            row.addProperty("supervised_runs", template.supervisedRuns());
            row.addProperty("consecutive_failures", template.consecutiveFailures());
            row.addProperty("successes", template.successes());
            row.addProperty("failures", template.failures());
            row.addProperty("source_proposal_id", template.sourceProposalId());
            row.addProperty("source_plan_id", template.sourcePlanId());
            row.addProperty("created_at", template.createdAt());
            row.addProperty("updated_at", template.updatedAt());
            row.addProperty("revision", template.revision());
            row.add("steps", steps(template.steps()));
            templates.add(row);
        }
        root.add("proposals", proposals);
        root.add("templates", templates);
        return GSON.toJson(root);
    }

    public static Loaded decode(String json) {
        TemplateCatalog catalog = new TemplateCatalog();
        List<String> problems = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return new Loaded(catalog, problems);
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("root is not an object");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            problems.add("invalid_json");
            return new Loaded(catalog, problems);
        }
        List<TemplateProposal> proposals = new ArrayList<>();
        List<PlanTemplate> templates = new ArrayList<>();
        for (JsonElement element : array(root, "proposals")) {
            try {
                JsonObject row = element.getAsJsonObject();
                proposals.add(new TemplateProposal(str(row, "id"), str(row, "name"),
                        str(row, "goal"), str(row, "description"),
                        readSteps(row), str(row, "source_plan_id"), num(row, "created_at"),
                        TemplateProposal.Status.fromId(str(row, "status"))));
            } catch (RuntimeException exception) {
                problems.add("bad_proposal");
            }
        }
        for (JsonElement element : array(root, "templates")) {
            try {
                JsonObject row = element.getAsJsonObject();
                templates.add(new PlanTemplate(str(row, "id"), str(row, "name"),
                        str(row, "goal"), readSteps(row),
                        TrustLevel.fromId(str(row, "trust")),
                        (int) num(row, "supervised_runs"),
                        (int) num(row, "consecutive_failures"),
                        (int) num(row, "successes"), (int) num(row, "failures"),
                        str(row, "source_proposal_id"), str(row, "source_plan_id"),
                        num(row, "created_at"), num(row, "updated_at"),
                        Math.max(1, (int) num(row, "revision"))));
            } catch (RuntimeException exception) {
                problems.add("bad_template");
            }
        }
        catalog.replace(proposals, templates);
        return new Loaded(catalog, problems);
    }

    private static JsonArray steps(List<TemplateStep> values) {
        JsonArray out = new JsonArray();
        for (TemplateStep step : values) {
            JsonObject row = new JsonObject();
            row.addProperty("module", step.module());
            row.add("params", GSON.toJsonTree(step.params()));
            row.add("defaults", GSON.toJsonTree(step.defaults()));
            out.add(row);
        }
        return out;
    }

    private static List<TemplateStep> readSteps(JsonObject row) {
        List<TemplateStep> out = new ArrayList<>();
        for (JsonElement element : array(row, "steps")) {
            JsonObject step = element.getAsJsonObject();
            out.add(new TemplateStep(str(step, "module"), object(step, "params"),
                    object(step, "defaults")));
        }
        return out;
    }

    private static Map<String, Object> object(JsonObject row, String key) {
        if (!row.has(key) || !row.get(key).isJsonObject()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : row.getAsJsonObject(key).entrySet()) {
            out.put(entry.getKey(), GSON.fromJson(entry.getValue(), Object.class));
        }
        return out;
    }

    private static JsonArray array(JsonObject row, String key) {
        return row.has(key) && row.get(key).isJsonArray()
                ? row.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject row, String key) {
        return row.has(key) && row.get(key).isJsonPrimitive()
                ? row.get(key).getAsString() : "";
    }

    private static long num(JsonObject row, String key) {
        try {
            return row.has(key) && row.get(key).isJsonPrimitive()
                    ? row.get(key).getAsLong() : 0L;
        } catch (RuntimeException exception) {
            return 0L;
        }
    }
}
