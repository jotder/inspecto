package com.gamma.control;

import com.gamma.spi.http.ApiContext;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import com.gamma.access.CapabilityManifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>The control itself (route-gating plan step 3c, 2026-09-16).</b> A mutating route must declare its
 * posture — a capability via {@code ApiContext.withCapability}, or an {@code Exemption} with a category and
 * a reason — and a route that declares neither fails the BOOT.
 *
 * <p>This is what an auditor can test by trying to add one, which is the whole difference between "we
 * reviewed the routes" (true at a point in time) and "an undeclared mutating route cannot exist in a running
 * server". {@code CapabilityManifestTest} is the belt that catches it in CI, over every module's source;
 * this is the braces, over what is actually deployed.
 */
class UndeclaredMutatingRouteTest {

    /** The caller closes it: ControlApi.close() does not close its service, whose subscribers sit on the
     *  process-wide EventLog.global(). */
    private CollectorService service(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        return new CollectorService(List.of(toon), 3600, 1);
    }

    @Test
    void anUndeclaredMutatingRouteIsRefusedAtRegistration(@TempDir Path dir) throws Exception {
        try (CollectorService svc = service(dir); ControlApi api = new ControlApi(svc, 0)) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> api.post("/undeclared-route", (ex, m) -> null));

            assertTrue(e.getMessage().contains("undeclared mutating route POST /undeclared-route"),
                    e.getMessage());
            assertTrue(e.getMessage().contains("withCapability") && e.getMessage().contains("Exemption"),
                    "the message must name BOTH ways out, or it tells a developer to gate a route that was "
                            + "meant to be open: " + e.getMessage());
        }
    }

    /** Every mutating method is covered — a gap in one is a gap in the control. */
    @Test
    void everyMutatingMethodIsCovered(@TempDir Path dir) throws Exception {
        try (CollectorService svc = service(dir); ControlApi api = new ControlApi(svc, 0)) {
            assertThrows(IllegalStateException.class, () -> api.post("/x1", (e, m) -> null));
            assertThrows(IllegalStateException.class, () -> api.put("/x2", (e, m) -> null));
            assertThrows(IllegalStateException.class, () -> api.patch("/x3", (e, m) -> null));
            assertThrows(IllegalStateException.class, () -> api.delete("/x4", (e, m) -> null));
        }
    }

    /**
     * ⛔ Reads stay open, and that is a DECISION, not an omission: confidentiality sits at the Space/ABAC
     * layer (operator 2026-09-15, re-affirmed as the compliance position 2026-09-16). A GET must therefore
     * register without any declaration — if this test ever fails, the control has quietly grown a claim the
     * compliance narrative does not make.
     */
    @Test
    void aReadNeedsNoDeclaration(@TempDir Path dir) throws Exception {
        try (CollectorService svc = service(dir); ControlApi api = new ControlApi(svc, 0)) {
            assertDoesNotThrow(() -> api.get("/a-plain-read", (e, m) -> null));
        }
    }

    /** A declared capability is accepted — the positive case, so the test above cannot pass vacuously. */
    @Test
    void aGatedMutatingRouteRegistersFine(@TempDir Path dir) throws Exception {
        try (CollectorService svc = service(dir); ControlApi api = new ControlApi(svc, 0)) {
            assertDoesNotThrow(() -> api.post("/gated-route",
                    ApiContext.withCapability("canAdminister", (e, m) -> null)));
        }
    }

    /** And so is a recorded exemption — checked through a real one, so the lookup is exercised as shipped. */
    @Test
    void arecordedExemptionRegistersFine(@TempDir Path dir) throws Exception {
        try (CollectorService svc = service(dir); ControlApi api = new ControlApi(svc, 0)) {
            assertTrue(CapabilityManifest.isExempt("POST", "/queries/([^/]+)/run"),
                    "fixture check: this exemption must exist for the assertion below to mean anything");
        }
    }
}
