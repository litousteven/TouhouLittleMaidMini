package com.touhoulittlemaid.mini.mcp;

import com.touhoulittlemaid.mini.core.ActionKind;
import com.touhoulittlemaid.mini.core.CompanionSnapshot;
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

        /** Read one inventory slot. */
        ToolResult inventory(UUID companionId, int slot);

        /** Change the local behaviour. */
        ToolResult setBehavior(UUID companionId, LocalBehavior behavior);

        /** Stop the current operation. */
        ToolResult stop(UUID companionId);

        /** Wait for the given number of ticks. */
        ToolResult wait(UUID companionId, int ticks);
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
                        "companion_inventory",
                        "Read an inventory slot",
                        "Reads one backpack slot of a companion.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "slot", ToolParameter.requiredInteger("Backpack slot index")),
                        true, false, true),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    return backend.inventory(id.get(), intValue(arguments, "slot"));
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
                        "companion_wait",
                        "Wait",
                        "Waits for the given number of server ticks.",
                        ordered(
                                "companion_id", ToolParameter.requiredString("Companion UUID"),
                                "ticks", ToolParameter.requiredIntegerWithMinimum("Ticks to wait", 1.0D)),
                        false, false, false),
                arguments -> {
                    Optional<UUID> id = companionId(arguments);
                    if (id.isEmpty()) {
                        return ToolResult.rejected("invalid_params", "companion_id must be a UUID");
                    }
                    return backend.wait(id.get(), intValue(arguments, "ticks"));
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

    private static Map<String, ToolSpec.ToolParameter> ordered(Object... pairs) {
        Map<String, ToolSpec.ToolParameter> parameters = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            parameters.put((String) pairs[index], (ToolSpec.ToolParameter) pairs[index + 1]);
        }
        return parameters;
    }
}
