package com.touhoulittlemaid.mini.core.autonomy;

import com.touhoulittlemaid.mini.core.plan.PlanGate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One thing worth doing, decided by server rules — never by the model.
 *
 * <p>The model only chooses among these. That is what keeps the token cost small and the
 * behaviour explainable: no scanning the world by prompt, no "why did she dismantle the
 * house".
 */
public record Opportunity(String id, String why, int priority, Map<String, Object> suggested) {

    /**
     * The modules a plan must use to fulfil each opportunity.
     *
     * <p>One source of truth for three consumers: the scanner drops candidates the current
     * tier cannot execute, the prompt tells the model exactly which modules to use, and the
     * tier policy stays the authority on what is allowed. The outer list holds alternatives:
     * gathering is possible with either mining or chopping, the rest need all of their modules.
     */
    private static final Map<String, List<List<String>>> MODULES = Map.of(
            "gather_building", List.of(List.of("mine_material"), List.of("chop_tree")),
            "harvest_farm", List.of(List.of("harvest_area", "plant_area")),
            "cook_food", List.of(List.of("withdraw_items", "smelt_items", "deposit_items")),
            "supply_owner", List.of(List.of("withdraw_items", "goto", "deposit_items")),
            "tidy_station", List.of(List.of("pickup_items", "deposit_items")),
            "tidy_chests", List.of(List.of("tidy_chests")));

    /** Flat list for the prompt; empty for an unknown id. */
    public static List<String> requiredModules(String id) {
        return MODULES.getOrDefault(id, List.of()).stream()
                .flatMap(List::stream).distinct().toList();
    }

    /** False for an unknown id: the server never offers an opportunity it cannot name. */
    public static boolean allowedBy(PlanGate.Policy policy, String id) {
        List<List<String>> alternatives = MODULES.getOrDefault(id, List.of());
        return !alternatives.isEmpty()
                && alternatives.stream().anyMatch(policy::allowsModules);
    }

    public Opportunity {
        id = id == null ? "" : id.strip();
        why = why == null ? "" : why;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (suggested != null) {
            suggested.entrySet().stream()
                    .filter(row -> row.getKey() != null)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(row -> copy.put(row.getKey(), row.getValue()));
        }
        suggested = Collections.unmodifiableMap(copy);
    }

    public static Opportunity of(String id, String why, int priority) {
        return new Opportunity(id, why, priority, Map.of());
    }

    public Opportunity with(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(suggested);
        copy.put(key, value);
        return new Opportunity(id, why, priority, copy);
    }

    /** One line for the UI and for logs; the model gets the same text in its prompt. */
    public String describe() {
        StringBuilder out = new StringBuilder(id);
        if (!why.isBlank()) {
            out.append(" because ").append(why);
        }
        if (!suggested.isEmpty()) {
            out.append(' ').append(suggested);
        }
        return out.toString();
    }
}
