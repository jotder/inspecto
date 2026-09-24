package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.objects.AnnotationKinds;
import com.gamma.objects.ObjectType;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CORE half of the SEC-7d data-scope guard and the ABAC A3 row-level PEP: {@link AnnotationTargets}'
 * {@code object} arm applies {@link Subject#scoped()}/{@code dataScopes} on the {@code caseType} attribute
 * and then {@link RowScope}, over {@code ObjectAccess.summary}'s plain map — the one copy of that rule core
 * owns (notes and tags reach it from {@code inspecto-ops}).
 *
 * <p>⚠ {@code EDITION-GATED-TESTS-IN-WRONG-HOME-1} (2026-09-25). {@code ControlApiScopedObjectsTest} and the
 * row-scope case of {@code ControlApiAccessDeciderObjectsTest} stay in {@code inspecto-ops}: every assertion
 * in them drives {@code /objects}, whose filter/404/graph-pruning lives in the module's own
 * {@code ObjectRoutes.visibleTo}. What core contributes to that guard is exactly this class's subject —
 * so it runs here, in the default reactor, against {@link FakeObjectEngineProvider} (installed for the
 * {@code CollectorService} constructor only — see {@code ControlApiReconPromoteTest.open}) and a
 * {@link ExchangeAttributeScopeTest.FakeExchange} carrying the {@link Subject} the authenticate stage
 * would have stamped.
 */
class AnnotationTargetsScopeTest {

    private static final Subject FRAUD_ANALYST = new Subject("ana", Set.of(), Set.of("fraud"));
    private static final Subject UNSCOPED = new Subject("root", Set.of());   // dataScopes null

    @AfterEach
    void tearDown() {
        AccessDeciders.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, FakeObjectEngineProvider.FakeObjects objects)
            implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private static Ctx open(Path dir, boolean withEngine) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        ClassLoader outer = Thread.currentThread().getContextClassLoader();
        CollectorService svc;
        try {
            if (withEngine)
                Thread.currentThread().setContextClassLoader(ControlApiReconPromoteTest.fakeObjectEngineClassLoader(outer));
            svc = new CollectorService(List.of(toon), 3600, 1);
        } finally {
            Thread.currentThread().setContextClassLoader(outer);
        }
        return new Ctx(svc, new ControlApi(svc, 0), withEngine ? TestFakeObjects.of(svc) : null);
    }

    private static ExchangeAttributeScopeTest.FakeExchange as(Subject s) {
        var ex = new ExchangeAttributeScopeTest.FakeExchange("/api/v1/notes");
        if (s != null) ApiContext.attr(ex, ApiContext.ATTR_SUBJECT, s);
        return ex;
    }

    private static String open(Ctx c, Map<String, String> attrs) {
        return c.objects().open(ObjectType.INCIDENT, "t", "d", "HIGH", "corr", attrs);
    }

    private static int status(Runnable r) {
        return assertThrows(ApiException.class, r::run).status;
    }

    @Test
    void scopedSubjectSeesInScopeAndUntypedObjectsOnly(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            String fraud = open(c, Map.of("caseType", "fraud"));
            String billing = open(c, Map.of("caseType", "billing"));
            String untyped = open(c, Map.of());

            assertEquals("corr", AnnotationTargets.gate(c.api(), as(FRAUD_ANALYST), AnnotationKinds.OBJECT, fraud),
                    "in-scope object passes and yields its correlation id");
            assertEquals("corr", AnnotationTargets.gate(c.api(), as(FRAUD_ANALYST), AnnotationKinds.OBJECT, untyped),
                    "untyped objects are visible to every scoped subject");
            assertEquals(404, status(() -> AnnotationTargets.gate(c.api(), as(FRAUD_ANALYST), AnnotationKinds.OBJECT, billing)),
                    "out-of-scope object: indistinguishable from absence");
            assertFalse(AnnotationTargets.visible(c.api(), as(FRAUD_ANALYST), AnnotationKinds.OBJECT, billing),
                    "the read-side filter hides it too");
            assertTrue(AnnotationTargets.visible(c.api(), as(FRAUD_ANALYST), AnnotationKinds.OBJECT, fraud));
        }
    }

    @Test
    void unscopedAndAnonymousCallersSeeEveryObject(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            String billing = open(c, Map.of("caseType", "billing"));
            assertEquals("corr", AnnotationTargets.gate(c.api(), as(UNSCOPED), AnnotationKinds.OBJECT, billing),
                    "dataScopes null = unscoped");
            assertEquals("corr", AnnotationTargets.gate(c.api(), as(null), AnnotationKinds.OBJECT, billing),
                    "no Subject (Personal) = no data scoping");
            assertNull(AnnotationTargets.gate(c.api(), as(FRAUD_ANALYST), AnnotationKinds.OBJECT, "INCIDENT-nope"),
                    "an absent object is null (the caller's 404), not a scope denial");
        }
    }

    @Test
    void rowScopeDenyHidesTheObjectAndCarriesItsKind(@TempDir Path dir) throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        AccessDeciders.forTest((ex, subject, action, route, kind, resource) -> {
            calls.add(action + " " + route + " kind=" + kind + " subject=" + subject.id());
            return "hidden".equals(resource.get("flag")) ? AccessDecider.Decision.DENY : AccessDecider.Decision.ABSTAIN;
        });
        try (Ctx c = open(dir, true)) {
            String visible = open(c, Map.of());
            String hidden = open(c, Map.of("flag", "hidden"));

            assertEquals("corr", AnnotationTargets.gate(c.api(), as(UNSCOPED), AnnotationKinds.OBJECT, visible),
                    "ABSTAIN leaves the row visible");
            assertEquals(404, status(() -> AnnotationTargets.gate(c.api(), as(UNSCOPED), AnnotationKinds.OBJECT, hidden)),
                    "row-level DENY: indistinguishable from absence (SEC-7d)");
            assertTrue(calls.stream().anyMatch(s -> s.equals("read /api/v1/notes kind=incident subject=root")),
                    "row-level consultations are reads carrying the resolved kind: " + calls);

            calls.clear();
            assertEquals("corr", AnnotationTargets.gate(c.api(), as(null), AnnotationKinds.OBJECT, hidden),
                    "no Subject: the decider is never consulted and the row stays visible");
            assertTrue(calls.isEmpty(), "no Subject → no policy consultation: " + calls);
        }
    }

    @Test
    void objectTargetIs503WithoutTheOpsModule(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            assertEquals(503, status(() -> AnnotationTargets.gate(c.api(), as(FRAUD_ANALYST), AnnotationKinds.OBJECT, "x")),
                    "absent the module the object kind is not addressable at all");
        }
    }
}
