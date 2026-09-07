package com.gamma.exchange;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The Exchange ledger + Share Grant lifecycle: offer → request → approve/deny/revoke, all fail-closed. */
class ExchangeTest {

    private static Offer datasetOffer() {
        return new Offer("dataset", "tax_receipts", "finance", "FY26 receipts",
                Map.of("columns", java.util.List.of("amount", "day")), "a.rao@finance.gov", 1L);
    }

    @Test
    void disabledWhenSingleTenant() {
        Exchange ex = Exchange.under(null);
        assertFalse(ex.enabled());
        assertThrows(IllegalStateException.class, ex::offers);
        assertThrows(IllegalStateException.class, () -> ex.putOffer(datasetOffer()));
    }

    @Test
    void offerRoundTrips(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        assertTrue(ex.enabled());
        assertTrue(ex.offers().isEmpty());

        ex.putOffer(datasetOffer());
        assertEquals(1, ex.offers().size());
        Optional<Offer> got = ex.offer("finance", "dataset", "tax_receipts");
        assertTrue(got.isPresent());
        assertEquals("FY26 receipts", got.get().description());

        // upsert by (owner,kind,item) — not a duplicate
        ex.putOffer(new Offer("dataset", "tax_receipts", "finance", "updated", Map.of(), "a.rao", 2L));
        assertEquals(1, ex.offers().size());
        assertEquals("updated", ex.offer("finance", "dataset", "tax_receipts").orElseThrow().description());
    }

    @Test
    void grantLifecycleAndFailClosedResolution(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(datasetOffer());

        // no grant yet ⇒ resolution is fail-closed even though the offer exists
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isEmpty());

        ShareGrant g = ex.request("dataset", "tax_receipts", "finance", "audit", "r.gupta@audit.gov", "audit", null);
        assertEquals(ShareGrant.REQUESTED, g.status());
        assertEquals(ShareGrant.SNAPSHOT, g.mode(), "default mode is snapshot");
        // still fail-closed while only requested
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isEmpty());

        // re-request is idempotent; requesting again does not duplicate
        assertEquals(g.id(), ex.request("dataset", "tax_receipts", "finance", "audit", "x", "y", null).id());
        assertEquals(1, ex.grants().size());

        // approve ⇒ active ⇒ resolves to metadata
        ShareGrant approved = ex.approve(g.id(), "a.rao@finance.gov");
        assertEquals(ShareGrant.ACTIVE, approved.status());
        assertEquals("a.rao@finance.gov", approved.approvedBy());
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isPresent());

        // requesting an already-active grant is a conflict
        assertThrows(IllegalStateException.class,
                () -> ex.request("dataset", "tax_receipts", "finance", "audit", "x", "y", null));

        // revoke ⇒ resolution closes again; re-approving a revoked grant is illegal
        ex.revoke(g.id(), "a.rao@finance.gov");
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isEmpty());
        assertThrows(IllegalStateException.class, () -> ex.approve(g.id(), "a.rao@finance.gov"));
    }

    @Test
    void denyKeepsResolutionClosed(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(datasetOffer());
        ShareGrant g = ex.request("dataset", "tax_receipts", "finance", "audit", "r.gupta", "audit", null);
        ex.deny(g.id(), "a.rao");
        assertEquals(ShareGrant.DENIED, ex.grant(g.id()).orElseThrow().status());
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isEmpty());
        // a denied request can be reopened
        assertEquals(ShareGrant.REQUESTED,
                ex.request("dataset", "tax_receipts", "finance", "audit", "r.gupta", "audit", null).status());
    }

    @Test
    void unknownGrantTransitionThrows(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        assertThrows(java.util.NoSuchElementException.class, () -> ex.approve("nope", "x"));
    }

    @Test
    void widgetGrantClosureAndCascade(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(new Offer("dataset", "tax_receipts", "finance", "", Map.of(), "a", 1L));
        ex.putOffer(new Offer("widget", "chart1", "finance", "", Map.of(), "a", 1L,
                java.util.List.of("tax_receipts")));

        // requesting the widget auto-creates the bound dataset grant (both pending)
        ShareGrant wg = ex.request("widget", "chart1", "finance", "audit", "r", "p", null);
        String dgid = ShareGrant.idFor("dataset", "tax_receipts", "finance", "audit");
        assertEquals(ShareGrant.REQUESTED, wg.status());
        assertTrue(ex.grant(dgid).isPresent(), "dataset grant travels with the widget");
        assertFalse(ex.canRenderWidget("audit", "finance", "chart1"), "not renderable while pending");

        // approving the widget activates the pair atomically
        ex.approve(wg.id(), "a");
        assertEquals(ShareGrant.ACTIVE, ex.grant(dgid).orElseThrow().status());
        assertTrue(ex.canRenderWidget("audit", "finance", "chart1"));

        // revoking the dataset grant cascades to the dependent widget grant (fail-closed)
        ex.revoke(dgid, "a");
        assertEquals(ShareGrant.REVOKED, ex.grant(wg.id()).orElseThrow().status());
        assertFalse(ex.canRenderWidget("audit", "finance", "chart1"));
    }

    // ── D9: the saved-view kind ────────────────────────────────────────────────

    /** A view reading TWO datasets: the closure must cover both, and ANY revocation must close the view. */
    @Test
    void viewGrantClosureCoversEveryDatasetItReads(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(new Offer("dataset", "payments", "finance", "", Map.of(), "a", 1L));
        ex.putOffer(new Offer("dataset", "accounts", "finance", "", Map.of(), "a", 1L));
        ex.putOffer(new Offer(Exchange.VIEW, "fraud_ring", "finance", "", Map.of(), "a", 1L,
                java.util.List.of("payments", "accounts")));

        ShareGrant vg = ex.request(Exchange.VIEW, "fraud_ring", "finance", "audit", "r", "p", null);
        String pay = ShareGrant.idFor("dataset", "payments", "finance", "audit");
        String acc = ShareGrant.idFor("dataset", "accounts", "finance", "audit");
        assertTrue(ex.grant(pay).isPresent(), "every dataset the view reads travels with it");
        assertTrue(ex.grant(acc).isPresent(), "every dataset the view reads travels with it");
        assertFalse(ex.canRender("audit", "finance", Exchange.VIEW, "fraud_ring"), "not renderable while pending");

        ex.approve(vg.id(), "a");
        assertEquals(ShareGrant.ACTIVE, ex.grant(pay).orElseThrow().status(), "approval activates the closure");
        assertEquals(ShareGrant.ACTIVE, ex.grant(acc).orElseThrow().status());
        assertTrue(ex.canRender("audit", "finance", Exchange.VIEW, "fraud_ring"));

        // revoking ONE of the two datasets must close the view — a partial closure is not a free pass
        ex.revoke(acc, "a");
        assertEquals(ShareGrant.REVOKED, ex.grant(vg.id()).orElseThrow().status(), "cascade reaches the view");
        assertFalse(ex.canRender("audit", "finance", Exchange.VIEW, "fraud_ring"));
        assertEquals(ShareGrant.ACTIVE, ex.grant(pay).orElseThrow().status(), "the untouched dataset stays active");
    }

    /** A view owns no rows, so snapshot delivery is rejected outright — never silently coerced to live. */
    @Test
    void viewGrantIsLiveModeOnly(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(new Offer("dataset", "payments", "finance", "", Map.of(), "a", 1L));
        ex.putOffer(new Offer(Exchange.VIEW, "fraud_ring", "finance", "", Map.of(), "a", 1L,
                java.util.List.of("payments")));

        assertEquals(ShareGrant.LIVE,
                ex.request(Exchange.VIEW, "fraud_ring", "finance", "audit", "r", "p", null).mode(),
                "an omitted mode defaults to live for a view, not to the dataset default");
        assertEquals(ShareGrant.LIVE, ex.grant(ShareGrant.idFor("dataset", "payments", "finance", "audit"))
                .orElseThrow().mode(), "the paired dataset grant inherits the view's live mode");

        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () ->
                ex.request(Exchange.VIEW, "fraud_ring", "finance", "ops", "r", "p", ShareGrant.SNAPSHOT));
        assertTrue(bad.getMessage().contains("live-mode only"), bad.getMessage());
        assertTrue(ex.grant(ShareGrant.idFor(Exchange.VIEW, "fraud_ring", "finance", "ops")).isEmpty(),
                "a rejected request must leave no grant behind");
        // a dataset offer keeps the snapshot default — the view rule must not leak across kinds
        assertEquals(ShareGrant.SNAPSHOT,
                ex.request("dataset", "payments", "finance", "ops", "r", "p", null).mode());
    }

    /** A derived item whose offer records no datasets can never render — an empty closure is a denial. */
    @Test
    void viewWithNoRecordedDatasetsNeverRenders(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(new Offer(Exchange.VIEW, "orphan", "finance", "", Map.of(), "a", 1L));
        ShareGrant g = ex.request(Exchange.VIEW, "orphan", "finance", "audit", "r", "p", null);
        ex.approve(g.id(), "a");
        assertEquals(ShareGrant.ACTIVE, ex.grant(g.id()).orElseThrow().status());
        assertFalse(ex.canRender("audit", "finance", Exchange.VIEW, "orphan"),
                "an active grant with an empty dataset closure is still fail-closed");
    }

    /** Offers persisted before D9 carry a scalar {@code dataset}; they must still load. */
    @Test
    void legacyScalarDatasetKeyStillLoads(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        Map<String, Object> legacy = new java.util.LinkedHashMap<>(
                new Offer("widget", "chart1", "finance", "", Map.of(), "a", 1L).toMap());
        legacy.remove("datasets");
        legacy.put("dataset", "tax_receipts");          // the pre-D9 spelling
        Ledger.write(spacesRoot.resolve("_shared").resolve("offers.toon"), "offers", java.util.List.of(legacy));

        assertEquals(java.util.List.of("tax_receipts"),
                ex.offer("finance", "widget", "chart1").orElseThrow().datasets());
    }

    @Test
    void expiryClosesResolution(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(datasetOffer());
        ShareGrant g = ex.request("dataset", "tax_receipts", "finance", "audit", "r", "p", null);
        ex.approve(g.id(), "a");
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isPresent());

        ex.setExpiry(g.id(), System.currentTimeMillis() - 1000);   // already past
        assertTrue(ex.activeGrant("audit", "finance", "dataset", "tax_receipts").isEmpty(), "expired ⇒ inactive");
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isEmpty());

        ex.setExpiry(g.id(), System.currentTimeMillis() + 3_600_000);   // future re-opens
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isPresent());
    }

    @Test
    void pinSetAndClear(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(datasetOffer());
        ShareGrant g = ex.request("dataset", "tax_receipts", "finance", "audit", "r", "p", null);
        ex.approve(g.id(), "a");
        assertNull(ex.grant(g.id()).orElseThrow().pin());
        assertEquals("v123", ex.setPin(g.id(), "v123").pin());
        assertEquals("v123", ex.grant(g.id()).orElseThrow().pin());
        assertNull(ex.setPin(g.id(), null).pin(), "blank/null clears the pin");
    }

    @Test
    void sharedRefParsing() {
        assertEquals(new SharedRef("finance", "tax_receipts"),
                SharedRef.parse("shared/finance/tax_receipts").orElseThrow());
        assertTrue(SharedRef.isShared("shared/x/y"));
        assertFalse(SharedRef.isShared("finance/tax"));
        assertTrue(SharedRef.parse("shared/finance").isEmpty(), "needs owner and item");
        assertTrue(SharedRef.parse("shared/Finance/x").isEmpty(), "owner must match SpaceId charset");
        assertEquals("shared/finance/tax_receipts", new SharedRef("finance", "tax_receipts").ref());
    }
}
