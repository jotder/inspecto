package com.gamma.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. */
class SignalTypeTest {

    @Test
    void signalTypesAreByteIdentical() {
        assertEquals("kpi.completeness.evaluated", SignalType.KPI_COMPLETENESS_EVALUATED);
        assertEquals("kpi.completeness.breached", SignalType.KPI_COMPLETENESS_BREACHED);
        assertEquals("kpi.completeness.unknown_streak", SignalType.KPI_COMPLETENESS_UNKNOWN_STREAK);
        assertEquals("alert.evaluate.completed", SignalType.ALERT_EVALUATE_COMPLETED);
        assertEquals("la.detect.completed", SignalType.LA_DETECT_COMPLETED);
        assertEquals("mail.sent", SignalType.MAIL_SENT);
        assertEquals("publish.postgres.completed", SignalType.PUBLISH_POSTGRES_COMPLETED);
        assertEquals("publish.postgres.refused", SignalType.PUBLISH_POSTGRES_REFUSED);
        assertEquals("report.attach.refused", SignalType.REPORT_ATTACH_REFUSED);
        assertEquals("sample.hello.completed", SignalType.SAMPLE_HELLO_COMPLETED);
        assertEquals("job.dataset.produced", SignalType.JOB_DATASET_PRODUCED);
        assertEquals("job.pack.loaded", SignalType.JOB_PACK_LOADED);
        assertEquals("job.pack.rejected", SignalType.JOB_PACK_REJECTED);
        assertEquals("job.pack.unloaded", SignalType.JOB_PACK_UNLOADED);
        assertEquals("job.run.started", SignalType.JOB_RUN_STARTED);
        assertEquals("job.run.completed", SignalType.JOB_RUN_COMPLETED);
        assertEquals("job.run.failed", SignalType.JOB_RUN_FAILED);
        assertEquals("job.run.rejected", SignalType.JOB_RUN_REJECTED);
        assertEquals("job.chain.cut", SignalType.JOB_CHAIN_CUT);
        assertEquals("pipeline.commit", SignalType.PIPELINE_COMMIT);
        assertEquals("job.signal.refused", SignalType.JOB_SIGNAL_REFUSED);
        assertEquals("la.index.build.completed", SignalType.LA_INDEX_BUILD_COMPLETED);
        assertEquals("risk.score.produced", SignalType.RISK_SCORE_PRODUCED);
        assertEquals("anomaly.score.produced", SignalType.ANOMALY_SCORE_PRODUCED);
        assertEquals("maintenance.filerepo.findings", SignalType.MAINTENANCE_FILEREPO_FINDINGS);
        assertEquals("maintenance.metadata.findings", SignalType.MAINTENANCE_METADATA_FINDINGS);
        assertEquals("maintenance.storage.threshold", SignalType.MAINTENANCE_STORAGE_THRESHOLD);
        assertEquals("maintenance.storage.trend", SignalType.MAINTENANCE_STORAGE_TREND);
        assertEquals("maintenance.scheduler.findings", SignalType.MAINTENANCE_SCHEDULER_FINDINGS);
    }
}
