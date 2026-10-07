package com.gamma.intelligence.pack;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.tool.Tool;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-2 verification item 4 (2026-09-29): the autonomy kill switch reached only the ops_monitor loop, so an
 * approved in-session act call (intelligence.act.enabled=true) still ran with it engaged. The pack's tool
 * provider, the one the platform's sessions call through, now halts every mutating tool while it is engaged.
 */
class KillSwitchActTierTest {

    private static final String REFUSAL = "refused: the autonomy kill switch is engaged";

    @Test
    void anEngagedKillSwitchHaltsEveryMutatingToolOnThePlatformsBelt() throws Exception {
        AtomicBoolean engaged = new AtomicBoolean(true);
        try (CollectorService svc = new CollectorService(List.of(), 3600, 1)) {
            List<Tool> belt = new InspectoPack(svc, engaged::get).toolProvider().tools();
            List<Tool> mutating = belt.stream().filter(t -> t.spec().mutating()).toList();
            assertEquals(7, mutating.size(), "the act tier: " + mutating.stream().map(t -> t.spec().name()).toList());
            for (Tool t : mutating) {
                ToolResult r = ToolCaller.with(ToolCaller.UNRESTRICTED,
                        () -> t.invoke(new ToolCall(t.spec().name(), Map.of(), new RunId("kill"))));
                assertFalse(r.ok(), t.spec().name());
                assertEquals("tool '" + t.spec().name() + "' " + REFUSAL, r.error(), t.spec().name());
            }
            engaged.set(false);   // released: the same tools run again (their own argument checks now answer)
            for (Tool t : mutating) {
                ToolResult r = ToolCaller.with(ToolCaller.UNRESTRICTED,
                        () -> t.invoke(new ToolCall(t.spec().name(), Map.of(), new RunId("kill"))));
                assertFalse(String.valueOf(r.error()).contains(REFUSAL), t.spec().name() + " must run once released");
            }
            belt.stream().filter(t -> !t.spec().mutating()).forEach(t -> {
                ToolResult r = ToolCaller.with(ToolCaller.UNRESTRICTED,
                        () -> t.invoke(new ToolCall(t.spec().name(), Map.of(), new RunId("kill"))));
                assertFalse(String.valueOf(r.error()).contains(REFUSAL), t.spec().name() + " is not act tier");
            });
        }
    }
}
