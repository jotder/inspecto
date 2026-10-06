package com.gamma.control;

import com.gamma.service.CollectorService;

/**
 * Test-side downcast onto {@link FakeObjectEngineProvider.FakeObjects} — this module's twin of
 * {@code inspecto-ops}' {@code TestOpsEngine}. {@code CollectorService.objects()} hands out only the
 * narrow {@code ObjectAccess} seam; this takes it back down to the fake's test-only surface
 * ({@code all()}, {@code get()}, {@code archive()}) so a fixture can seed and inspect state a route's own
 * seam does not expose.
 *
 * <p>⚠ Fails loudly rather than returning null: in this test tree the fake is always installed (see
 * {@code META-INF/services/com.gamma.service.ObjectEngineProvider}), so an empty seam means the fixture
 * did not boot a {@code CollectorService} — a broken test, not a scenario.
 */
final class TestFakeObjects {

    private TestFakeObjects() {}

    static FakeObjectEngineProvider.FakeObjects of(CollectorService svc) {
        return svc.objects()
                .filter(FakeObjectEngineProvider.FakeObjects.class::isInstance)
                .map(FakeObjectEngineProvider.FakeObjects.class::cast)
                .orElseThrow(() -> new IllegalStateException(
                        "no FakeObjectEngineProvider was discovered - check "
                                + "src/test/resources/META-INF/services/com.gamma.service.ObjectEngineProvider"));
    }
}
