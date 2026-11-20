package com.touhoulittlemaid.mini.fabric.permission;

import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;
import com.touhoulittlemaid.mini.mcp.PermissionBroker;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * In-game human authorization for destructive actions.
 *
 * <p>Approval is a one-shot ticket tied to a companion and action fingerprint. The
 * prompt is a clickable chat message; only a real player can run the approval command,
 * and only the companion owner or an operator may decide.
 */
public final class CompanionPermissions {

    private static final PermissionBroker BROKER = new PermissionBroker();
    private static final long TTL_MILLIS = 120_000L;

    private CompanionPermissions() {
    }

    public static PermissionBroker.Ticket authorize(CompanionEntity companion, String action) {
        var owner = companion.getOwner();
        if (owner != null && !(owner instanceof ServerPlayer player && player.connection != null)) {
            return new PermissionBroker.Ticket(UUID.randomUUID().toString(),
                    companion.getUUID().toString(), action,
                    PermissionBroker.Decision.DENIED, 0L);
        }

        PermissionBroker.Ticket ticket = BROKER.authorize(
                companion.getUUID().toString(), action, TTL_MILLIS);
        if (ticket.decision() == PermissionBroker.Decision.PENDING
                && owner instanceof ServerPlayer player) {
            player.sendSystemMessage(prompt(companion, ticket));
            CompanionRuntimeState.publish(companion.getUUID(), "permission_required",
                    "{\"permission_id\":\"" + ticket.id() + "\",\"action\":\""
                            + action + "\"}");
        }
        return ticket;
    }

    /** Test seam and command backend; ownership is checked by the command caller. */
    public static boolean decide(String id, boolean allow) {
        PermissionBroker.Ticket ticket = BROKER.decide(id, allow);
        if (ticket.decision() == PermissionBroker.Decision.UNKNOWN) {
            return false;
        }
        try {
            CompanionRuntimeState.publish(UUID.fromString(ticket.companionId()),
                    "permission_resolved",
                    "{\"permission_id\":\"" + id + "\",\"allowed\":" + allow + '}');
        } catch (IllegalArgumentException ignored) {
            // A malformed id should never reach here; the broker only stores UUIDs.
        }
        return true;
    }

    /** Player-facing decision; the player must own the companion or be an operator. */
    public static boolean decide(Player player, String id, boolean allow) {
        PermissionBroker.Ticket ticket = BROKER.status(id);
        if (ticket.decision() == PermissionBroker.Decision.UNKNOWN) {
            return false;
        }
        CompanionEntity companion = findCompanion(player, ticket.companionId());
        if (companion == null) {
            return false;
        }
        boolean operator = player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        if (!companion.isOwner(player) && !operator) {
            return false;
        }
        return decide(id, allow);
    }

    public static PermissionBroker.Ticket status(String id) {
        return BROKER.status(id);
    }

    private static CompanionEntity findCompanion(Player player, String companionId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(companionId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                && serverLevel.getEntityInAnyDimension(uuid) instanceof CompanionEntity companion) {
            return companion;
        }
        return null;
    }

    private static Component prompt(CompanionEntity companion, PermissionBroker.Ticket ticket) {
        Component allow = Component.translatable("chat.tlmm.permission.allow")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.RunCommand(
                                "/tlmm auth allow " + ticket.id())));
        Component deny = Component.translatable("chat.tlmm.permission.deny")
                .withStyle(style -> style.withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent.RunCommand(
                                "/tlmm auth deny " + ticket.id())));
        return Component.translatable("chat.tlmm.permission.request",
                        companion.companionName(), ticket.action())
                .append(" [").append(allow).append("] [").append(deny).append("]");
    }
}
