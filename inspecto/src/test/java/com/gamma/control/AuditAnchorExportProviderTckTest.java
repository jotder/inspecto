package com.gamma.control;

import com.gamma.job.MaintenanceTaskProvider;
import com.gamma.job.testkit.MaintenanceTaskProviderContract;

/** {@link AuditAnchorExportProvider} against the platform's MaintenanceTaskProvider TCK (MODULE-REORG-1 P5b). */
class AuditAnchorExportProviderTckTest extends MaintenanceTaskProviderContract {
    @Override
    protected MaintenanceTaskProvider provider() {
        return new AuditAnchorExportProvider();
    }
}
