package com.gamma.control;

import com.gamma.service.ActivationFacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** SE-1b (D-SE12): the activation check's decision for each opt-in, from the lock's facts of one object. */
class ActivationHoldsTest {

    private static final ActivationFacts.Entry BROKEN = new ActivationFacts.Entry("pipeline", "orders",
            List.of(Map.of("path", "orders_pipeline.toon", "key", "collector.dataset", "value", "gone", "toKind", "dataset")),
            List.of());
    private static final ActivationFacts.Entry EDITED = new ActivationFacts.Entry("pipeline", "orders",
            List.of(), List.of(Map.of("path", "orders_pipeline.toon", "change", "modified")));
    private static final ActivationFacts.Entry BOTH = new ActivationFacts.Entry("pipeline", "orders",
            BROKEN.dangling(), EDITED.outside());

    @AfterEach
    void clear() {
        System.clearProperty(ActivationHolds.PROPERTY);
    }

    private static List<String> holds(ActivationHolds.Mode mode, ActivationFacts.Entry e) {
        return ActivationHolds.holds("s", mode, e).stream().map(ActivationHolds.Hold::hold).toList();
    }

    @Test
    void refuseActivationHoldsADanglingLinkAndNamesIt() {
        ActivationHolds.Mode refuse = new ActivationHolds.Mode(false, true, false, false);
        List<ActivationHolds.Hold> h = ActivationHolds.holds("s", refuse, BROKEN);
        assertEquals(1, h.size());
        assertEquals("links", h.get(0).hold());
        assertTrue(h.get(0).reason().contains("collector.dataset -> gone (dataset)"), h.get(0).reason());
        assertEquals(BROKEN.dangling(), h.get(0).body().get("links"));
        assertEquals(List.of(), holds(refuse, EDITED), "refuse-activation alone does not hold a hand edit");
    }

    @Test
    void anApprovalPolicyHoldsAnOutsideEditOfTheKindItGovernsOnly() {
        ActivationHolds.Mode governsPipeline = new ActivationHolds.Mode(false, false, true, false);
        assertEquals(List.of("changed-outside"), holds(governsPipeline, EDITED));
        assertEquals(List.of(), holds(governsPipeline, BROKEN), "a policy alone does not hold a dangling link");
        ActivationHolds.Mode governsJob = new ActivationHolds.Mode(false, false, false, true);
        assertEquals(List.of(), holds(governsJob, EDITED), "a policy for Jobs does not hold a Pipeline");
        assertEquals("orders_pipeline.toon", ActivationHolds.holds("s", governsPipeline, EDITED).get(0).body().get("path"));
    }

    @Test
    void bothHoldsAddUpAndNoOptInHoldsNothing() {
        assertEquals(List.of("links", "changed-outside"), holds(new ActivationHolds.Mode(false, true, true, true), BOTH));
        assertEquals(List.of(), holds(new ActivationHolds.Mode(false, false, false, false), BOTH));
        assertFalse(new ActivationHolds.Mode(false, false, false, false).optedIn());
    }

    @Test
    void basicHoldsNothingWhateverTheSpaceAsks() {
        ActivationHolds.Mode basic = new ActivationHolds.Mode(true, true, true, true);
        assertEquals(List.of(), holds(basic, BOTH));
        assertFalse(basic.optedIn());
    }

    @Test
    void aManyLinkReasonIsBounded() {
        List<Map<String, Object>> links = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) links.add(Map.of("key", "k" + i, "value", "v" + i, "toKind", "dataset"));
        ActivationFacts.Entry e = new ActivationFacts.Entry("job", "j", links, List.of());
        String reason = ActivationHolds.holds("s", new ActivationHolds.Mode(false, true, false, false), e).get(0).reason();
        assertTrue(reason.contains("and 2 more") && !reason.contains("v3"), reason);
    }

    @Test
    void anUntrackedSpaceHoldsNothing() {
        assertNull(ActivationHolds.reason("no-such-space", "pipeline", "orders"), "no report: never held (D-SE1)");
    }

    @Test
    void theOptInsAreCachedUntilAPolicyFileChanges(@TempDir Path config) throws Exception {
        ActivationHolds.resetForTest();
        assertFalse(ActivationHolds.mode("cache-space", config).governsPipeline(), "no approval.toon: governs nothing");
        Files.writeString(config.resolve("approval.toon"), "approval:\n  pipeline:\n    required: true\n");
        assertTrue(ActivationHolds.mode("cache-space", config).governsPipeline(), "a new file is a new stamp: read again");
        Files.writeString(config.resolve("approval.toon"), "approval:\n  job:\n    required: true\n  pipeline:\n    required: false\n");
        ActivationHolds.Mode m = ActivationHolds.mode("cache-space", config);
        assertFalse(m.governsPipeline(), m.toString());
        assertTrue(m.governsJob(), m.toString());
    }
}
