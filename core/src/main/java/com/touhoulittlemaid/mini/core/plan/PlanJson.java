package com.touhoulittlemaid.mini.core.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict JSON codec for {@link Plan}.
 *
 * <p>Unknown fields are rejected rather than ignored. A model that emits a shell command,
 * a tool name or an invented field gets a parse error instead of a silently reduced plan.
 */
public final class PlanJson {

    private static final Gson PRETTY = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private static final Set<String> PLAN_FIELDS = Set.of("version", "goal", "steps");
    private static final Set<String> STEP_FIELDS =
            Set.of("module", "params", "bind", "if", "on_failure", "repeat");
    private static final Set<String> REPEAT_FIELDS = Set.of("max", "while");
    private static final int MAX_GOAL_LENGTH = 512;
    private static final int MAX_MODULE_ID_LENGTH = 64;
    private static final int MAX_PARAM_DEPTH = 4;

    private PlanJson() {
    }

    public static ParsedPlan parse(String json) throws PlanFormatException {
        if (json == null || json.isBlank()) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN, "plan payload is empty");
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > PlanLimits.MAX_PLAN_BYTES) {
            throw new PlanFormatException(FailureCodes.PLAN_TOO_LARGE,
                    "plan is " + bytes + " bytes, limit is " + PlanLimits.MAX_PLAN_BYTES);
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException exception) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                    "plan is not valid JSON: " + rootCause(exception));
        }
        if (!root.isJsonObject()) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                    "plan must be a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        rejectUnknownFields(object, PLAN_FIELDS, "plan");

        int version = requireInt(object, "version", "plan");
        if (version != PlanLimits.VERSION) {
            throw new PlanFormatException(FailureCodes.UNKNOWN_VERSION,
                    "plan version " + version + " is not supported, expected "
                            + PlanLimits.VERSION);
        }
        String goal = optionalString(object, "goal", MAX_GOAL_LENGTH, "plan");
        JsonElement stepsElement = object.get("steps");
        if (stepsElement == null || !stepsElement.isJsonArray()) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                    "plan.steps must be an array");
        }
        JsonArray stepsArray = stepsElement.getAsJsonArray();
        if (stepsArray.size() > PlanLimits.MAX_STEPS) {
            throw new PlanFormatException(FailureCodes.TOO_MANY_STEPS,
                    "plan has " + stepsArray.size() + " steps, limit is "
                            + PlanLimits.MAX_STEPS);
        }
        List<PlanStep> steps = new ArrayList<>(stepsArray.size());
        for (int index = 0; index < stepsArray.size(); index++) {
            JsonElement stepElement = stepsArray.get(index);
            if (!stepElement.isJsonObject()) {
                throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                        "step " + index + " must be an object");
            }
            steps.add(parseStep(stepElement.getAsJsonObject(), index));
        }
        return new ParsedPlan(new Plan(version, goal, steps), bytes);
    }

    private static PlanStep parseStep(JsonObject object, int index) throws PlanFormatException {
        String where = "step " + index;
        rejectUnknownFields(object, STEP_FIELDS, where);

        JsonElement moduleElement = object.get("module");
        if (moduleElement == null || !moduleElement.isJsonPrimitive()
                || !moduleElement.getAsJsonPrimitive().isString()) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                    where + ".module must be a string");
        }
        String module = moduleElement.getAsString().strip();
        if (module.isEmpty() || module.length() > MAX_MODULE_ID_LENGTH
                || !module.matches("[a-z][a-z0-9_]*")) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                    where + ".module is not a valid module id: " + module);
        }

        Map<String, Object> params = Map.of();
        JsonElement paramsElement = object.get("params");
        if (paramsElement != null && !paramsElement.isJsonNull()) {
            if (!paramsElement.isJsonObject()) {
                throw new PlanFormatException(FailureCodes.INVALID_PARAMS,
                        where + ".params must be an object");
            }
            params = convertObject(paramsElement.getAsJsonObject(), where, 0);
        }

        String bind = optionalString(object, "bind", 32, where);
        if (!bind.isEmpty() && !bind.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                    where + ".bind is not a valid name: " + bind);
        }
        String condition = optionalString(object, "if", PlanLimits.MAX_PREDICATE_LENGTH, where);
        String onFailure = optionalString(object, "on_failure", 16, where);
        RepeatSpec repeat = parseRepeat(object.get("repeat"), where);

        return new PlanStep(module, ModuleParams.of(params), bind, condition,
                FailurePolicy.parse(onFailure), repeat);
    }

    private static RepeatSpec parseRepeat(JsonElement element, String where)
            throws PlanFormatException {
        if (element == null || element.isJsonNull()) {
            return RepeatSpec.ONCE;
        }
        if (!element.isJsonObject()) {
            throw new PlanFormatException(FailureCodes.INVALID_REPEAT,
                    where + ".repeat must be an object");
        }
        JsonObject object = element.getAsJsonObject();
        rejectUnknownFields(object, REPEAT_FIELDS, where + ".repeat");
        int max = requireInt(object, "max", where + ".repeat");
        if (max < 1 || max > PlanLimits.MAX_REPEAT) {
            throw new PlanFormatException(FailureCodes.INVALID_REPEAT,
                    where + ".repeat.max must be 1.." + PlanLimits.MAX_REPEAT + ", got " + max);
        }
        String whileCondition = optionalString(object, "while",
                PlanLimits.MAX_PREDICATE_LENGTH, where + ".repeat");
        return new RepeatSpec(max, whileCondition);
    }

    private static Map<String, Object> convertObject(JsonObject object, String where, int depth)
            throws PlanFormatException {
        if (depth > MAX_PARAM_DEPTH) {
            throw new PlanFormatException(FailureCodes.INVALID_PARAMS,
                    where + ".params is nested deeper than " + MAX_PARAM_DEPTH);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            values.put(entry.getKey(), convertValue(entry.getValue(), where, depth));
        }
        return values;
    }

    private static Object convertValue(JsonElement element, String where, int depth)
            throws PlanFormatException {
        if (element == null || element.isJsonNull()) {
            throw new PlanFormatException(FailureCodes.INVALID_PARAMS,
                    where + ".params contains null");
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                double value = primitive.getAsDouble();
                if (value == Math.rint(value) && !Double.isInfinite(value)
                        && Math.abs(value) <= 9_007_199_254_740_992.0D) {
                    return (long) value;
                }
                return value;
            }
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement child : element.getAsJsonArray()) {
                list.add(convertValue(child, where, depth + 1));
            }
            return List.copyOf(list);
        }
        if (element.isJsonObject()) {
            return convertObject(element.getAsJsonObject(), where, depth + 1);
        }
        throw new PlanFormatException(FailureCodes.INVALID_PARAMS,
                where + ".params contains an unsupported value");
    }

    /** Serialises a plan for debug output and tests. */
    public static String write(Plan plan) {
        JsonObject root = new JsonObject();
        root.addProperty("version", plan.version());
        if (!plan.goal().isEmpty()) {
            root.addProperty("goal", plan.goal());
        }
        JsonArray steps = new JsonArray();
        for (PlanStep step : plan.steps()) {
            JsonObject object = new JsonObject();
            object.addProperty("module", step.module());
            object.add("params", PRETTY.toJsonTree(step.params().raw()));
            if (step.hasBind()) {
                object.addProperty("bind", step.bind());
            }
            if (step.hasCondition()) {
                object.addProperty("if", step.condition());
            }
            FailurePolicy policy = step.onFailure();
            if (policy.kind() != FailurePolicy.Kind.ABORT) {
                object.addProperty("on_failure", policy.kind() == FailurePolicy.Kind.CONTINUE
                        ? "continue"
                        : "retry:" + policy.retries());
            }
            if (!step.repeat().isOnce()) {
                JsonObject repeat = new JsonObject();
                repeat.addProperty("max", step.repeat().max());
                if (step.repeat().hasCondition()) {
                    repeat.addProperty("while", step.repeat().whileCondition());
                }
                object.add("repeat", repeat);
            }
            steps.add(object);
        }
        root.add("steps", steps);
        return PRETTY.toJson(root);
    }

    private static void rejectUnknownFields(JsonObject object, Set<String> allowed, String where)
            throws PlanFormatException {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                        where + " has an unsupported field: " + key);
            }
        }
    }

    private static int requireInt(JsonObject object, String field, String where)
            throws PlanFormatException {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                    where + "." + field + " must be a number");
        }
        double value = element.getAsDouble();
        if (value != Math.rint(value)) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                    where + "." + field + " must be an integer");
        }
        return (int) value;
    }

    private static String optionalString(JsonObject object, String field, int maxLength,
            String where) throws PlanFormatException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                    where + "." + field + " must be a string");
        }
        String value = element.getAsString().strip();
        if (value.length() > maxLength) {
            throw new PlanFormatException(FailureCodes.INVALID_PLAN,
                    where + "." + field + " is longer than " + maxLength + " characters");
        }
        return value;
    }

    private static String rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                : message;
    }
}
