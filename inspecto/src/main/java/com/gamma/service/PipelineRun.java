package com.gamma.service;

import com.gamma.api.PublicApi;
import com.gamma.inspector.DryRunRejects;
import com.gamma.inspector.MultiCollectorProcessor;

import java.util.List;

/**
 * One manual pipeline run's identity + outcome, for the W5b async-trigger poll. {@code total}/{@code failed}
 * are the {@link MultiCollectorProcessor.RunResult} counts once terminal; {@code -1} while {@code RUNNING}.
 * {@code rejects} is set only on a finished DRY run (EXECUTION-RESIDUALS X4): each member that rejected records,
 * its first 100 (line + reason) and total; {@code null} for a real or failed run.
 *
 * <p>Promoted out of {@code CollectorService} in 4.0.0 — see {@link PipelineView} for why nesting a
 * public record inside a service class is a coupling trap.
 */
@PublicApi(since = "4.0.0")
public record PipelineRun(String runId, String pipeline, String trigger, String startedAt, String finishedAt,
                          String status, int total, int failed, String message,
                          List<DryRunRejects.MemberRejects> rejects) {}
