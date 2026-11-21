package com.touhoulittlemaid.mini.core.semantic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Remembers what was already asked, and how much asking is still allowed.
 *
 * <p>Three separate promises: the same content is not paid for twice within a week, no more
 * than one batch leaves per minute, and a session or a day cannot run away with the budget.
 * The ledger is persisted next to the knowledge files so a restart does not reset the day.
 */
public final class ScanLedger {

    public static final Duration ASKED_TTL = Duration.ofDays(7);
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(60);
    public static final int MAX_PER_SESSION = 4;
    public static final int MAX_PER_DAY = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "ledger.json";

    public record Quota(boolean allowed, String code, int remainingSession, int remainingToday) {

        public static Quota ok(int session, int today) {
            return new Quota(true, KnowledgeCodes.OK, session, today);
        }

        public static Quota deny(String code) {
            return new Quota(false, code, 0, 0);
        }
    }

    private final Map<String, Instant> asked = new LinkedHashMap<>();
    private String day = "";
    private int batchesToday;
    private int batchesThisSession;
    private String session = "";
    private Instant lastBatchAt = Instant.EPOCH;

    public static ScanLedger load(Path directory) {
        ScanLedger ledger = new ScanLedger();
        if (directory == null) {
            return ledger;
        }
        Path file = directory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return ledger;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return ledger;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("day") && root.get("day").isJsonPrimitive()) {
                ledger.day = root.get("day").getAsString();
            }
            ledger.batchesToday = root.has("batches_today")
                    ? root.get("batches_today").getAsInt() : 0;
            if (root.has("last_batch_at") && root.get("last_batch_at").isJsonPrimitive()) {
                try {
                    ledger.lastBatchAt = Instant.parse(root.get("last_batch_at").getAsString());
                } catch (RuntimeException ignored) {
                    ledger.lastBatchAt = Instant.EPOCH;
                }
            }
            JsonElement askedElement = root.get("asked");
            if (askedElement != null && askedElement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> row : askedElement.getAsJsonObject().entrySet()) {
                    if (!row.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    try {
                        ledger.asked.put(row.getKey(), Instant.parse(row.getValue().getAsString()));
                    } catch (RuntimeException ignored) {
                        // A corrupt timestamp only costs one extra question.
                    }
                }
            }
        } catch (IOException | JsonParseException exception) {
            return new ScanLedger();
        }
        return ledger;
    }

    public void save(Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        Files.createDirectories(directory);
        JsonObject root = new JsonObject();
        root.addProperty("day", day);
        root.addProperty("batches_today", batchesToday);
        root.addProperty("last_batch_at", lastBatchAt.toString());
        JsonObject askedObject = new JsonObject();
        new TreeMap<>(asked).forEach((key, value) -> askedObject.addProperty(key, value.toString()));
        root.add("asked", askedObject);

        Path file = directory.resolve(FILE_NAME);
        Path temporary = directory.resolve(FILE_NAME + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Starts a new login; the per-session budget resets, the daily one does not. */
    public void beginSession(String key) {
        String next = key == null ? "" : key;
        if (!next.equals(session)) {
            session = next;
            batchesThisSession = 0;
        }
    }

    public boolean recentlyAsked(SemanticKind kind, String id, Instant now) {
        Instant askedAt = asked.get(KnowledgeCandidate.key(kind, id));
        return askedAt != null && askedAt.plus(ASKED_TTL).isAfter(now);
    }

    public void markAsked(SemanticKind kind, String id, Instant now) {
        if (id == null || id.isBlank()) {
            return;
        }
        asked.put(KnowledgeCandidate.key(kind, id), now);
    }

    public Quota quota(Instant now) {
        rollDay(now);
        if (lastBatchAt.plus(MIN_INTERVAL).isAfter(now)) {
            return Quota.deny(KnowledgeCodes.RATE_LIMITED);
        }
        if (batchesThisSession >= MAX_PER_SESSION) {
            return Quota.deny(KnowledgeCodes.SESSION_QUOTA);
        }
        if (batchesToday >= MAX_PER_DAY) {
            return Quota.deny(KnowledgeCodes.DAILY_QUOTA);
        }
        return Quota.ok(MAX_PER_SESSION - batchesThisSession, MAX_PER_DAY - batchesToday);
    }

    public void recordBatch(Instant now) {
        rollDay(now);
        batchesThisSession++;
        batchesToday++;
        lastBatchAt = now;
    }

    /** Drops expired memory; returns how many keys were forgotten. */
    public int prune(Instant now) {
        int before = asked.size();
        asked.entrySet().removeIf(row -> !row.getValue().plus(ASKED_TTL).isAfter(now));
        return before - asked.size();
    }

    public int askedCount() {
        return asked.size();
    }

    public int batchesToday(Instant now) {
        rollDay(now);
        return batchesToday;
    }

    public void clear() {
        asked.clear();
        batchesToday = 0;
        batchesThisSession = 0;
        lastBatchAt = Instant.EPOCH;
        day = "";
    }

    public Map<String, Instant> snapshot() {
        return Map.copyOf(asked);
    }

    private void rollDay(Instant now) {
        String today = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        if (!today.equals(day)) {
            day = today;
            batchesToday = 0;
        }
    }
}
