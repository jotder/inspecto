package com.gamma.intelligence.pack;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.tool.Tool;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every tool the pack hands the platform is classified. If a tool is gated by {@link ToolCapabilities}, its
 * enforcing wrapper refuses a caller without the capability (and a caller with none bound). If it is not gated,
 * it is on the explicit list below. A new tool therefore fails here until someone decides which it is.
 */
class ToolCapabilitiesCoverageTest {

    private static final Set<String> GATED = Set.of("component_draft", "query_author", "projection_author",
            "kpi_report_builder", "pipeline_author", "anomaly_scan", "suggest_expectations",
            // the act tier: mutating, and gated as well
            "component_apply", "component_rollback", "job_run", "pipeline_rerun", "alert_ack", "schedule_apply",
            "runbook_operator");
    private static final Set<String> UNGATED = Set.of("glossary_lookup", "docs_search", "status_get", "signals_query",
            "signal_timeline", "timeline_build", "diff_batches", "config_versions_diff", "config_schema");

    @Test
    void everyToolOnTheBeltIsClassifiedAndEveryGatedToolEnforces() throws Exception {
        try (CollectorService svc = new CollectorService(java.util.List.of(), 3600, 1)) {
            Set<String> seen = new TreeSet<>();
            for (Tool tool : new InspectoToolProvider(svc).tools()) {
                String name = tool.spec().name();
                seen.add(name);
                ToolCall call = new ToolCall(name, Map.of(), new RunId("coverage"));
                if (GATED.contains(name)) {
                    assertTrue(ToolCapabilities.of(tool.spec()).isPresent(), name + " must declare a gate");
                    ToolResult none = tool.invoke(call);   // nothing bound
                    assertEquals("tool '" + name + "' refused: no caller is bound", none.error(), name);
                    ToolResult reader = ToolCaller.with(ToolCaller.of(Set.of()), () -> tool.invoke(call));
                    assertTrue(String.valueOf(reader.error()).startsWith("tool '" + name + "' refused: missing capability"),
                            name + " must refuse a caller without the capability, got " + reader.error());
                } else {
                    assertTrue(UNGATED.contains(name), "unclassified tool '" + name + "': add it to GATED or UNGATED");
                    assertTrue(ToolCapabilities.of(tool.spec()).isEmpty(), name + " is listed ungated but declares a gate");
                }
            }
            assertTrue(seen.containsAll(UNGATED), "a listed ungated tool left the belt: " + seen);
        }
    }

    /**
     * DB-QUERY-UNGATED-1 (operator 2026-10-03, option A): the row-reading tools and the operational-group reads of
     * {@code POST /db/query} / {@code GET /db/table} take ONE capability. This module cannot see the control-plane
     * route class, so this reads its source and fails if either side moves alone.
     */
    @Test
    void rowReadersShareTheOperationalRowReadCapability() throws Exception {
        String src = Files.readString(Path.of("..", "inspecto", "src", "main", "java", "com", "gamma", "control",
                "DbBrowserRoutes.java"));
        assertTrue(src.contains("ApiContext.requireCapability(ex, \"" + ToolCapabilities.AUTHOR + "\")"),
                "DbBrowserRoutes' operational-row gate must be " + ToolCapabilities.AUTHOR);
        assertEquals(2, src.split(java.util.regex.Pattern.quote("requireOperationalRead(ex)"), -1).length - 1,
                "the gate is called by both /db/table and /db/query");
        assertEquals(Set.of("anomaly_scan", "suggest_expectations"), ToolCapabilities.ROW_READERS,
                "a new row-reading tool must take the /db/query operational-row capability; update both together");
    }
}
