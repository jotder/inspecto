package com.gamma.intelligence.pack;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AGT-ARTIFACT-1 slice one: a draft skill's ok result is remembered per run, consumed on read, transparent otherwise. */
class DraftArtifactsTest {

    private static Tool tool(String name, ToolResult result) {
        ToolSpec spec = new ToolSpec(name, "d", "{}", false, Role.USER, Capability.READ_METADATA);
        return new Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public ToolResult invoke(ToolCall call) { return result; }
        };
    }

    @Test
    void recordsAComponentDraftForItsRunAndHandsItOutOnce() {
        Map<String, Object> value = Map.of("kind", "expectation", "type", "expectation", "clean", false,
                "findings", List.of(Map.of("fieldPath", "target", "message", "required")),
                "draft", Map.of("name", "half_baked"));
        Tool wrapped = DraftArtifacts.recording(tool("component_draft", new ToolResult(true, value, null, Map.of())));

        ToolResult r = wrapped.invoke(new ToolCall("component_draft", Map.of(), new RunId("run-9")));
        assertSame(value, r.value(), "the wrapper is transparent — the model sees the tool's own result");

        Map<String, Object> artifact = DraftArtifacts.take(new RunId("run-9"));
        assertNotNull(artifact);
        assertEquals("draft", artifact.get("kind"));
        @SuppressWarnings("unchecked") Map<String, Object> config = (Map<String, Object>) artifact.get("config");
        assertEquals("component_draft", config.get("tool"));
        assertEquals("expectation", config.get("draftKind"));
        assertEquals(false, config.get("clean"));
        assertEquals(Map.of("name", "half_baked"), config.get("draft"));
        assertNull(DraftArtifacts.take(new RunId("run-9")), "consumed on read");
        assertNull(DraftArtifacts.take(new RunId("run-other")), "another run sees nothing");
    }

    @Test
    void aFailedResultAndANonDraftToolAreNotRecorded() {
        Tool failed = DraftArtifacts.recording(tool("component_draft", new ToolResult(false, null, "boom", Map.of())));
        failed.invoke(new ToolCall("component_draft", Map.of(), new RunId("run-f")));
        assertNull(DraftArtifacts.take(new RunId("run-f")));

        Tool other = tool("list_pipelines", new ToolResult(true, Map.of("draft", "x"), null, Map.of()));
        assertSame(other, DraftArtifacts.recording(other), "a tool outside RECORDED_TOOLS is returned as-is");
    }
}
