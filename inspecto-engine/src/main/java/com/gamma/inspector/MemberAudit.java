package com.gamma.inspector;

import com.gamma.etl.Batch;

import java.time.LocalDateTime;

/**
 * Per-input-file audit record accumulated while a {@link BatchIngestStrategy} processes
 * a batch, then consumed by {@link BatchProcessor}'s audit assembly. Extracted from
 * {@code BatchProcessor} so both the CSV and plugin strategies can build it directly.
 */
record MemberAudit(int srcId, String filename, String status,
                   long parsedRows, long errorRows, String error, LocalDateTime start,
                   String origin) {

    static MemberAudit accepted(Batch.Member m, long parsed, long errors, LocalDateTime start) {
        return new MemberAudit(m.srcId(), m.file().getName(), "SUCCESS", parsed, errors, "", start,
                origin(m));
    }

    static MemberAudit rejected(Batch.Member m, String status, String error, LocalDateTime start) {
        return new MemberAudit(m.srcId(), m.file().getName(), status, 0, 0, error, start, origin(m));
    }

    /**
     * The inbox file this member came OUT of — the archive or compressed original the operator
     * actually dropped — or blank when the member IS that file (the ordinary, uncompressed case).
     *
     * <p>⚠ Captured HERE, at ingest time, and not where the audit row is written: {@code writeAudit}
     * runs AFTER {@code commit}, and commit's {@code UnpackStage.cleanup} consumes the origin
     * mapping — so resolving it later reads blank for every expanded file. The lookup is cheap
     * (a map hit) and this is the last moment the mapping is guaranteed to exist.
     */
    private static String origin(Batch.Member m) {
        java.io.File original = com.gamma.etl.unpack.UnpackOrigins.originalOr(m.file());
        return original.equals(m.file()) ? "" : original.getName();
    }
}
