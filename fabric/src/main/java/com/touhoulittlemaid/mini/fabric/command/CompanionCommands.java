package com.touhoulittlemaid.mini.fabric.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.mcp.CompanionTokenStore;
import com.touhoulittlemaid.mini.mcp.TokenScope;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * M1 player-facing companion lifecycle commands.
 *
 * <p>This is the only Java entry point that creates or removes a companion, so the
 * later action/permission/audit pipeline has a single seam to attach to.
 */
public final class CompanionCommands {

    private CompanionCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) ->
                dispatcher.register(Commands.literal("tlmm")
                        .then(Commands.literal("summon")
                                .executes(context -> summon(context.getSource())))
                        .then(Commands.literal("remove")
                                .executes(context -> remove(context.getSource())))
                        .then(Commands.literal("rename")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> rename(context.getSource(),
                                                StringArgumentType.getString(context, "name")))))
                        .then(Commands.literal("home")
                                .executes(context -> setHome(context.getSource())))
                        .then(Commands.literal("behavior")
                                .then(Commands.argument("behavior", StringArgumentType.word())
                                        .executes(context -> setBehavior(context.getSource(),
                                                StringArgumentType.getString(context, "behavior")))))
                        .then(Commands.literal("auth")
                                .then(Commands.literal("allow")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(context -> decide(context.getSource(),
                                                        StringArgumentType.getString(context, "id"),
                                                        true))))
                                .then(Commands.literal("deny")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(context -> decide(context.getSource(),
                                                        StringArgumentType.getString(context, "id"),
                                                        false)))))
                        .then(Commands.literal("mcp")
                                .then(Commands.literal("token")
                                        .then(Commands.literal("create")
                                                .then(Commands.argument("days",
                                                                IntegerArgumentType.integer(1, 365))
                                                        .executes(context -> createToken(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(
                                                                        context, "days")))))
                                        .then(Commands.literal("list")
                                                .executes(context -> listTokens(context.getSource())))
                                        .then(Commands.literal("revoke")
                                                .then(Commands.argument("id",
                                                                StringArgumentType.word())
                                                        .executes(context -> revokeToken(
                                                                context.getSource(),
                                                                StringArgumentType.getString(
                                                                        context, "id")))))))));
    }

    private static int summon(net.minecraft.commands.CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("tlmm summon must be run by a player"));
            return 0;
        }
        ServerLevel level = player.level();
        CompanionEntity companion = FabricContent.COMPANION.create(level,
                net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (companion == null) {
            source.sendFailure(Component.literal("could not create a companion"));
            return 0;
        }
        BlockPos target = player.blockPosition().relative(player.getDirection());
        companion.setPos(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
        companion.setOwner(player);
        companion.setBehavior(LocalBehavior.FOLLOW);
        level.addFreshEntity(companion);
        source.sendSuccess(() -> Component.literal("Summoned companion"), false);
        return 1;
    }

    private static int remove(net.minecraft.commands.CommandSourceStack source) {
        List<CompanionEntity> companions = ownedCompanions(source);
        if (companions.isEmpty()) {
            source.sendFailure(Component.literal("no owned companion to remove"));
            return 0;
        }
        int removed = 0;
        for (CompanionEntity companion : companions) {
            if (!companion.canBeDeletedInventoryLossless()) {
                source.sendFailure(Component.literal(
                        "companion still holds items; empty its backpack before removing it"));
                continue;
            }
            companion.discard();
            removed++;
        }
        int total = removed;
        source.sendSuccess(() -> Component.literal("Removed " + total + " companion(s)"), false);
        return removed;
    }

    private static int rename(net.minecraft.commands.CommandSourceStack source, String name) {
        List<CompanionEntity> companions = ownedCompanions(source);
        if (companions.isEmpty()) {
            source.sendFailure(Component.literal("no owned companion to rename"));
            return 0;
        }
        for (CompanionEntity companion : companions) {
            companion.setCompanionName(name);
        }
        source.sendSuccess(() -> Component.literal("Renamed companion to " + name), false);
        return companions.size();
    }

    private static int setHome(net.minecraft.commands.CommandSourceStack source) {
        List<CompanionEntity> companions = ownedCompanions(source);
        if (companions.isEmpty()) {
            source.sendFailure(Component.literal("no owned companion to bind"));
            return 0;
        }
        BlockPos position = BlockPos.containing(source.getPosition());
        for (CompanionEntity companion : companions) {
            companion.setHome(position);
        }
        source.sendSuccess(() -> Component.literal("Companion home set to "
                + position.getX() + ", " + position.getY() + ", " + position.getZ()), false);
        return companions.size();
    }

    private static int setBehavior(net.minecraft.commands.CommandSourceStack source, String behaviorId) {
        LocalBehavior behavior;
        try {
            behavior = LocalBehavior.fromId(behaviorId);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("unknown behaviour: " + behaviorId));
            return 0;
        }
        List<CompanionEntity> companions = ownedCompanions(source);
        if (companions.isEmpty()) {
            source.sendFailure(Component.literal("no owned companion to update"));
            return 0;
        }
        for (CompanionEntity companion : companions) {
            companion.setBehavior(behavior);
        }
        source.sendSuccess(() -> Component.literal("Companion behaviour set to " + behavior.id()), false);
        return companions.size();
    }

    private static int decide(net.minecraft.commands.CommandSourceStack source, String id,
            boolean allow) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("tlmm auth must be run by a player"));
            return 0;
        }
        if (!com.touhoulittlemaid.mini.fabric.permission.CompanionPermissions
                .decide(player, id, allow)) {
            source.sendFailure(Component.literal(
                    "unknown, expired or not your permission request"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                allow ? "Permission allowed" : "Permission denied"), false);
        return 1;
    }

    private static int createToken(net.minecraft.commands.CommandSourceStack source, int days) {
        List<CompanionEntity> companions = ownedCompanions(source);
        if (companions.isEmpty()) {
            source.sendFailure(Component.literal("no owned companion to authorize"));
            return 0;
        }
        Set<UUID> ids = companions.stream()
                .map(CompanionEntity::getUUID)
                .collect(java.util.stream.Collectors.toSet());
        CompanionTokenStore.Issued issued = TlmmFabric.tokenStore()
                .issue(ids, TokenScope.FULL, Duration.ofDays(days));
        source.sendSuccess(() -> Component.literal(
                "MCP token (shown once): " + issued.plaintext()), false);
        source.sendSuccess(() -> Component.literal(
                "token_id=" + issued.record().id()), false);
        return 1;
    }

    private static int listTokens(net.minecraft.commands.CommandSourceStack source) {
        List<CompanionTokenStore.Record> tokens = TlmmFabric.tokenStore().list();
        if (tokens.isEmpty()) {
            source.sendSuccess(() -> Component.literal("no active MCP tokens"), false);
            return 0;
        }
        for (CompanionTokenStore.Record record : tokens) {
            source.sendSuccess(() -> Component.literal(
                    record.id() + "  " + record.digestId() + "  expires="
                            + record.expiresAt() + "  companions=" + record.companionIds()),
                    false);
        }
        return tokens.size();
    }

    private static int revokeToken(net.minecraft.commands.CommandSourceStack source, String id) {
        if (!TlmmFabric.tokenStore().revoke(id)) {
            source.sendFailure(Component.literal("unknown token id"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("MCP token revoked"), false);
        return 1;
    }

    private static List<CompanionEntity> ownedCompanions(net.minecraft.commands.CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return List.of();
        }
        return player.level().getEntitiesOfClass(CompanionEntity.class,
                player.getBoundingBox().inflate(64.0D),
                companion -> companion.isOwnedBy(player));
    }
}
