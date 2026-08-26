package com.gamma.job;

/** The {@code db_maintenance} maintenance task: CHECKPOINT/VACUUM every per-space DuckDB store over its
 *  own live connection (single-writer — never a second connection): the acquisition ledger and the
 *  Consignment output registry, plus — when the hosting service configured them — the job-run and
 *  provenance projections (MNT-9). */
final class DbMaintenanceTask {

    private DbMaintenanceTask() {}

    static JobResult run(JobService host) {
        long t0 = System.nanoTime();
        com.gamma.acquire.AcquisitionLedgers.shared().maintenance();
        int stores = 1;
        // Reached through its own per-space registry rather than `host`, like the ledger above. It became a
        // default-on store with D1, so leaving it out would have shipped the first store that grows a row per
        // output file and is never checkpointed.
        var outputs = com.gamma.consignment.ConsignmentOutputStores.shared();
        if (outputs != null) {
            outputs.maintenance();
            stores++;
        }
        if (host != null) {
            var jobRuns = host.runStore();
            if (jobRuns.isPresent()) {
                jobRuns.get().maintenance();
                stores++;
            }
            var provenance = host.provenanceStore();
            if (provenance.isPresent()) {
                provenance.get().maintenance();
                stores++;
            }
        }
        return JobResult.ok("db_maintenance: " + stores + " store(s) maintenance completed"
                + (stores > 1 ? " (ledger + the configured per-space projections)" : " (ledger)"),
                (System.nanoTime() - t0) / 1_000_000L);
    }
}
