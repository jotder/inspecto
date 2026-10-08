package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.OperationalObject;
import com.gamma.service.CollectorService;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-P7 step 3: {@code inspecto-ops} contributes the {@code incident} and {@code case} linked-subject
 * providers an Action Request is raised from - resolve (kind-guarded), and the idempotent open-or-reuse the
 * {@code invoke-api} consequence relies on.
 */
class OpsLinkedSubjectsTest {

    private record Ctx(CollectorService svc, ControlApi api) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        return new Ctx(svc, new ControlApi(svc, 0));
    }

    @Test
    void bothKindsAreContributedAndAvailable(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(List.of("case", "incident"),
                    LinkedSubjects.all().stream().map(LinkedSubjectProvider::kind).sorted().toList());
            assertTrue(LinkedSubjects.of(c.api, "incident").isPresent());
            assertTrue(LinkedSubjects.of(c.api, "case").isPresent());
            assertTrue(LinkedSubjects.of(c.api, "task").isEmpty(), "probe: a kind nobody contributes has no provider");
        }
    }

    @Test
    void resolveNeverCrossesKinds(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            var objects = TestOpsEngine.of(c.svc);
            OperationalObject inc = objects.open(ObjectType.INCIDENT, "Leak", "d", "HIGH", null, null, null, "t", Map.of());
            OperationalObject kase = objects.open(ObjectType.CASE, "Fraud ring", "d", "HIGH", null, null, null, "t", Map.of());
            LinkedSubjectProvider incidents = LinkedSubjects.of(c.api, "incident").orElseThrow();
            LinkedSubjectProvider cases = LinkedSubjects.of(c.api, "case").orElseThrow();
            assertEquals(inc.id(), incidents.resolve(c.api, inc.id()).orElseThrow().id());
            assertEquals(kase.id(), cases.resolve(c.api, kase.id()).orElseThrow().id());
            assertTrue(incidents.resolve(c.api, kase.id()).isEmpty(), "a Case id is not an Incident");
            assertTrue(cases.resolve(c.api, inc.id()).isEmpty(), "an Incident id is not a Case");
            assertTrue(incidents.resolve(c.api, "no-such-id").isEmpty());
        }
    }

    @Test
    void openIsIdempotentForAnOriginUntilTheIncidentIsResolved(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            LinkedSubjectProvider incidents = LinkedSubjects.of(c.api, "incident").orElseThrow();
            LinkedSubjectProvider.OpenRequest req = new LinkedSubjectProvider.OpenRequest("decision-rule:leak",
                    "decisionRule", "leak", "Decision Rule leak", "d", "warning", Map.of("decisionRule", "leak"));
            String first = incidents.open(c.api, req).orElseThrow();
            assertEquals(first, incidents.open(c.api, req).orElseThrow(), "the same origin reuses the open Incident");
            LinkedSubjectProvider.OpenRequest other = new LinkedSubjectProvider.OpenRequest("decision-rule:leak",
                    "decisionRule", "other", "Decision Rule other", "d", "warning", Map.of("decisionRule", "other"));
            assertNotEquals(first, incidents.open(c.api, other).orElseThrow(), "probe: another rule opens its own");

            TestOpsEngine.of(c.svc).transition(first, "archive", "sys");   // terminal; resolve is gated on RCA/SLA evidence
            assertNotEquals(first, incidents.open(c.api, req).orElseThrow(), "an archived Incident is not reused");
            assertTrue(LinkedSubjects.of(c.api, "case").orElseThrow().open(c.api, req).isEmpty(), "a Case is never opened");
        }
    }
}
