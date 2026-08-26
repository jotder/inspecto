package com.gamma.etl;

/**
 * The end status of ONE source file inside a Consignment — the "member" vocabulary.
 *
 * <p>Declared once, here, because it is written by four independent ingest paths
 * ({@code CsvBatchStrategy}, {@code NativeCsvStreamingEngine}, {@code GenerationModeIngester},
 * {@code UnionModeIngester}) and read by two ({@code BatchProcessor}'s manifest assembly and its
 * audit-ledger assembly). It lived as bare string literals across those six files and drifted
 * exactly as predicted: a fifth value, {@link #SKIPPED_UNREADABLE}, was added on 2026-08-26 to only
 * one of them.
 *
 * <p><b>The constant name IS the wire form.</b> It is what lands in the JSON
 * {@link BatchManifest.MemberEntry#status()} and in the {@code status} column of the per-file audit
 * ledger, verbatim — so renaming a constant is an on-disk format change, and the tests that assert
 * the literal strings are the guard that says so.
 *
 * <p>⛔ <b>Three status vocabularies meet in this area and none of them are this one.</b> Do not
 * merge, widen or cross-map:
 * <ul>
 *   <li>{@code com.gamma.etl.unpack.UnpackStatus} describes an <b>Archive</b> (a container) — the
 *       unpack stage's run-level verdict. This one describes a <b>member</b>.</li>
 *   <li>{@code IngestOutcome.status} ({@code SUCCESS}/{@code EMPTY}/{@code FAILED}) describes the
 *       whole <b>batch</b>, not any one file in it.</li>
 *   <li>{@code JobResult}/{@code ProcessorResult}/{@code BatchEvent} statuses are execution
 *       outcomes of a run, and share only the spelling of {@code SUCCESS}.</li>
 * </ul>
 */
public enum MemberStatus {

    /** The file was read and its accepted rows contributed to the Consignment. */
    SUCCESS,

    /** Readable, well-formed, but carried no data rows — quarantined rather than committed empty. */
    QUARANTINED_EMPTY,

    /** Read, but its header/shape did not match the declared schema — quarantined. */
    QUARANTINED_MISMATCH,

    /** Could not be read at all (decode/IO failure) — quarantined. */
    QUARANTINED_UNREADABLE,

    /**
     * An Archive entry the unpack stage could not decode — encrypted, or an unsupported compression
     * method — so it was never planned into a batch at all. It therefore has no {@code srcId}
     * ({@code -1}), no backup and no marker; it exists in the manifest so a partial expansion never
     * reads as a clean success. ⚠ Distinct from {@link #QUARANTINED_UNREADABLE}, which IS a planned
     * member that failed on the way in.
     */
    SKIPPED_UNREADABLE;

    /** True when {@code status} is this constant's wire form. For call sites still holding a String. */
    public boolean is(String status) {
        return name().equals(status);
    }
}
