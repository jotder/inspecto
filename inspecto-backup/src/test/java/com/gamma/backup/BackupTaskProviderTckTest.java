package com.gamma.backup;

import com.gamma.job.MaintenanceTaskProvider;
import com.gamma.job.testkit.MaintenanceTaskProviderContract;

/** {@link BackupTaskProvider} against the platform's MaintenanceTaskProvider TCK (MODULE-REORG-1 P5b). */
class BackupTaskProviderTckTest extends MaintenanceTaskProviderContract {
    @Override
    protected MaintenanceTaskProvider provider() {
        return new BackupTaskProvider();
    }
}
