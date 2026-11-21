package com.touhoulittlemaid.mini.fabric.semantic;

import com.touhoulittlemaid.mini.core.semantic.KnowledgeBatch;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeBatchJson;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeCandidate;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeCodes;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeExporter;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeIngest;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeStore;
import com.touhoulittlemaid.mini.core.semantic.ScanLedger;
import com.touhoulittlemaid.mini.core.semantic.SemanticEntry;
import com.touhoulittlemaid.mini.core.semantic.SemanticSource;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.phys.Vec3;

/**
 * The annotation loop on the game side: scan, prepare a batch, apply a proposal, review it.
 *
 * <p>The model itself is not called here. The batch is handed to whoever can call a model —
 * the client brain from {@code M6.4} on, or an operator through {@code /tlmm knowledge
 * ingest} today — and comes back as JSON that this class validates before anything applies.
 */
public final class KnowledgeService {

    private static final FabricRealFacts FACTS = new FabricRealFacts();

    private static volatile Path directory;
    private static volatile ScanLedger ledger = new ScanLedger();
    private static volatile KnowledgeBatch lastBatch;
    /** Ids a plan or goal just failed on; they jump the queue next time a batch is built. */
    private static final java.util.Set<String> urgent =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private KnowledgeService() {
    }

    public record Prepared(KnowledgeBatch batch, String code,
            UnknownContentScanner.Result scan) {

        public boolean ok() {
            return batch != null && !batch.isEmpty() && KnowledgeCodes.OK.equals(code);
        }

        public String describe() {
            if (batch != null && !batch.isEmpty()) {
                return "batch " + batch.id() + " with " + batch.size() + " item(s)";
            }
            return switch (code) {
                case "nothing_new" -> "nothing new to ask about";
                default -> "batch refused: " + code;
            };
        }
    }

    public static void load(Path knowledgeDirectory) {
        directory = knowledgeDirectory;
        ledger = ScanLedger.load(knowledgeDirectory);
        lastBatch = null;
        SemanticService.load(knowledgeDirectory);
        TlmmFabric.LOGGER.info("[knowledge] ledger has {} asked id(s)", ledger.askedCount());
    }

    public static void reset() {
        directory = null;
        ledger = new ScanLedger();
        lastBatch = null;
        urgent.clear();
        SemanticService.reset();
    }

    /**
     * Queues one id for the next batch.
     *
     * <p>Called when a plan fails with {@code unknown_category}: the player is standing in
     * front of exactly the content the annotation should explain, so it goes first instead of
     * waiting for the next world scan to reach it.
     */
    public static void noteUnknown(String id, com.touhoulittlemaid.mini.core.semantic
            .SemanticKind kind) {
        if (id == null || id.isBlank() || kind == null) {
            return;
        }
        urgent.add(KnowledgeCandidate.key(kind, id));
    }

    public static int urgentCount() {
        return urgent.size();
    }

    public static void clearUrgent() {
        urgent.clear();
    }

    public static Path directory() {
        return directory;
    }

    public static KnowledgeStore store() {
        return SemanticService.store();
    }

    public static ScanLedger ledger() {
        return ledger;
    }

    public static KnowledgeBatch lastBatch() {
        return lastBatch;
    }

    /** Scans the world, then turns the fresh candidates into a batch if the budget allows. */
    public static Prepared prepare(ServerLevel level, Vec3 center, List<Container> inventories,
            Instant now) {
        UnknownContentScanner.Result scan = UnknownContentScanner.scan(level, center, inventories);
        ScanLedger.Quota quota = ledger.quota(now);
        if (!quota.allowed()) {
            return new Prepared(null, quota.code(), scan);
        }
        List<KnowledgeCandidate> fresh = new ArrayList<>();
        List<KnowledgeCandidate> first = new ArrayList<>();
        for (KnowledgeCandidate candidate : scan.candidates()) {
            if (ledger.recentlyAsked(candidate.kind(), candidate.id(), now)) {
                continue;
            }
            if (urgent.contains(candidate.key())) {
                first.add(candidate);
            } else {
                fresh.add(candidate);
            }
        }
        first.addAll(fresh);
        fresh = first;
        if (fresh.isEmpty()) {
            return new Prepared(null, "nothing_new", scan);
        }
        KnowledgeBatch batch = KnowledgeBatch.of(fresh);
        for (KnowledgeCandidate candidate : batch.items()) {
            ledger.markAsked(candidate.kind(), candidate.id(), now);
        }
        ledger.recordBatch(now);
        lastBatch = batch;
        saveQuietly();
        return new Prepared(batch, KnowledgeCodes.OK, scan);
    }

    public static KnowledgeIngest.IngestReport ingest(String responseJson) {
        return ingest(lastBatch, responseJson);
    }

    /**
     * Applies an answer to a specific batch.
     *
     * <p>Passing the batch in is what keeps two simultaneous rounds from crossing: a shared
     * "last batch" field is enough for one player, not for a server running several.
     */
    public static KnowledgeIngest.IngestReport ingest(KnowledgeBatch batch, String responseJson) {
        if (batch == null) {
            return KnowledgeIngest.IngestReport.empty();
        }
        KnowledgeBatchJson.Response response = KnowledgeBatchJson.parseResponse(responseJson);
        KnowledgeIngest.IngestReport report = KnowledgeIngest.ingest(batch, response, FACTS,
                store());
        saveQuietly();
        return report;
    }

    /** The player said yes: a pending row becomes a user-owned row with full confidence. */
    public static boolean approve(String id) {
        SemanticEntry pending = store().pendingById(id).orElse(null);
        if (pending == null) {
            return false;
        }
        store().put(new SemanticEntry(pending.id(), pending.kind(), pending.tags(),
                SemanticSource.USER, 1.0D, pending.batch(), "approved by player"));
        saveQuietly();
        return true;
    }

    public static boolean deny(String id) {
        SemanticEntry pending = store().pendingById(id).orElse(null);
        if (pending == null) {
            return false;
        }
        store().remove(pending.id(), pending.kind());
        saveQuietly();
        return true;
    }

    /** Removes one row wherever it lives; used by the UI and by tests that seed state. */
    public static boolean forgetEntry(String id) {
        boolean removed = store().removeAny(id);
        if (removed) {
            saveQuietly();
        }
        return removed;
    }

    public static int undo(String batchId) {
        int removed = store().removeBatch(batchId);
        if (removed > 0) {
            saveQuietly();
        }
        return removed;
    }

    public static boolean forget(String namespace) {
        int removed = store().removeNamespace(namespace);
        Path dir = directory;
        if (dir != null) {
            try {
                Files.deleteIfExists(dir.resolve(namespace + ".json"));
            } catch (IOException exception) {
                TlmmFabric.LOGGER.warn("[knowledge] cannot delete {}: {}", namespace,
                        exception.getMessage());
            }
        }
        if (removed > 0) {
            saveQuietly();
        }
        return removed > 0;
    }

    public static KnowledgeExporter.ExportReport export(int packFormat) {
        return KnowledgeExporter.export(directory, store().entries(), packFormat);
    }

    public static void save() throws IOException {
        if (directory == null) {
            return;
        }
        store().save(directory);
        ledger.save(directory);
    }

    private static void saveQuietly() {
        try {
            save();
        } catch (IOException exception) {
            TlmmFabric.LOGGER.warn("[knowledge] cannot save: {}", exception.getMessage());
        }
    }
}
