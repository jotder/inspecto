package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.service.ImportJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs a refused import's undo so that the refusal stays the answer (`IMPORT-RESIDUALS-1` (3)): a rollback that
 * itself fails never replaces the 4xx (or the original failure) — it is logged, attached as suppressed and named in
 * the message — and every file the rollback left alone because another request changed it meanwhile is reported.
 */
final class ImportRollback {

    private static final Logger log = LoggerFactory.getLogger(ImportRollback.class);

    private ImportRollback() {}

    /** An undo step: unregister, roll the journal back, re-read configs. */
    interface Undo {
        void run() throws IOException;
    }

    /**
     * What an undo left behind.
     *
     * @param notRolledBack files (relative to the root, forward slashes) left as a concurrent writer made them
     * @param failure       the undo's own failure, or {@code null}
     */
    record Outcome(List<String> notRolledBack, Exception failure) {
        boolean clean() {
            return notRolledBack.isEmpty() && failure == null;
        }

        /** Appended to a refusal's message; empty when {@link #clean()}. */
        String note() {
            String n = notRolledBack.isEmpty() ? "" : "; changed concurrently, not rolled back: " + notRolledBack;
            return failure == null ? n : n + "; rollback incomplete: " + failure.getMessage();
        }

        /** The same facts as body fields, for a refusal answered with a JSON body. */
        void into(Map<String, Object> body) {
            if (!notRolledBack.isEmpty()) body.put("notRolledBack", notRolledBack);
            if (failure != null) body.put("rollbackIncomplete", String.valueOf(failure.getMessage()));
        }
    }

    /** Run {@code undo}; never throws — its failure is logged and returned, with what the journal left alone. */
    static Outcome run(ImportJournal journal, Path root, Undo undo) {
        Exception failure = null;
        try {
            undo.run();
        } catch (IOException | RuntimeException e) {
            failure = e;
            log.error("[IMPORT] rollback incomplete under {}", root, e);
        }
        Path r = root.toAbsolutePath().normalize();
        List<String> left = journal.notRolledBack().stream()
                .map(p -> r.relativize(p).toString().replace('\\', '/')).toList();
        return new Outcome(left, failure);
    }

    /** The refusal, same status and code, its message carrying the outcome; the undo failure as suppressed. */
    static ApiException refusal(ApiException refused, Outcome o) {
        if (o.clean()) return refused;
        ApiException noted = new ApiException(refused.status, refused.errorCode, refused.getMessage() + o.note());
        noted.initCause(refused);
        if (o.failure() != null) noted.addSuppressed(o.failure());
        return noted;
    }

    /** A non-refusal failure whose rollback was not clean: its message (the 500 body) carrying the outcome. */
    static IOException failure(Exception failed, Outcome o) {
        IOException noted = new IOException(failed.getMessage() + o.note(), failed);
        if (o.failure() != null) noted.addSuppressed(o.failure());
        return noted;
    }
}
