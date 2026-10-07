package com.gamma.intelligence.pack;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code AGT-ARTIFACT-1} (decided 2026-09-10, slice one built 2026-09-15): a draft skill's result arrives on the
 * answer as {@code artifact: {kind: "draft", …}} so the assistant UI renders something actionable instead of
 * prose. eoiagent itself never constructs an {@code InlineArtifact} (checked in the pinned jars: every
 * {@code AgentAnswer} is built with a null artifact), so the producer has to live HERE — and it cannot read
 * the draft off the answer. It captures the tool's own result instead, keyed by the {@link RunId} eoiagent
 * threads through every {@link ToolCall} and into the final {@code AgentAnswer}.
 *
 * <p>Bounded, consumed on read, process-wide like the sessions it serves. Slice one records
 * {@code component_draft} only ({@link #RECORDED_TOOLS}); {@code query_author}, {@code projection_author} and
 * {@code kpi_report_builder} share the {@code {kind, draft}} shape and are one line each; {@code pipeline_author}
 * has no {@code draft} key (its graph is under {@code flow}) and needs a decision before it joins.
 */
public final class DraftArtifacts {
    private DraftArtifacts() {}

    /** The artifact kind the assistant UI renders as a read-only draft. */
    public static final String KIND = "draft";
    /** Tools whose successful result is captured as a draft artifact. */
    static final Set<String> RECORDED_TOOLS = Set.of("component_draft", "query_author", "projection_author", "kpi_report_builder");
    private static final int MAX = 256;

    private static final Map<String, Map<String, Object>> BY_RUN = new ConcurrentHashMap<>();

    /** Wrap {@code tool} so an {@code ok} result carrying a {@code draft} is remembered for its run. Transparent otherwise. */
    public static Tool recording(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        if (!RECORDED_TOOLS.contains(tool.spec().name())) return tool;
        return new Tool() {
            @Override public ToolSpec spec() { return tool.spec(); }
            @Override public ToolResult invoke(ToolCall call) {
                ToolResult r = tool.invoke(call);
                record(tool.spec().name(), call, r);
                return r;
            }
        };
    }

    public static void record(String toolName, ToolCall call, ToolResult r) {
        if (r == null || !r.ok() || call == null || call.run() == null) return;
        if (!(r.value() instanceof Map<?, ?> value) || !value.containsKey("draft")) return;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("tool", toolName);
        if (value.get("kind") != null) config.put("draftKind", value.get("kind"));
        if (value.get("type") != null) config.put("type", value.get("type"));
        if (value.get("clean") != null) config.put("clean", value.get("clean"));
        if (value.get("findings") != null) config.put("findings", value.get("findings"));
        config.put("draft", value.get("draft"));
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("kind", KIND);
        artifact.put("title", toolName.replace('_', ' ') + " — draft");
        artifact.put("config", config);
        if (BY_RUN.size() >= MAX) BY_RUN.clear();   // a bounded scratch, not a ledger
        BY_RUN.put(call.run().value(), artifact);
    }

    /** The draft recorded for {@code run}, removed on read; {@code null} when none. */
    public static Map<String, Object> take(RunId run) {
        return run == null ? null : BY_RUN.remove(run.value());
    }
}
