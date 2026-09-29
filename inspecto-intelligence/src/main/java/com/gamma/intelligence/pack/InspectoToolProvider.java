package com.gamma.intelligence.pack;

import com.eoiagent.app.McpServerRef;
import com.eoiagent.app.ToolProvider;
import com.eoiagent.tool.Tool;
import com.gamma.service.ReadModel;

import java.util.List;

/** Exposes the P0 read tool belt to the core {@code ToolRegistry}. No MCP servers yet (Feature.MCP_TOOLS is off). */
final class InspectoToolProvider implements ToolProvider {

    private final ReadModel service;
    private final java.util.function.BooleanSupplier killSwitch;

    InspectoToolProvider(ReadModel service) {
        this(service, () -> false);
    }

    InspectoToolProvider(ReadModel service, java.util.function.BooleanSupplier killSwitch) {
        this.service = service;
        this.killSwitch = killSwitch;
    }

    @Override
    public List<Tool> tools() {
        // AGT-ARTIFACT-1: the draft skills are wrapped so their result reaches the answer as an artifact.
        // 2026-09-29: every gated tool enforces the caller's capability itself (ToolCapabilities.enforcing), so
        // a model-driven call from a session meets the same check as POST /agent/tools/{name}.
        return InspectoTools.tools(service).stream().map(t -> ToolCapabilities.haltable(t, killSwitch))
                .map(ToolCapabilities::enforcing).map(DraftArtifacts::recording).toList();
    }

    @Override
    public List<McpServerRef> mcpServers() {
        return List.of();
    }
}
