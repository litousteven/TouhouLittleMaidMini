package com.touhoulittlemaid.mini.fabric.network;

import com.touhoulittlemaid.mini.core.semantic.KnowledgeStore;
import com.touhoulittlemaid.mini.core.semantic.SemanticEntry;
import com.touhoulittlemaid.mini.core.semantic.SemanticTag;
import com.touhoulittlemaid.mini.fabric.semantic.KnowledgeService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Registers and sends the knowledge review view. */
public final class KnowledgeNetworking {

    private KnowledgeNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(KnowledgeViewPayload.TYPE,
                KnowledgeViewPayload.STREAM_CODEC);
    }

    /** Pushes the current state to one player; a client without the channel is skipped. */
    public static void sendView(ServerPlayer player) {
        sendView(player, false);
    }

    public static void sendView(ServerPlayer player, boolean open) {
        if (player == null || !ServerPlayNetworking.canSend(player, KnowledgeViewPayload.TYPE)) {
            return;
        }
        KnowledgeStore store = KnowledgeService.store();
        List<KnowledgeViewPayload.Row> rows = store.pendingEntries().stream()
                .sorted(Comparator.comparing(SemanticEntry::id))
                .limit(KnowledgeViewPayload.MAX_ROWS)
                .map(KnowledgeNetworking::row)
                .collect(Collectors.toCollection(ArrayList::new));
        String batch = KnowledgeService.lastBatch() == null ? "" : KnowledgeService.lastBatch().id();
        ServerPlayNetworking.send(player, new KnowledgeViewPayload(rows, store.size(),
                store.pendingEntries().size(),
                KnowledgeService.ledger().batchesToday(Instant.now()), batch, open));
    }

    private static KnowledgeViewPayload.Row row(SemanticEntry entry) {
        Set<SemanticTag> tags = entry.tags();
        String tagLine = tags.stream().map(SemanticTag::id).sorted()
                .collect(Collectors.joining(","));
        return new KnowledgeViewPayload.Row(entry.id(),
                entry.kind().name().toLowerCase(java.util.Locale.ROOT), tagLine,
                entry.confidence(), entry.reason());
    }
}
