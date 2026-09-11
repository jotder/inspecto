package com.gamma.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link StoreHealth} — the per-space record of what each operational store family actually resolved to. */
class StoreHealthTest {

    @BeforeEach
    void reset() {
        StoreHealth.clearAll();
        System.clearProperty(Topology.PROPERTY);   // only ours — never a blanket sweep
    }

    @Test
    void recordsPerSpaceAndDoesNotLeakAcrossSpaces() {
        StoreHealth.degraded("alpha", "jobRuns", "jdbc:duckdb:a", "boom");
        StoreHealth.record("beta", "jobRuns", StoreHealth.Status.UP, "jdbc:duckdb:b", "open");

        assertEquals(StoreHealth.Status.DEGRADED, StoreHealth.of("alpha").get("jobRuns").status());
        assertEquals(StoreHealth.Status.UP, StoreHealth.of("beta").get("jobRuns").status());
        assertTrue(StoreHealth.anyDegraded("alpha"));
        assertFalse(StoreHealth.anyDegraded("beta"),
                "beta's store opened — alpha's failure must not be attributed to it");
    }

    /**
     * 🔴 The staleness guard. A family that fails once and succeeds on a later open must stop reporting
     * DEGRADED — otherwise {@code /health/details} would show a failure that has already been fixed, and an
     * operator who repaired their database would have no way to see that they had.
     */
    @Test
    void aLaterOutcomeReplacesTheEarlierOne() {
        StoreHealth.degraded("s", "events", "/tmp/ev", "disk full");
        assertTrue(StoreHealth.anyDegraded("s"));

        StoreHealth.record("s", "events", StoreHealth.Status.UP, "/tmp/ev", "rolling Parquet");

        assertEquals(1, StoreHealth.of("s").size(), "the family is replaced, not accumulated");
        assertEquals(StoreHealth.Status.UP, StoreHealth.of("s").get("events").status());
        assertFalse(StoreHealth.anyDegraded("s"), "a recovered family is no longer a failure");
    }

    /** Sorted, so {@code /health/details} renders in a stable order and an assertion over it cannot flake. */
    @Test
    void familiesAreOrderedByName() {
        StoreHealth.record("s", "status", StoreHealth.Status.UP, "u", "d");
        StoreHealth.record("s", "events", StoreHealth.Status.UP, "u", "d");
        StoreHealth.record("s", "acquisitionLedger", StoreHealth.Status.UP, "u", "d");

        assertEquals(List.of("acquisitionLedger", "events", "status"),
                List.copyOf(StoreHealth.of("s").keySet()));
    }

    @Test
    void clearDropsOneSpaceOnly() {
        StoreHealth.degraded("alpha", "objects", "u", "boom");
        StoreHealth.degraded("beta", "objects", "u", "boom");

        StoreHealth.clear("alpha");

        assertEquals(Map.of(), StoreHealth.of("alpha"));
        assertTrue(StoreHealth.anyDegraded("beta"));
    }

    /**
     * ⚠ An unknown space reads as empty, never null — {@code /health/details} calls this for every request,
     * including one bound to a space whose stores never recorded anything.
     */
    @Test
    void unknownAndNullSpacesReadEmptyRatherThanThrowing() {
        assertEquals(Map.of(), StoreHealth.of("never-seen"));
        assertEquals(Map.of(), StoreHealth.of(null));
        assertFalse(StoreHealth.anyDegraded("never-seen"));
        assertFalse(StoreHealth.anyDegraded(null));
    }

    // ---- the partitioned invariant (phase A) ------------------------------------------------------

    /**
     * 🔴 Phase A's invariant: across processes sharing one database, a store that silently falls back to
     * memory is split-brain, not degraded service — so recording a degradation must fail the boot.
     */
    @Test
    void aDegradationIsABootFailureInAPartitionedTopology() {
        System.setProperty(Topology.PROPERTY, "partitioned");
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> StoreHealth.degraded("s", "jobRuns", "jdbc:postgresql://db/x", "connection refused"));
            assertTrue(e.getMessage().contains("jobRuns"), "name the store: " + e.getMessage());
            assertTrue(e.getMessage().contains("connection refused"), "keep the cause: " + e.getMessage());
            assertEquals(StoreHealth.Status.DEGRADED, StoreHealth.of("s").get("jobRuns").status(),
                    "recorded before throwing, so anything that catches can still see what failed");
        } finally {
            System.clearProperty(Topology.PROPERTY);
        }
    }

    /**
     * The falsification arm. Without it the test above would pass equally if {@code record} threw on every
     * degradation regardless of topology — which would break every single-node deployment.
     */
    @Test
    void theSameDegradationIsToleratedOnASingleNode() {
        System.clearProperty(Topology.PROPERTY);
        assertDoesNotThrow(() -> StoreHealth.degraded("s", "jobRuns", "jdbc:duckdb:x", "disk full"));
        assertTrue(StoreHealth.anyDegraded("s"));
    }

    /** ⚠ Only DEGRADED is fatal — a family the operator never asked for must not stop a partitioned boot. */
    @Test
    void partitionedDoesNotRejectUnconfiguredOrHealthyFamilies() {
        System.setProperty(Topology.PROPERTY, "partitioned");
        try {
            assertDoesNotThrow(() -> StoreHealth.record("s", "events", StoreHealth.Status.NOT_CONFIGURED,
                    "memory", "operator did not ask for a durable event store"));
            assertDoesNotThrow(() -> StoreHealth.record("s", "status", StoreHealth.Status.UP,
                    "jdbc:postgresql://db/x", "open"));
        } finally {
            System.clearProperty(Topology.PROPERTY);
        }
    }

    /** A null space or family is dropped rather than filed under a null key that no reader could ever find. */
    @Test
    void recordIgnoresNullKeys() {
        StoreHealth.degraded(null, "jobRuns", "u", "boom");
        StoreHealth.degraded("s", null, "u", "boom");

        assertEquals(Map.of(), StoreHealth.of("s"));
        assertEquals(Map.of(), StoreHealth.of(null));
    }
}
