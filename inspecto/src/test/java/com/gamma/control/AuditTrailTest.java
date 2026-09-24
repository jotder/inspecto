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
