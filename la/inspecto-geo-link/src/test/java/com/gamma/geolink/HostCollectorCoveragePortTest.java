package com.gamma.geolink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The event-day parse of a lineage {@code partition}: Hive form, the {@code dt=} form, and no day at all. */
class HostCollectorCoveragePortTest {

    @Test
    void hiveFormIsZeroPadded() {
        assertEquals("2026-09-03", HostCollectorCoveragePort.day("year=2026/month=9/day=3"));
        assertEquals("2026-09-03", HostCollectorCoveragePort.day("calls/year=2026/month=09/day=03"));
    }

    @Test
    void dtFormIsTakenVerbatim() {
        assertEquals("2026-09-03", HostCollectorCoveragePort.day("dt=2026-09-03"));
        assertEquals("2026-09-03", HostCollectorCoveragePort.day("calls/dt=2026-09-03/part-0"));
    }

    @Test
    void noDayIsNull() {
        assertNull(HostCollectorCoveragePort.day(null));
        assertNull(HostCollectorCoveragePort.day("region=north"));
        assertNull(HostCollectorCoveragePort.day("dt=2026-9-3"));
    }

    @Test
    void originsAreTheHeadSegmentLowerCased() {
        assertEquals(java.util.Set.of("calls", "other"), HostCollectorCoveragePort.origins(java.util.Set.of("Calls/events", "other")));
    }

    @Test
    void aSinglePipelineOwnsItsOwnNameButASelectorPipelineNever(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var cfg = com.gamma.etl.PipelineConfig.loadForValidation(com.gamma.etl.PipelineConfigBatchTest.writePipeline(dir, "").toString());
        assertTrue(HostCollectorCoveragePort.ownsOrigin(cfg, java.util.Set.of("mini_etl")));
        assertFalse(HostCollectorCoveragePort.ownsOrigin(cfg, java.util.Set.of("another")));
    }
}
