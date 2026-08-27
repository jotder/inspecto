package com.gamma.service;

import com.gamma.api.PublicApi;

/**
 * A pipeline's identity + current state, for the Control API's listing.
 *
 * <p>Promoted out of {@code CollectorService} in 4.0.0. As a nested record it made
 * {@code CollectorService} an unavoidable import for every caller of
 * {@link ReadModel#pipelines()} — the return type's own spelling
 * ({@code CollectorService.PipelineView}) carried the dependency, so narrowing the receiver to
 * {@link ReadModel} could not remove it. Keep it top-level.
 */
@PublicApi(since = "4.0.0")
public record PipelineView(String name, String configPath, boolean paused, int committedBatches) {}
