package com.gamma.opsjob;

import com.gamma.job.MaintenanceTaskProvider;
import com.gamma.job.testkit.MaintenanceTaskProviderContract;

/** {@link OpsMaintenanceTasks} against the platform's MaintenanceTaskProvider TCK (MODULE-REORG-1 P5b). */
class OpsMaintenanceTasksTckTest extends MaintenanceTaskProviderContract {
    @Override
    protected MaintenanceTaskProvider provider() {
        return new OpsMaintenanceTasks();
    }

    @Override
    protected java.util.Map<String, String> params() {
        return java.util.Map.of("retention_days", "30");
    }
}
