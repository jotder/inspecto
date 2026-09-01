package com.gamma.etl;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Groups matched files by resolved schema/table, then greedily packs each group
 * into {@link Consignment}es honoring {@code maxFiles} OR {@code maxBytes} (whichever
 * trips first). A file larger than {@code maxBytes} forms a batch of one.
 *
 * <p>Pure and side-effect free apart from reading {@link File#length()}; the
 * schema resolution is injected via {@link SchemaResolver} so it is unit-testable
 * without a {@link PipelineConfig}.
 *
 * <p>Named for the canonical <b>Consignment</b> concept (GLOSSARY §2) ahead of the coordinated
 * Consignment→Consignment sweep (§13, amendment Phase 7) — the {@link Consignment} type it returns renames there.
 */
public final class ConsignmentPlanner {

    private ConsignmentPlanner() {}

    /** Resolves the schema/table for one file (wraps {@code SchemaSelector.select} or a single schema). */
    @FunctionalInterface
    public interface SchemaResolver {
        SchemaSelector.Selection resolve(File file) throws IOException;
    }

    /**
     * How candidate files are ordered before packing. {@code MTIME} — file modification time, which
     * for a collected file is its arrival in the inbox — is the DEFAULT (operator decision
     * 2026-08-12): a Consignment follows the order data actually arrived. A path tie-break keeps
     * equal stamps deterministic. {@code NAME} (absolute-path lexicographic) is the opt-in for feeds
     * whose stamps are unreliable — a copy or re-download resets mtime, while a timestamp embedded
     * in the NAME survives any transport ({@code processing.batch.order: name}).
     */
    public enum Order { NAME, MTIME }

    /** As {@link #plan(List, SchemaResolver, int, long, String, Order)} with the default {@link Order#MTIME}. */
    public static List<Consignment> plan(List<File> files, SchemaResolver resolver,
                                   int maxFiles, long maxBytes, String runTimestamp)
            throws IOException {
        return plan(files, resolver, maxFiles, maxBytes, runTimestamp, Order.MTIME);
    }

    /**
     * Plan batches from the given files.
     *
     * @param files        candidate files (already filtered for duplicates)
     * @param resolver      schema/table resolver
     * @param maxFiles      max member files per batch (>= 1)
     * @param maxBytes      max summed bytes per batch (>= 1)
     * @param runTimestamp  run timestamp embedded in each batch id
     * @param order         ordering before packing ({@link Order})
     * @return batches, grouped by schema/table, in deterministic order
     * @throws IOException if schema resolution fails
     */
    public static List<Consignment> plan(List<File> files, SchemaResolver resolver,
                                   int maxFiles, long maxBytes, String runTimestamp, Order order)
            throws IOException {

        // Group by table key (insertion-ordered for determinism), preserving each file's resolved
        // selection. MTIME (the default) tie-breaks on path so files sharing a stamp cannot reorder
        // between runs; NAME is the stamp-independent opt-in.
        Comparator<File> byPath = Comparator.comparing(f -> f.toPath().toAbsolutePath().toString());
        List<File> sorted = new ArrayList<>(files);
        sorted.sort(order == Order.MTIME
                ? Comparator.comparingLong(File::lastModified).thenComparing(byPath)
                : byPath);

        LinkedHashMap<String, List<File>> byKey = new LinkedHashMap<>();
        Map<File, SchemaSelector.Selection> selByFile = new HashMap<>();
        for (File f : sorted) {
            SchemaSelector.Selection sel = resolver.resolve(f);
            String key = (sel.table() != null && !sel.table().isBlank()) ? sel.table() : "default";
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            selByFile.put(f, sel);
        }

        List<Consignment> batches = new ArrayList<>();
        int seq = 1;
        for (Map.Entry<String, List<File>> group : byKey.entrySet()) {
            String key  = group.getKey();
            String slug = key.replaceAll("[^A-Za-z0-9]+", "_");

            List<Consignment.Member> current = new ArrayList<>();
            long currentBytes = 0;
            for (File f : group.getValue()) {
                long bytes = f.length();
                boolean wouldExceed = !current.isEmpty()
                        && (current.size() >= maxFiles || currentBytes + bytes > maxBytes);
                if (wouldExceed) {
                    batches.add(buildBatch(runTimestamp, slug, seq++, key, current, selByFile));
                    current = new ArrayList<>();
                    currentBytes = 0;
                }
                current.add(new Consignment.Member(f, current.size(), bytes, selByFile.get(f)));
                currentBytes += bytes;
            }
            if (!current.isEmpty())
                batches.add(buildBatch(runTimestamp, slug, seq++, key, current, selByFile));
        }
        return batches;
    }

    private static Consignment buildBatch(String ts, String slug, int seq, String table,
                                    List<Consignment.Member> members,
                                    Map<File, SchemaSelector.Selection> selByFile) {
        // Re-index srcId from 0 within the final batch (members were added with running index).
        List<Consignment.Member> reindexed = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            Consignment.Member m = members.get(i);
            reindexed.add(new Consignment.Member(m.file(), i, m.bytes(), m.selection()));
        }
        String batchId = String.format("%s_%s_%04d", ts, slug, seq);
        String schemaName = schemaNameOf(reindexed.get(0).selection());
        return new Consignment(batchId, schemaName, "default".equals(table) ? null : table, reindexed);
    }

    @SuppressWarnings("unchecked")
    private static String schemaNameOf(SchemaSelector.Selection sel) {
        Object raw = sel.schema().get("raw");
        if (raw instanceof Map<?, ?> rawMap && rawMap.get("name") != null)
            return String.valueOf(rawMap.get("name"));
        return "schema";
    }
}
