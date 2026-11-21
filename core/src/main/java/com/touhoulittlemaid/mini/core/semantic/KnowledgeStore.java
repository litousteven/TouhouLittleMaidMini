package com.touhoulittlemaid.mini.core.semantic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * In-memory knowledge layer plus its on-disk round trip.
 *
 * <p>Accepted entries are what the resolver may use; pending entries are proposals the
 * player has not confirmed and never influence behaviour. Batch ids survive the round trip
 * so a whole model run can be rolled back later.
 */
public final class KnowledgeStore {

    public record Key(SemanticKind kind, String id) {

        public static Key of(SemanticKind kind, String id) {
            return new Key(kind, id);
        }
    }

    public record LoadReport(int accepted, int pending, int rejected,
            Map<String, Integer> codes, List<String> problems) {

        public static LoadReport empty() {
            return new LoadReport(0, 0, 0, Map.of(), List.of());
        }
    }

    private final Map<Key, SemanticEntry> accepted = new LinkedHashMap<>();
    private final Map<Key, SemanticEntry> pending = new LinkedHashMap<>();

    public void put(SemanticEntry entry) {
        accepted.put(Key.of(entry.kind(), entry.id()), entry);
        pending.remove(Key.of(entry.kind(), entry.id()));
    }

    public void putPending(SemanticEntry entry) {
        pending.put(Key.of(entry.kind(), entry.id()), entry);
    }

    public Optional<SemanticEntry> lookup(String id, SemanticKind kind, SemanticSource source) {
        SemanticEntry entry = accepted.get(Key.of(kind, id));
        return entry != null && entry.source() == source ? Optional.of(entry) : Optional.empty();
    }

    public Optional<SemanticEntry> any(String id, SemanticKind kind) {
        return Optional.ofNullable(accepted.get(Key.of(kind, id)));
    }

    public Optional<SemanticEntry> pendingEntry(String id, SemanticKind kind) {
        return Optional.ofNullable(pending.get(Key.of(kind, id)));
    }

    public boolean hasUser(String id, SemanticKind kind) {
        return lookup(id, kind, SemanticSource.USER).isPresent();
    }

    public boolean remove(String id, SemanticKind kind) {
        boolean removed = accepted.remove(Key.of(kind, id)) != null;
        removed |= pending.remove(Key.of(kind, id)) != null;
        return removed;
    }

    /** Rolls back every entry from one model batch; returns how many lines were removed. */
    public int removeBatch(String batch) {
        if (batch == null || batch.isBlank()) {
            return 0;
        }
        int removed = 0;
        var iterator = accepted.entrySet().iterator();
        while (iterator.hasNext()) {
            if (batch.equals(iterator.next().getValue().batch())) {
                iterator.remove();
                removed++;
            }
        }
        var pendingIterator = pending.entrySet().iterator();
        while (pendingIterator.hasNext()) {
            if (batch.equals(pendingIterator.next().getValue().batch())) {
                pendingIterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public Collection<SemanticEntry> entries() {
        return List.copyOf(accepted.values());
    }

    public Collection<SemanticEntry> pendingEntries() {
        return List.copyOf(pending.values());
    }

    public int size() {
        return accepted.size();
    }

    public void clear() {
        accepted.clear();
        pending.clear();
    }

    /**
     * Reads every {@code *.json} in a directory, validating each line.
     *
     * <p>A single bad line never fails the whole load: it is counted and reported, and the
     * rest of the namespace still applies.
     */
    public LoadReport load(Path directory, RealFacts facts) {
        if (directory == null || !Files.isDirectory(directory)) {
            return LoadReport.empty();
        }
        int acceptedCount = 0;
        int pendingCount = 0;
        int rejectedCount = 0;
        Map<String, Integer> codes = new TreeMap<>();
        List<String> problems = new ArrayList<>();

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException exception) {
            problems.add("cannot list " + directory + ": " + exception.getMessage());
            return new LoadReport(0, 0, 0, Map.of(), problems);
        }

        for (Path file : files) {
            var result = KnowledgeFile.read(file);
            problems.addAll(result.problems());
            for (SemanticEntry entry : result.snapshot().entries().values()) {
                var validation = KnowledgeValidator.validate(entry, facts, this);
                switch (validation.outcome()) {
                    case ACCEPTED -> {
                        put(entry);
                        acceptedCount++;
                    }
                    case PENDING -> {
                        putPending(entry);
                        pendingCount++;
                        codes.merge(validation.code(), 1, Integer::sum);
                    }
                    case REJECTED -> {
                        rejectedCount++;
                        codes.merge(validation.code(), 1, Integer::sum);
                        problems.add(entry.id() + ": " + validation.code() + " ("
                                + validation.message() + ")");
                    }
                }
            }
            for (SemanticEntry entry : result.snapshot().pending().values()) {
                putPending(entry);
                pendingCount++;
            }
        }
        return new LoadReport(acceptedCount, pendingCount, rejectedCount, codes, problems);
    }

    /** Writes accepted and pending entries back, one file per namespace. */
    public void save(Path directory) throws IOException {
        Map<String, Map<String, SemanticEntry>> acceptedByNamespace = new TreeMap<>();
        for (SemanticEntry entry : accepted.values()) {
            acceptedByNamespace.computeIfAbsent(entry.namespace(),
                    key -> new LinkedHashMap<>()).put(entry.id(), entry);
        }
        Map<String, Map<String, SemanticEntry>> pendingByNamespace = new TreeMap<>();
        for (SemanticEntry entry : pending.values()) {
            pendingByNamespace.computeIfAbsent(entry.namespace(),
                    key -> new LinkedHashMap<>()).put(entry.id(), entry);
        }
        java.util.Set<String> namespaces = new java.util.TreeSet<>();
        namespaces.addAll(acceptedByNamespace.keySet());
        namespaces.addAll(pendingByNamespace.keySet());
        Files.createDirectories(directory);
        for (String namespace : namespaces) {
            KnowledgeFile.write(directory.resolve(namespace + ".json"),
                    new KnowledgeFile.Snapshot(namespace, null,
                            acceptedByNamespace.getOrDefault(namespace, Map.of()),
                            pendingByNamespace.getOrDefault(namespace, Map.of())));
        }
    }
}
