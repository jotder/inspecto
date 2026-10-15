package com.gamma.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CL-12's ingest signal: a scope counts while open, a second close does not count twice. */
class IngestActivityTest {

    @Test
    void aScopeCountsWhileOpenAndClosingTwiceCountsOnce() {
        int base = IngestActivity.inFlight();
        IngestActivity.Scope a = IngestActivity.begin();
        IngestActivity.Scope b = IngestActivity.begin();
        assertEquals(base + 2, IngestActivity.inFlight());
        assertTrue(IngestActivity.active());
        a.close();
        a.close();
        assertEquals(base + 1, IngestActivity.inFlight(), "a second close of one scope must not release another's count");
        b.close();
        assertEquals(base, IngestActivity.inFlight());
        if (base == 0) assertFalse(IngestActivity.active());
    }
}
