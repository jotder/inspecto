package com.gamma.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the pure request→action classifier behind the audit trail. */
class AuditTrailTest {

    @Test
    void classifiesDestructiveAndMutatingActions() {
        assertEquals(new AuditTrail.Action("pipeline.deleted", "destructive"),
                AuditTrail.classify("DELETE", "/runs/orders"));
        assertEquals(new AuditTrail.Action("pipeline.triggered", "data_mutation"),
                AuditTrail.classify("POST", "/runs/orders/trigger"));
        assertEquals(new AuditTrail.Action("pipeline.created", "data_mutation"),
                AuditTrail.classify("POST", "/runs"));
        assertEquals(new AuditTrail.Action("connection.updated", "data_mutation"),
                AuditTrail.classify("PUT", "/connections/sftp1"));
        assertEquals(new AuditTrail.Action("space.deleted", "destructive"),
                AuditTrail.classify("DELETE", "/spaces/team-b"));
    }

    /** DUCKLE-C8: the baseline ops must not read as "expectation.created" in the audit trail. */
    @Test
    void classifiesBaselineAcceptAndClear() {
        assertEquals(new AuditTrail.Action("expectation.accepted", "data_mutation"),
                AuditTrail.classify("POST", "/expectations/rows/baseline/accept"));
        assertEquals(new AuditTrail.Action("expectation.cleared", "data_mutation"),
                AuditTrail.classify("POST", "/expectations/rows/baseline/clear"));
    }

    /** X4: a record-level replay is audited under its own verb, not the POST default "pipeline.created". */
    @Test
    void classifiesReplayRejectsAsReplayed() {
        assertEquals(new AuditTrail.Action("pipeline.rejects_replayed", "data_mutation"),
                AuditTrail.classify("POST", "/runs/orders/replay-rejects"));
        assertEquals(new AuditTrail.Action("pipeline.reprocessed", "data_mutation"),
                AuditTrail.classify("POST", "/runs/orders/reprocess"));
    }

    @Test
    void classifiesConfigAndExport() {
        assertEquals("configuration", AuditTrail.classify("POST", "/config/write").category());
        assertEquals(new AuditTrail.Action("event.exported", "export"),
                AuditTrail.classify("GET", "/events/export"));
    }

    @Test
    void skipsDiagnosticAndReadOnly() {
        assertNull(AuditTrail.classify("POST", "/connections/sftp1/test"));
        assertNull(AuditTrail.classify("POST", "/components/grammar/x/test"));
        assertNull(AuditTrail.classify("POST", "/pipelines/authored/f1/dry-run"));
        assertNull(AuditTrail.classify("POST", "/validate"));
        assertNull(AuditTrail.classify("POST", "/assist/chat"));
        assertNull(AuditTrail.classify("GET", "/runs"), "ordinary reads are not audited");
    }

    /** R2-12 (operator, 2026-09-26): a POST that {@link CapabilityManifest#EXEMPTIONS} declares
     *  {@code read-shaped} is a read carrying a body and is classified like a GET — every dashboard tile's
     *  {@code /bi/query} was a {@code bi.created · data_mutation} row, ~120 a short session. */
    @Test
    void readShapedPostsFromTheManifestAreNotAudited() {
        assertNull(AuditTrail.classify("POST", "/bi/query"));
        assertNull(AuditTrail.classify("POST", "/db/query"));
        assertNull(AuditTrail.classify("POST", "/recon/breaks"));
        assertNull(AuditTrail.classify("POST", "/inv/investigations/inv-1/replay"), "a ([^/]+) group binds one segment");
        assertNull(AuditTrail.classify("POST", "/config/preview/parsing"),
                "read-shaped wins over the /config → configuration bucket");
    }

    /** The manifest pattern is a FULL match on method and path, so a persisting sibling of a read stays audited. */
    @Test
    void readShapedMatchIsAnchoredAndPerMethod() {
        assertEquals(new AuditTrail.Action("recon.created", "data_mutation"),
                AuditTrail.classify("POST", "/recon/promote"), "opens an Incident — a sibling of /recon/breaks");
        assertEquals(new AuditTrail.Action("component.created", "data_mutation"),
                AuditTrail.classify("POST", "/components/widget"));
        assertEquals(new AuditTrail.Action("component.updated", "data_mutation"),
                AuditTrail.classify("PUT", "/components/widget/x"));
        assertEquals(new AuditTrail.Action("inv.created", "data_mutation"),
                AuditTrail.classify("POST", "/inv/investigations/inv-1/ops"), "only /replay of that id is a read");
        assertNotNull(AuditTrail.classify("POST", "/inv/investigations/a/b/replay"), "([^/]+) does not span a slash");
        assertNotNull(AuditTrail.classify("POST", "/bi/query/extra"), "no prefix match");
        assertNotNull(AuditTrail.classify("PUT", "/bi/query"), "the entry is for POST only");
    }

    /** A read-shaped export is still an export: a GET export is audited (Category B), and a read is
     *  classified like a GET — so {@code POST /bundle/export} is {@code bundle.exported · export}, not dropped. */
    @Test
    void aReadShapedExportIsAuditedLikeAGetExport() {
        assertEquals(new AuditTrail.Action("bundle.exported", "export"), AuditTrail.classify("POST", "/bundle/export"));
    }

    /** AUDIT-AUTH-DUPLICATE-ROW-1: the /auth/* routes emit their own typed `authentication` rows through
     *  AuditTrail.authentication; classifying them here too wrote a second, mis-categorised row
     *  ("auth.created", data_mutation) for every sign-in, refresh and sign-out. */
    @Test
    void leavesTheAuthRoutesToTheirTypedEmitter() {
        assertNull(AuditTrail.classify("POST", "/auth/exchange"));
        assertNull(AuditTrail.classify("POST", "/auth/refresh"));
        assertNull(AuditTrail.classify("POST", "/auth/logout"));
    }
}
