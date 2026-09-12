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
        return plan(files, resolver, maxFiles, maxBytes, runTimestamp, Order.MTIME, null);
    }

    /** @deprecated pass the poll root — without it a member's id falls back to its basename. */
    @Deprecated
    public static List<Consignment> plan(List<File> files, SchemaResolver resolver,
                                   int maxFiles, long maxBytes, String runTimestamp, Order order)
            throws IOException {
        return plan(files, resolver, maxFiles, maxBytes, runTimestamp, order, null);
    }

    /**
     * Plan batches from the given files.
     *
     * @param files        candidate files (already filtered for duplicates)
     * @param resolver      schema/table resolver
     * @param maxFiles      max member files per batch (>= 1)
     * @param maxBytes      max summed bytes per batch (>= 1)
     * @param runTimestamp  ⚠ NO LONGER PART OF THE BATCH ID — retained only so existing call sites keep
     *                      compiling. 🔴 It WAS the id's leading component, and being {@code now()} at
     *                      second granularity it was the ONLY non-deterministic input, which is exactly
     *                      what made two executors of the same work clobber or duplicate
     *                      ({@code CONSIGNMENT-ID-DETERMINISTIC-1}). ⛔ Do not wire it back into the id.
     *                      Removing the parameter outright is a follow-up, kept out of the identity
     *                      change so that diff stays reviewable.
     * @param pollRoot      root the member paths are relativized against, so the id is mount-independent;
     *                      {@code null} falls back to basenames (the dry-run path, which needs no
     *                      cross-pod stability)
     * @param order         ordering before packing ({@link Order})
     * @return batches, grouped by schema/table, in deterministic order
     * @throws IOException if schema resolution fails
     */
    public static List<Consignment> plan(List<File> files, SchemaResolver resolver,
                                   int maxFiles, long maxBytes, String runTimestamp, Order order,
                                   java.nio.file.Path pollRoot)
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
                    batches.add(buildBatch(pollRoot, slug, seq++, key, current, selByFile));
                    current = new ArrayList<>();
                    currentBytes = 0;
                }
                current.add(new Consignment.Member(f, current.size(), bytes, selByFile.get(f)));
                currentBytes += bytes;
            }
            if (!current.isEmpty())
                batches.add(buildBatch(pollRoot, slug, seq++, key, current, selByFile));
        }
        return batches;
    }

    private static Consignment buildBatch(java.nio.file.Path pollRoot, String slug, int seq, String table,
                                    List<Consignment.Member> members,
                                    Map<File, SchemaSelector.Selection> selByFile) {
        // Re-index srcId from 0 within the final batch (members were added with running index).
        List<Consignment.Member> reindexed = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            Consignment.Member m = members.get(i);
            reindexed.add(new Consignment.Member(m.file(), i, m.bytes(), m.selection()));
        }
        // 🔴 The id is derived from the batch's CONTENT SHAPE, never the clock — see ConsignmentId.
        // ⛔ Do not reintroduce runTimestamp here: it was the ONLY non-deterministic component, and it is
        // what made two executors of the same work either clobber one manifest or produce two.
        String batchId = ConsignmentId.of(slug, seq, pollRoot, reindexed);
        String schemaName = schemaNameOf(reindexed.get(0).selection());
        return new Consignment(batchId, schemaName, "default".equals(table) ? null : table, reindexed);
    }

    /**
     * The audit label of the schema a consignment was planned under — {@code raw.name} of the selected
     * schema, else {@code "schema"}. A plugin-ingester pipeline (ASN.1, XML, fixed-width segments) has
     * NO single schema: its records flatten onto {@code processing.segments}, chosen per record at
     * ingest, so the one-shot resolver hands the planner a selection whose schema is {@code null}.
     * That used to throw here, and every plugin pipeline run through {@code CollectorProcessor} died
     * before its first batch (found by the 02-parsing/asn1-frontend example, 2026-09-06). The label is
     * only ever read back by the manifest and audit rows, which already tolerate an unresolvable name.
     */
    private static String schemaNameOf(SchemaSelector.Selection sel) {
        Map<String, Object> schema = sel == null ? null : sel.schema();
        Object raw = schema == null ? null : schema.get("raw");
        if (raw instanceof Map<?, ?> rawMap && rawMap.get("name") != null)
            return String.valueOf(rawMap.get("name"));
        return "schema";
    }
}
