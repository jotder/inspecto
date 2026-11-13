package com.gamma.intelligence.store;

import com.gamma.intelligence.policy.AutonomyPolicy;
import com.gamma.intelligence.policy.AutonomyPolicyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SE-4 step 1 for the assist agent's files ({@code agent/}): a ring and the autonomy policy load from
 * {@code state/agent} first, then {@code config/agent}; with both the state one wins, and this release keeps its
 * changes in memory rather than write a file a newer release owns.
 */
class AgentStateZoneTest {

    private static final class StringRing extends DurableJsonlRing<String> {
        StringRing(Path file) {
            super(10, file, new Codec<String>() {
                @Override public Map<String, Object> toRecord(String s) { return Map.of("v", s); }
                @Override public String fromRecord(Map<String, Object> r) { return String.valueOf(r.get("v")); }
            }, "item(s)");
        }
        void add(String s) { append(s); }
        List<String> recent() { return recentSnapshot(10); }
    }

    private static Path inState(Path root, String name) throws Exception {
        return Files.createDirectories(root.resolveSibling("state").resolve("agent")).resolve(name);
    }

    @Test
    void aRingUnderStateIsRead(@TempDir Path tmp) throws Exception {
        Path file = Files.createDirectories(tmp.resolve("config").resolve("agent")).resolve("approvals.jsonl");
        Files.writeString(inState(tmp.resolve("config"), "approvals.jsonl"), "{\"v\":\"s\"}\n");
        assertEquals(List.of("s"), new StringRing(file).recent());
    }

    @Test
    void aRingUnderConfigIsRead(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config").resolve("agent").resolve("approvals.jsonl");
        new StringRing(file).add("c");
        assertFalse(Files.exists(tmp.resolve("state")), "nothing is written to state/");
        assertEquals(List.of("c"), new StringRing(file).recent());
    }

    @Test
    void withBothTheStateRingWinsAndIsNeverRewrittenHere(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config").resolve("agent").resolve("approvals.jsonl");
        new StringRing(file).add("c");
        Path state = inState(tmp.resolve("config"), "approvals.jsonl");
        Files.writeString(state, "{\"v\":\"s\"}\n");
        StringRing ring = new StringRing(file);
        assertEquals(List.of("s"), ring.recent(), "state/ wins");
        ring.add("new");
        assertEquals(List.of("new", "s"), ring.recent(), "kept in memory");
        assertEquals("{\"v\":\"s\"}\n", Files.readString(state), "state/ untouched");
        assertEquals("{\"v\":\"c\"}\n", Files.readString(file), "and config/ not rewritten either: no read would see it");
    }

    @Test
    void theAutonomyPolicyFollowsTheSameRule(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config").resolve("agent").resolve("policy.json");
        AutonomyPolicyStore config = new AutonomyPolicyStore(file);
        config.update(AutonomyPolicy.defaults());
        assertTrue(Files.isRegularFile(file));
        assertFalse(new AutonomyPolicyStore(file).current().killSwitch(), "read from config/");

        Files.writeString(inState(tmp.resolve("config"), "policy.json"),
                Files.readString(file).replaceFirst("\"killSwitch\"\\s*:\\s*false", "\"killSwitch\" : true"));
        AutonomyPolicyStore both = new AutonomyPolicyStore(file);
        assertTrue(both.current().killSwitch(), "state/ wins");
        String before = Files.readString(file);
        both.update(AutonomyPolicy.defaults());
        assertEquals(before, Files.readString(file), "a newer release's policy is not shadowed by a write here");
    }
}
