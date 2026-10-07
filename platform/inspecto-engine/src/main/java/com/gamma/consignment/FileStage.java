package com.gamma.consignment;

import com.gamma.api.PublicApi;

/**
 * <b>Phase 4 Slice 2 — Stage C's per-file stage progression (§2.4).</b> The crash-safe commit
 * ordering {@code ConsignmentIngestor.finalizeSource} already enforces in code, made durable and
 * queryable: one row per (file, stage) recorded as each real boundary is crossed, so "where is
 * file X right now" is answerable without re-reading the manifest.
 *
 * <p>Deliberately only the boundaries {@code finalizeSource} genuinely crosses today — no stage is
 * invented ahead of the code that would report it. {@code REGISTERED} covers the optional DuckLake
 * register; the remaining five mirror the method's own ordering comment (register → manifest →
 * backup → markers LAST → ledger/watermark).
 */
@PublicApi(since = "4.0.0")
public enum FileStage {
    /** DuckLake register attempted (optional, non-fatal). */
    REGISTERED,
    /** {@code ConsignmentManifest} written — the file's existence of record. */
    MANIFESTED,
    /** §11.3 output-registry row(s) recorded for this batch's outputs. */
    OUTPUT_REGISTERED,
    /** The original file copied to {@code dirs.backup}. */
    BACKED_UP,
    /** PATH-mode marker sentinel written, or content-based fingerprint recorded to the ledger. */
    MARKED,
    /** A DB-export connector's watermark advanced past this file. */
    WATERMARK_ADVANCED,
    /**
     * Phase 4 S4b (park/drain, D-13): the file's Consignment PARKED at a disabled Step — manifest
     * written with {@code parkedAt}, file moved to the park home, and <b>none</b> of the later
     * stages ran (no backup-as-committed, no marker, no watermark). The queryable projection of an
     * uncommitted-by-choice Consignment; cleared by the drain completing the normal sequence.
     */
    PARKED
}
