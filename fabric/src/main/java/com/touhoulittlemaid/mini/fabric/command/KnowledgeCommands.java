package com.touhoulittlemaid.mini.fabric.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeCodes;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeIngest;
import com.touhoulittlemaid.mini.core.semantic.SemanticEntry;
import com.touhoulittlemaid.mini.core.semantic.SemanticTag;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.network.KnowledgeNetworking;
import com.touhoulittlemaid.mini.fabric.semantic.KnowledgeService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;

/**
 * {@code /tlmm knowledge ...}: the operator-facing half of the annotation loop.
 *
 * <p>Scanning and applying a proposal are operator actions because they change server-wide
 * classification. Applying a proposal is also the only way to test the pipeline without a
 * client brain: the JSON is exactly what the model is asked to return.
 */
public final class KnowledgeCommands {

    private KnowledgeCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("knowledge")
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("scan")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> scan(context.getSource())))
                .then(Commands.literal("ingest")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("json", StringArgumentType.greedyString())
                                .executes(context -> ingest(context.getSource(),
                                        StringArgumentType.getString(context, "json")))))
                .then(Commands.literal("approve")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> approve(context.getSource(),
                                        StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("deny")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> deny(context.getSource(),
                                        StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("undo")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("batch", StringArgumentType.word())
                                .executes(context -> undo(context.getSource(),
                                        StringArgumentType.getString(context, "batch")))))
                .then(Commands.literal("forget")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(context -> forget(context.getSource(),
                                        StringArgumentType.getString(context, "namespace")))))
                .then(Commands.literal("export")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> export(context.getSource())))
                .then(Commands.literal("ui")
                        .executes(context -> ui(context.getSource())));
    }

    private static int list(CommandSourceStack source) {
        var store = KnowledgeService.store();
        source.sendSuccess(() -> Component.translatableWithFallback(
                "command.tlmm.knowledge.list", "accepted %s   pending %s   batches today %s",
                store.size(), store.pendingEntries().size(),
                KnowledgeService.ledger().batchesToday(Instant.now())), false);
        List<SemanticEntry> pending = store.pendingEntries().stream()
                .sorted(java.util.Comparator.comparing(SemanticEntry::id))
                .limit(10)
                .toList();
        if (pending.isEmpty()) {
            source.sendSuccess(() -> Component.translatableWithFallback(
                    "command.tlmm.knowledge.empty", "nothing pending"), false);
            return 0;
        }
        for (SemanticEntry entry : pending) {
            String tags = entry.tags().stream().map(SemanticTag::id).sorted()
                    .collect(Collectors.joining(","));
            source.sendSuccess(() -> Component.literal(entry.id() + " [" + entry.kind()
                    .name().toLowerCase(java.util.Locale.ROOT) + "] " + tags + " "
                    + String.format(java.util.Locale.ROOT, "%.2f", entry.confidence()) + " "
                    + entry.reason()), false);
        }
        return pending.size();
    }

    private static int scan(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatableWithFallback(
                    "command.tlmm.knowledge.player", "This command needs a player"));
            return 0;
        }
        List<Container> inventories = new ArrayList<>();
        inventories.add(player.getInventory());
        for (CompanionEntity companion : companions(player)) {
            inventories.add(companion.backpack());
        }
        var prepared = KnowledgeService.prepare((ServerLevel) player.level(), player.position(),
                inventories, Instant.now());
        source.sendSuccess(() -> Component.translatableWithFallback(
                "command.tlmm.knowledge.scan",
                "scanned %s positions, %s unknown candidate(s), %s container(s)",
                prepared.scan().positions(), prepared.scan().candidates().size(),
                prepared.scan().containers()), false);
        if (prepared.ok()) {
            source.sendSuccess(() -> Component.translatableWithFallback(
                    "command.tlmm.knowledge.batch", "%s; send a model answer with %s",
                    prepared.describe(), "/tlmm knowledge ingest <json>"), false);
        } else if (!"nothing_new".equals(prepared.code())) {
            source.sendFailure(Component.literal(prepared.describe()));
        } else {
            source.sendSuccess(() -> Component.literal(prepared.describe()), false);
        }
        KnowledgeNetworking.sendView(player);
        return prepared.ok() ? 1 : 0;
    }

    private static int ingest(CommandSourceStack source, String json) {
        if (KnowledgeService.lastBatch() == null) {
            source.sendFailure(Component.translatableWithFallback(
                    "command.tlmm.knowledge.ingest.none",
                    "no batch prepared; run /tlmm knowledge scan first"));
            return 0;
        }
        KnowledgeIngest.IngestReport report = KnowledgeService.ingest(json);
        source.sendSuccess(() -> Component.translatableWithFallback(
                "command.tlmm.knowledge.ingest",
                "batch %s: %s accepted, %s pending, %s rejected %s",
                report.batch(), report.accepted(), report.pending(), report.rejected(),
                report.codes().isEmpty() ? "" : report.codes()), false);
        if (source.getPlayer() != null) {
            KnowledgeNetworking.sendView(source.getPlayer());
        }
        return report.accepted();
    }

    private static int approve(CommandSourceStack source, String id) {
        if (!KnowledgeService.approve(id)) {
            source.sendFailure(Component.translatableWithFallback(
                    "command.tlmm.knowledge.unknown", "%s is not pending", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatableWithFallback(
                "command.tlmm.knowledge.approved", "approved %s", id), true);
        if (source.getPlayer() != null) {
            KnowledgeNetworking.sendView(source.getPlayer());
        }
        return 1;
    }

    private static int deny(CommandSourceStack source, String id) {
        if (!KnowledgeService.deny(id)) {
            source.sendFailure(Component.translatableWithFallback(
                    "command.tlmm.knowledge.unknown", "%s is not pending", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatableWithFallback(
                "command.tlmm.knowledge.denied", "denied %s", id), true);
        if (source.getPlayer() != null) {
            KnowledgeNetworking.sendView(source.getPlayer());
        }
        return 1;
    }

    private static int undo(CommandSourceStack source, String batch) {
        int removed = KnowledgeService.undo(batch);
        source.sendSuccess(() -> Component.translatableWithFallback(
                "command.tlmm.knowledge.undone", "removed %s row(s) from batch %s",
                removed, batch), true);
        if (source.getPlayer() != null) {
            KnowledgeNetworking.sendView(source.getPlayer());
        }
        return removed;
    }

    private static int forget(CommandSourceStack source, String namespace) {
        boolean forgotten = KnowledgeService.forget(namespace);
        source.sendSuccess(() -> Component.translatableWithFallback(
                "command.tlmm.knowledge.forgotten", "forgot namespace %s", namespace), true);
        if (source.getPlayer() != null) {
            KnowledgeNetworking.sendView(source.getPlayer());
        }
        return forgotten ? 1 : 0;
    }

    private static int export(CommandSourceStack source) {
        int packFormat = net.minecraft.SharedConstants.getCurrentVersion()
                .packVersion(net.minecraft.server.packs.PackType.SERVER_DATA).major();
        var report = KnowledgeService.export(packFormat);
        source.sendSuccess(() -> Component.translatableWithFallback(
                "command.tlmm.knowledge.exported", "exported %s to %s",
                report.summary(), report.root()), false);
        if (!report.problems().isEmpty()) {
            source.sendFailure(Component.literal(String.join("; ", report.problems())));
        }
        return report.tags();
    }

    private static int ui(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatableWithFallback(
                    "command.tlmm.knowledge.player", "This command needs a player"));
            return 0;
        }
        KnowledgeNetworking.sendView(player, true);
        return 1;
    }

    private static List<CompanionEntity> companions(ServerPlayer player) {
        return player.level().getEntitiesOfClass(CompanionEntity.class,
                player.getBoundingBox().inflate(64.0D),
                companion -> companion.isOwnedBy(player));
    }
}
