package com.touhoulittlemaid.mini.mcp;

import com.touhoulittlemaid.mini.core.ActionKind;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What a bearer token is allowed to do.
 *
 * <p>A read-only token may observe but never touch the body. Scopes are checked before a
 * handler runs, so a refused action never reaches the world.
 */
public record TokenScope(Set<ActionKind> allowedActions, boolean canInteractWithWorld) {

    public static final TokenScope READ_ONLY =
            new TokenScope(EnumSet.of(ActionKind.OBSERVE), false);
    public static final TokenScope FULL = new TokenScope(EnumSet.allOf(ActionKind.class), true);

    public TokenScope {
        Objects.requireNonNull(allowedActions, "allowedActions");
        allowedActions = Set.copyOf(allowedActions);
    }

    public boolean allows(ActionKind kind) {
        return allowedActions.contains(kind);
    }

    /** The action family a tool name belongs to. */
    public static ActionKind actionOf(String toolName) {
        return switch (toolName) {
            case "companion_list", "companion_state", "companion_context",
                    "companion_inventory", "companion_wait_events" -> ActionKind.OBSERVE;
            case "companion_move", "companion_set_behavior" -> ActionKind.MOVE;
            case "companion_interact" -> ActionKind.INTERACT;
            case "companion_say" -> ActionKind.WAIT;
            case "companion_stop" -> ActionKind.STOP;
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }
}
