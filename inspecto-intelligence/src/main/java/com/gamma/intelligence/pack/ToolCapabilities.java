package com.gamma.intelligence.pack;

import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;

import java.util.Map;
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

    /**
     * {@code tool}, enforcing {@link #of} against the bound {@link ToolCaller} before it executes. This is the
     * SAME check the dispatch route makes, applied inside the tool so that a model-driven call from a session
     * cannot bypass it. A refusal is an {@code ok=false} result that carries no rows. With no caller bound it
     * refuses (fails closed). An ungated tool is returned unwrapped.
     */
    public static Tool enforcing(Tool tool) {
        Optional<String> required = of(tool.spec());
        if (required.isEmpty()) return tool;
        String capability = required.get();
        return new Tool() {
            @Override public ToolSpec spec() { return tool.spec(); }
            @Override public ToolResult invoke(ToolCall call) {
                Optional<ToolCaller> caller = ToolCaller.current();
                if (caller.isEmpty())
                    return new ToolResult(false, null, "tool '" + tool.spec().name() + "' refused: no caller is bound", Map.of());
                if (!caller.get().holds(capability))
                    return new ToolResult(false, null, "tool '" + tool.spec().name() + "' refused: missing capability '"
                            + capability + "'", Map.of());
                return tool.invoke(call);
            }
        };
    }

    /**
     * A MUTATING {@code tool} that refuses to execute while {@code killSwitch} reads true. The kill switch
     * used to reach only the ops_monitor autonomy loop, so an approved in-session act call still ran with it
     * engaged (round-2 verification, 2026-09-29). A non-mutating tool is returned unwrapped.
     */
    public static Tool haltable(Tool tool, java.util.function.BooleanSupplier killSwitch) {
        if (!tool.spec().mutating()) return tool;
        return new Tool() {
            @Override public ToolSpec spec() { return tool.spec(); }
            @Override public ToolResult invoke(ToolCall call) {
                if (killSwitch.getAsBoolean())
                    return new ToolResult(false, null, "tool '" + tool.spec().name() + "' refused: the autonomy kill switch is engaged", Map.of());
                return tool.invoke(call);
            }
        };
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
