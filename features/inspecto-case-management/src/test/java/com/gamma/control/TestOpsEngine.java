package com.gamma.control;

import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectServiceAccess;
import com.gamma.service.CollectorService;

/**
 * Test-side twin of {@code com.gamma.opsapi.OpsEngine} (EDG-01 cell 7, 2026-09-08).
 *
 * <p>The moved HTTP tests seed fixtures through the full engine — nine-argument {@code open}, {@code link},
 * {@code query} — but {@code CollectorService.objects()} hands out only the narrow
 * {@code com.gamma.objects.ObjectAccess} seam now. This takes it back down, exactly as the production
 * routes do, so those tests stay byte-identical apart from the call that fetches the engine.
 *
 * <p>⚠ Fails loudly rather than returning null: in this module the engine is always installed, so an
 * empty seam means the test fixture never wired a provider — a broken test, not a scenario.
 */
final class TestOpsEngine {

    private TestOpsEngine() {}

    static ObjectService of(CollectorService svc) {
        return svc.objects()
                .filter(ObjectServiceAccess.class::isInstance)
                .map(access -> ((ObjectServiceAccess) access).service())
                .orElseThrow(() -> new IllegalStateException(
                        "no ObjectEngineProvider was discovered - inspecto-ops is on the test classpath, "
                                + "so this means the fixture did not boot a CollectorService"));
    }
}
