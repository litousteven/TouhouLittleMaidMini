package com.touhoulittlemaid.mini.mcp;

import com.touhoulittlemaid.mini.core.ActionKind;
import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.ContextCategory;
import com.touhoulittlemaid.mini.core.ContextQuery;
import com.touhoulittlemaid.mini.core.InventoryAction;
import com.touhoulittlemaid.mini.core.InventoryRequest;
import com.touhoulittlemaid.mini.core.InventoryTarget;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.core.ToolSpec;
import com.touhoulittlemaid.mini.core.ToolSpec.ToolParameter;
import com.touhoulittlemaid.mini.core.ToolSpec.ParameterType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The M2 tool surface.
 *
 * <p>Every tool maps onto one of the six generic action families. Farming, fishing and
 * similar gameplay are compositions the agent performs, so no per-activity tool exists.
 * The MCP contract stays inside the ten-tool budget.
 */
public final class CompanionTools {

    /**
     * What the transport needs from a live companion body. Implementations live in the
     * loader module and are responsible for threading.
     */
    public interface CompanionBackend {

        /** Companions visible to the agent right now. */
        java.util.List<CompanionSnapshot> listCompanions();

        CompanionSnapshot state(UUID companionId);

        /** Snapshot of the companion, or empty when the id is unknown. */
        default Optional<CompanionSnapshot> findState(UUID companionId) {
            try {
                return Optional.ofNullable(state(companionId));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }

        /** Move to a block position. */
        ToolResult move(UUID companionId, int x, int y, int z, double speed);

        /** Right-click a block position. */
        ToolResult interact(UUID companionId, int x, int y, int z);

        /** Bounded observation of one context category. */
        ToolResult context(UUID companionId, ContextQuery query);

        /** Read or change the companion inventory. */
        ToolResult inventory(UUID companionId, InventoryRequest request);

        /** Change the local behaviour. */
        ToolResult setBehavior(UUID companionId, LocalBehavior behavior);

        /** Stop the current operation. */
        ToolResult stop(UUID companionId);

        /** Long-poll events after a cursor. */
        ToolResult waitEvents(UUID companionId, long afterSequence, int timeoutSeconds, int maxEvents);

        /** Say a short line to the owner. */
        ToolResult say(UUID companionId, String text);
    }

    private CompanionTools() {
    }

    public static ToolRegistry registry(CompanionBackend backend) {
        Objects.requireNonNull(backend, "backend");
        ToolRegistry registry = new ToolRegistry();

        registry.register(new ToolSpec(
                        "companion_list",
                        "List companions",
                        "Lists every companion the agent may control.",
                        Map.of(),
                        true, false, true),
                arguments -> {
                    var companions = backend.listCompanions();
                    if (companions.isEmpty()) {
                        return ToolResult.ok("no_companions", "[]");
                    }
                    StringBuilder json = new StringBuilder("[");
                    for (int index = 0; index < companions.size(); index++) {
                        CompanionSnapshot snapshot = companions.get(index);
                        if (index > 0) {
                            json.append(',');
                        }
                        json.append(snapshotJson(snapshot));
                    }
                    return ToolResult.ok("ok", json.append(']').toString());
                });

        registry.register(new ToolSpec(
                        "companion_state",
                        "Read companion state",
                        "Returns identity, owner, local behaviour and home state.",
                        Map.of("companion_id", ToolParameter.requiredString("Companion UUID")),
                        true, false, true),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    Optional<CompanionSnapshot> snapshot = backend.findState(id.get());
                    if (snapshot.isEmpty()) {
                        return ToolResult.rejected("unknown_companion",
                                "No such companion: " + id.get());
                    }
                    return ToolResult.ok("ok", snapshotJson(snapshot.get()));
                });

        registry.register(new ToolSpec(
                        "companion_move",
                        "Move companion",
                        "Walk the companion to a block position.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "x", ToolParameter.requiredInteger("Target block X"),
                                "y", ToolParameter.requiredInteger("Target block Y"),
                                "z", ToolParameter.requiredInteger("Target block Z")),
                        false, false, false),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    return backend.move(id.get(),
                            intValue(arguments, "x"), intValue(arguments, "y"), intValue(arguments, "z"),
                            1.0D);
                });

        registry.register(new ToolSpec(
                        "companion_interact",
                        "Interact with a block",
                        "Right-click a block position as the companion.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "x", ToolParameter.requiredInteger("Target block X"),
                                "y", ToolParameter.requiredInteger("Target block Y"),
                                "z", ToolParameter.requiredInteger("Target block Z")),
                        false, false, false),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    return backend.interact(id.get(),
                            intValue(arguments, "x"), intValue(arguments, "y"), intValue(arguments, "z"));
                });

        registry.register(new ToolSpec(
                        "companion_context",
                        "Read nearby context",
                        "Loads one bounded context category: self, owner, inventory, blocks, "
                                + "entities, containers or hazards. Blocks and entities can be "
                                + "filtered by tags and IDs.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "category", ToolParameter.optionalString(
                                        "self, owner, inventory, blocks, entities, containers or hazards"),
                                "radius", ToolParameter.optionalIntegerWithMinimum("Block radius", 1.0D),
                                "tags", ToolParameter.optionalStringArray(
                                        "Block or entity tags, for example minecraft:crops"),
                                "block_ids", ToolParameter.optionalStringArray(
                                        "Exact block IDs to include"),
                                "entity_types", ToolParameter.optionalStringArray(
                                        "Exact entity type IDs to include"),
                                "max_results", ToolParameter.optionalIntegerWithMinimum(
                                        "Maximum returned entries", 1.0D)),
                        true, false, true),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    ContextCategory category;
                    try {
                        category = ContextCategory.fromId(
                                stringValue(arguments, "category", ContextCategory.BLOCKS.id()));
                    } catch (IllegalArgumentException exception) {
                        return ToolResult.rejected("invalid_params",
                                "category must be self, owner, inventory, blocks, entities, "
                                        + "containers or hazards");
                    }
                    int radius = arguments.containsKey("radius")
                            ? intValue(arguments, "radius") : ContextQuery.DEFAULT_RADIUS;
                    int maxResults = arguments.containsKey("max_results")
                            ? intValue(arguments, "max_results") : ContextQuery.DEFAULT_MAX_RESULTS;
                    ContextQuery query = new ContextQuery(category, radius,
                            stringList(arguments, "tags"),
                            stringList(arguments, "block_ids"),
                            stringList(arguments, "entity_types"),
                            maxResults);
                    return backend.context(id.get(), query);
                });

        registry.register(new ToolSpec(
                        "companion_inventory",
                        "Inspect and change the inventory",
                        "Lists, reads, moves, equips or drops items that already exist in "
                                + "the companion's backpack and equipment slots.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "action", ToolParameter.optionalString(
                                        "list, read, move, equip or drop"),
                                "item", ToolParameter.optionalString("Exact item ID"),
                                "count", ToolParameter.optionalIntegerWithMinimum(
                                        "Item count", 1.0D),
                                "source", ToolParameter.optionalString(
                                        "inventory, main_hand, off_hand, head, chest, legs or feet"),
                                "destination", ToolParameter.optionalString(
                                        "inventory, main_hand, off_hand, head, chest, legs or feet"),
                                "slot", ToolParameter.optionalIntegerWithMinimum(
                                        "Backpack slot index", 0.0D)),
                        false, true, false),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    InventoryAction action;
                    InventoryTarget source;
                    InventoryTarget destination;
                    try {
                        action = InventoryAction.fromId(stringValue(arguments, "action",
                                arguments.containsKey("slot")
                                        ? InventoryAction.READ.id()
                                        : InventoryAction.LIST.id()));
                        source = InventoryTarget.fromId(
                                stringValue(arguments, "source", InventoryTarget.INVENTORY.id()));
                        destination = InventoryTarget.fromId(stringValue(arguments, "destination",
                                action == InventoryAction.EQUIP
                                        ? InventoryTarget.MAIN_HAND.id()
                                        : InventoryTarget.INVENTORY.id()));
                    } catch (IllegalArgumentException exception) {
                        return ToolResult.rejected("invalid_params", exception.getMessage());
                    }
                    int count = arguments.containsKey("count")
                            ? intValue(arguments, "count") : 1;
                    int slot = arguments.containsKey("slot")
                            ? intValue(arguments, "slot") : -1;
                    String item = stringValue(arguments, "item", "");
                    if (action == InventoryAction.EQUIP && !destination.isEquipment()) {
                        return ToolResult.rejected("invalid_params",
                                "equip requires an equipment destination");
                    }
                    if (action == InventoryAction.READ && item.isBlank() && slot < 0) {
                        return ToolResult.rejected("invalid_params",
                                "read requires an item or a slot");
                    }
                    if ((action == InventoryAction.MOVE || action == InventoryAction.EQUIP
                            || action == InventoryAction.DROP)
                            && item.isBlank() && slot < 0) {
                        return ToolResult.rejected("invalid_params",
                                "this action requires an item or a slot");
                    }
                    return backend.inventory(id.get(), new InventoryRequest(action, item, count,
                            source, destination, slot));
                });

        registry.register(new ToolSpec(
                        "companion_set_behavior",
                        "Set local behaviour",
                        "Switches between the stay, follow and guard local behaviours.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "behavior", ToolParameter.requiredString("stay, follow or guard")),
                        false, false, true),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    try {
                        LocalBehavior behavior = LocalBehavior.fromId(String.valueOf(
                                arguments.get("behavior")));
                        return backend.setBehavior(id.get(), behavior);
                    } catch (IllegalArgumentException exception) {
                        return ToolResult.rejected("invalid_params",
                                "behavior must be stay, follow or guard");
                    }
                });

        registry.register(new ToolSpec(
                        "companion_wait_events",
                        "Wait for events",
                        "Long-polls behaviour and operation events after a cursor.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "after_seq", ToolParameter.requiredIntegerWithMinimum(
                                        "Last consumed event sequence", 0.0D),
                                "timeout_seconds", ToolParameter.optionalIntegerWithMinimum(
                                        "Long-poll timeout in seconds", 0.0D),
                                "max_events", ToolParameter.optionalIntegerWithMinimum(
                                        "Maximum events to return", 1.0D)),
                        true, false, false),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    int timeoutSeconds = arguments.containsKey("timeout_seconds")
                            ? intValue(arguments, "timeout_seconds") : 0;
                    int maxEvents = arguments.containsKey("max_events")
                            ? intValue(arguments, "max_events") : 20;
                    return backend.waitEvents(id.get(),
                            intValue(arguments, "after_seq"), timeoutSeconds, maxEvents);
                });

        registry.register(new ToolSpec(
                        "companion_say",
                        "Say something",
                        "Shows a short line to the owner.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "text", ToolParameter.requiredString("Line to show, at most 280 characters")),
                        false, false, false),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    Object raw = arguments.get("text");
                    String text = raw instanceof String value ? value : "";
                    if (text.isBlank()) {
                        return ToolResult.rejected("invalid_params", "text must not be blank");
                    }
                    if (text.length() > 280) {
                        return ToolResult.rejected("invalid_params",
                                "text must be at most 280 characters");
                    }
                    return backend.say(id.get(), text);
                });

        registry.register(new ToolSpec(
                        "companion_stop",
                        "Stop",
                        "Cancels the companion's current operation.",
                        Map.of("companion_id", ToolParameter.requiredString("Companion UUID")),
                        false, true, true),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    return backend.stop(id.get());
                });

        return registry;
    }

    /** The action families this tool surface is allowed to grow. */
    public static java.util.List<ActionKind> coveredActions() {
        return java.util.List.of(ActionKind.values());
    }

    private static String snapshotJson(CompanionSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append("{\"companion_id\":\"").append(snapshot.companionId()).append('"');
        snapshot.ownerId().ifPresent(owner ->
                json.append(",\"owner_id\":\"").append(owner).append('"'));
        json.append(",\"name\":\"").append(escape(snapshot.name())).append('"');
        json.append(",\"behavior\":\"").append(snapshot.behavior().id()).append('"');
        json.append(",\"home_set\":").append(snapshot.homeSet());
        return json.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static Optional<UUID> companionId(Map<String, Object> arguments) {
        Object raw = arguments.get("companion_id");
        if (!(raw instanceof String text)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static int intValue(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static String stringValue(Map<String, Object> arguments, String name,
            String fallback) {
        Object raw = arguments.get(name);
        return raw instanceof String value ? value : fallback;
    }

    private static java.util.List<String> stringList(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (!(raw instanceof java.util.List<?> list)) {
            return java.util.List.of();
        }
        java.util.List<String> values = new java.util.ArrayList<>(list.size());
        for (Object value : list) {
            if (value instanceof String text && !text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }

    private static Map<String, ToolSpec.ToolParameter> ordered(Object... pairs) {
        Map<String, ToolSpec.ToolParameter> parameters = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            parameters.put((String) pairs[index], (ToolSpec.ToolParameter) pairs[index + 1]);
        }
        return parameters;
    }
}
