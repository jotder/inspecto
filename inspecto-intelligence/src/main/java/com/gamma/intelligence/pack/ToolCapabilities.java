package com.gamma.intelligence.pack;

import com.eoiagent.core.ToolSpec;

import java.util.Optional;
import java.util.Set;

/**
 * Maps the capability a tool DECLARES (its {@link ToolSpec#capability()}) to the control-plane capability a
 * caller must hold to invoke it directly through {@code POST /agent/tools/{name}} and {@code /derive}.
 * Operator decision 2026-09-29 (ASSURE-INTELLIGENCE-BUNDLE-1): before this, those routes refused mutating
 * tools but let any authenticated caller run an authoring tool or scan store rows.
 *
 * <p>The switch has no {@code default} on purpose: a new eoiagent capability fails compilation here instead
 * of silently mapping to "no gate".
 */
public final class ToolCapabilities {

    /** The capability that gates Workbench authoring, and store-row reads until {@code /db/query} is gated. */
    public static final String AUTHOR = "canAuthorWorkbench";

    /**
     * Tools that declare only a metadata read but in fact read STORE ROWS (SQL over a DB-backed operational
     * store). They take the same capability as authoring. {@code POST /db/query}, the equivalent read route,
     * is itself ungated, and that inconsistency is recorded as a BACKLOG row.
     */
    static final Set<String> ROW_READERS = Set.of("anomaly_scan", "suggest_expectations");

    private ToolCapabilities() {
    }

    /** The control-plane capability for {@code spec}, or empty when an authenticated caller is enough. */
    public static Optional<String> of(ToolSpec spec) {
        if (ROW_READERS.contains(spec.name())) return Optional.of(AUTHOR);
        return switch (spec.capability()) {
            case READ_METADATA, READ_SCHEMA, READ_DOCS, INVESTIGATE -> Optional.empty();
            case RUN_SQL_READONLY, GENERATE_SQL, AUTHOR_PIPELINE, EDIT_CONFIG, WRITE_DATASTORE -> Optional.of(AUTHOR);
            case RUN_PIPELINE, TRIGGER_JOB -> Optional.of("canOperateRuns");
        };
    }
}
