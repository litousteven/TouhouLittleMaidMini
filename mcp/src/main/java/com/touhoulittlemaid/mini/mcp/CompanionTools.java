package com.touhoulittlemaid.mini.mcp;

import com.touhoulittlemaid.mini.core.ActionKind;
import com.touhoulittlemaid.mini.core.CompanionSchedule;
import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.ContextCategory;
import com.touhoulittlemaid.mini.core.ContextQuery;
import com.touhoulittlemaid.mini.core.InteractButton;
import com.touhoulittlemaid.mini.core.InteractHand;
import com.touhoulittlemaid.mini.core.InteractRequest;
import com.touhoulittlemaid.mini.core.InteractTargetKind;
import com.touhoulittlemaid.mini.core.InventoryAction;
import com.touhoulittlemaid.mini.core.InventoryRequest;
import com.touhoulittlemaid.mini.core.InventoryTarget;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.MoveMode;
import com.touhoulittlemaid.mini.core.MoveRequest;
import com.touhoulittlemaid.mini.core.MoveTargetKind;
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

        /** Move toward or away from a position, block or entity. */
        ToolResult move(UUID companionId, MoveRequest request);

        /** Perform one player-like block, entity or air interaction. */
        ToolResult interact(UUID companionId, InteractRequest request);

        /** Bounded observation of one context category. */
        ToolResult context(UUID companionId, ContextQuery query);

        /** Read or change the companion inventory. */
        ToolResult inventory(UUID companionId, InventoryRequest request);

        /** Change the local behaviour and, when given, its work schedule. */
        ToolResult setBehavior(UUID companionId, LocalBehavior behavior,
                Optional<CompanionSchedule> schedule);

        /** Stop the current operation. */
        ToolResult stop(UUID companionId);

        /** Long-poll events after a cursor. */
        ToolResult waitEvents(UUID companionId, long afterSequence, int timeoutSeconds, int maxEvents);

        /** Say a short line to the owner. */
        ToolResult say(UUID companionId, String text);
    }

    private static final ThreadLocal<java.util.Set<UUID>> ALLOWED_COMPANIONS =
            ThreadLocal.withInitial(java.util.Set::of);

    private CompanionTools() {
    }

    /** Runs one tool call with the token's companion scope, restoring the previous one. */
    public static <T> T withAllowedCompanions(java.util.Set<UUID> companionIds,
            java.util.function.Supplier<T> action) {
        java.util.Set<UUID> previous = ALLOWED_COMPANIONS.get();
        ALLOWED_COMPANIONS.set(java.util.Set.copyOf(companionIds));
        try {
            return action.get();
        } finally {
            ALLOWED_COMPANIONS.set(previous);
        }
    }

    private static boolean allowsCompanion(UUID companionId) {
        java.util.Set<UUID> allowed = ALLOWED_COMPANIONS.get();
        return allowed.isEmpty() || allowed.contains(companionId);
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
                    var companions = backend.listCompanions().stream()
                            .filter(snapshot -> allowsCompanion(snapshot.companionId()))
                            .toList();
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
                    if (!allowsCompanion(id.get())) {
                        return ToolResult.rejected("scope_denied",
                                "This token may not control this companion");
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
                        "Walks the companion toward or away from a position, block or entity. "
                                + "Long paths run as a body operation and do not block the caller.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "target_kind", ToolParameter.optionalString(
                                        "position, block or entity"),
                                "x", ToolParameter.optionalInteger("Target block X"),
                                "y", ToolParameter.optionalInteger("Target block Y"),
                                "z", ToolParameter.optionalInteger("Target block Z"),
                                "entity_id", ToolParameter.optionalString("Target entity UUID"),
                                "mode", ToolParameter.optionalString("goto or away"),
                                "distance", ToolParameter.optionalNumber(
                                        "Stop distance in blocks"),
                                "replace", ToolParameter.optionalBoolean(
                                        "Replace the current body operation")),
                        false, false, false),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    if (!allowsCompanion(id.get())) {
                        return ToolResult.rejected("scope_denied",
                                "This token may not control this companion");
                    }
                    MoveTargetKind targetKind;
                    MoveMode mode;
                    try {
                        String entityId = stringValue(arguments, "entity_id", "");
                        targetKind = MoveTargetKind.fromId(stringValue(arguments, "target_kind",
                                entityId.isBlank()
                                        ? MoveTargetKind.BLOCK.id()
                                        : MoveTargetKind.ENTITY.id()));
                        mode = MoveMode.fromId(
                                stringValue(arguments, "mode", MoveMode.GOTO.id()));
                    } catch (IllegalArgumentException exception) {
                        return ToolResult.rejected("invalid_params", exception.getMessage());
                    }
                    String entityId = stringValue(arguments, "entity_id", "");
                    if (targetKind == MoveTargetKind.ENTITY && entityId.isBlank()) {
                        return ToolResult.rejected("invalid_params",
                                "entity targets require entity_id");
                    }
                    if (targetKind != MoveTargetKind.ENTITY
                            && !(arguments.containsKey("x") && arguments.containsKey("y")
                            && arguments.containsKey("z"))) {
                        return ToolResult.rejected("invalid_params",
                                "x, y and z are required for position and block targets");
                    }
                    return backend.move(id.get(), new MoveRequest(targetKind,
                            intValue(arguments, "x"), intValue(arguments, "y"),
                            intValue(arguments, "z"), entityId, mode,
                            doubleValue(arguments, "distance", 1.0D),
                            booleanValue(arguments, "replace", true)));
                });

        registry.register(new ToolSpec(
                        "companion_interact",
                        "Interact",
                        "Performs one player-like click on a block or a dropped item entity. "
                                + "Crops are harvested when mature and replanted from carried "
                                + "seeds; dropped items are inserted into the backpack.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "target_kind", ToolParameter.optionalString("block, entity or air"),
                                "x", ToolParameter.optionalInteger("Target block X"),
                                "y", ToolParameter.optionalInteger("Target block Y"),
                                "z", ToolParameter.optionalInteger("Target block Z"),
                                "entity_id", ToolParameter.optionalString("Target entity UUID"),
                                "button", ToolParameter.optionalString("right or left"),
                                "hand", ToolParameter.optionalString("main or off"),
                                "sneak", ToolParameter.optionalBoolean("Sneak while interacting"),
                                "replace", ToolParameter.optionalBoolean(
                                        "Replace the current body operation")),
                        false, false, false),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    if (!allowsCompanion(id.get())) {
                        return ToolResult.rejected("scope_denied",
                                "This token may not control this companion");
                    }
                    InteractTargetKind targetKind;
                    InteractButton button;
                    InteractHand hand;
                    String entityId = stringValue(arguments, "entity_id", "");
                    try {
                        targetKind = InteractTargetKind.fromId(stringValue(arguments,
                                "target_kind", entityId.isBlank()
                                        ? InteractTargetKind.BLOCK.id()
                                        : InteractTargetKind.ENTITY.id()));
                        button = InteractButton.fromId(
                                stringValue(arguments, "button", InteractButton.RIGHT.id()));
                        hand = InteractHand.fromId(
                                stringValue(arguments, "hand", InteractHand.MAIN.id()));
                    } catch (IllegalArgumentException exception) {
                        return ToolResult.rejected("invalid_params", exception.getMessage());
                    }
                    if (targetKind == InteractTargetKind.ENTITY && entityId.isBlank()) {
                        return ToolResult.rejected("invalid_params",
                                "entity targets require entity_id");
                    }
                    if (targetKind != InteractTargetKind.ENTITY
                            && !(arguments.containsKey("x") && arguments.containsKey("y")
                            && arguments.containsKey("z"))) {
                        return ToolResult.rejected("invalid_params",
                                "x, y and z are required for block and air targets");
                    }
                    return backend.interact(id.get(), new InteractRequest(targetKind,
                            intValue(arguments, "x"), intValue(arguments, "y"),
                            intValue(arguments, "z"), entityId, button, hand,
                            booleanValue(arguments, "sneak", false),
                            booleanValue(arguments, "replace", true)));
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
                    if (!allowsCompanion(id.get())) {
                        return ToolResult.rejected("scope_denied",
                                "This token may not control this companion");
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
                    if (!allowsCompanion(id.get())) {
                        return ToolResult.rejected("scope_denied",
                                "This token may not control this companion");
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
                        "Switches between the stay, follow and guard local behaviours and can "
                                + "limit the local work schedule to day or night.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "behavior", ToolParameter.requiredString("stay, follow or guard"),
                                "schedule", ToolParameter.optionalString("all, day or night")),
                        false, false, true),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    if (!allowsCompanion(id.get())) {
                        return ToolResult.rejected("scope_denied",
                                "This token may not control this companion");
                    }
                    LocalBehavior behavior;
                    Optional<CompanionSchedule> schedule;
                    try {
                        behavior = LocalBehavior.fromId(String.valueOf(
                                arguments.get("behavior")));
                        schedule = arguments.containsKey("schedule")
                                ? Optional.of(CompanionSchedule.fromId(
                                        String.valueOf(arguments.get("schedule"))))
                                : Optional.empty();
                    } catch (IllegalArgumentException exception) {
                        return ToolResult.rejected("invalid_params",
                                "behavior must be stay, follow or guard and schedule must be "
                                        + "all, day or night");
                    }
                    return backend.setBehavior(id.get(), behavior, schedule);
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
                    if (!allowsCompanion(id.get())) {
                        return ToolResult.rejected("scope_denied",
                                "This token may not control this companion");
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
                    if (!allowsCompanion(id.get())) {
                        return ToolResult.rejected("scope_denied",
                                "This token may not control this companion");
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
                    if (!allowsCompanion(id.get())) {
                        return ToolResult.rejected("scope_denied",
                                "This token may not control this companion");
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
        json.append(",\"schedule\":\"").append(snapshot.schedule().id()).append('"');
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

    private static double doubleValue(Map<String, Object> arguments, String name,
            double fallback) {
        Object raw = arguments.get(name);
        return raw instanceof Number number ? number.doubleValue() : fallback;
    }

    private static boolean booleanValue(Map<String, Object> arguments, String name,
            boolean fallback) {
        Object raw = arguments.get(name);
        return raw instanceof Boolean value ? value : fallback;
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
