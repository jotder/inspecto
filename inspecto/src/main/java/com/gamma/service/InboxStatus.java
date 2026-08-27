package com.gamma.service;

import com.gamma.api.PublicApi;
import com.gamma.etl.IngestProgress;
import com.gamma.etl.StepProgress;

/**
 * Inbox status for a pipeline (M3, file-processing visibility): how many inbox files are waiting
 * to be processed and whether the pipeline is currently ingesting.
 *
 * <p>Promoted out of {@code CollectorService} in 4.0.0 — see {@link PipelineView} for why nesting a
 * public record inside a service class is a coupling trap.
 *
 * @param pipeline name (normalised)
 * @param inbox    absolute poll-root path the files are scanned from
 * @param pending  files matching {@code processing.file_pattern} not yet processed (the candidate
 *                 set a poll cycle would pick up); {@code -1} if the scan failed
 * @param running  whether this pipeline is mid-ingest right now ("under processing")
 * @param current  the file being ingested right now ("file index of total"); {@code null}
 *                 when the pipeline is not mid-file (v4.1.0, per-file in-flight visibility)
 * @param step     the chain step the current Consignment is at ("step index of total"); {@code null}
 *                 when nothing is running (G6/S7, live step gauge — in-memory, poll-read)
 * @param oldestInboxAgeSeconds lag: seconds since the oldest waiting inbox file was modified
 *                 ({@code 0} when the inbox is empty); the same signal as the
 *                 {@code inspecto_inbox_oldest_seconds} metric (pipeline-graph §3.5 / T15)
 */
@PublicApi(since = "4.0.0")
public record InboxStatus(String pipeline, String inbox, int pending, boolean running,
                          IngestProgress.Snapshot current, StepProgress.Snapshot step,
                          double oldestInboxAgeSeconds) {}
