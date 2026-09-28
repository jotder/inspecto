package com.gamma.pipeline;

import com.gamma.api.PublicApi;

/**
 * How a {@link PipelineNodeType} executes (Platform Services Stage 2, slice S2-1).
 *
 * <ul>
 *   <li>{@link #LOWERED} — the engine compiles the node to SQL inside the batch's own query. Every
 *       {@link BuiltinNodeType} is {@code LOWERED}. ⛔ A <b>pack</b> may not declare it: third-party SQL
 *       fragments stay out of the engine's query until a SQL-fragment guard exists (archived plan R2), so
 *       {@link PipelineNodeTypes#register} rejects the pack whole.</li>
 *   <li>{@link #EXECUTED} — the node runs as Java over its input relation. The only mode a pack may
 *       declare. A classpath provider may declare either.</li>
 * </ul>
 */
@PublicApi(since = "4.0.0")
public enum ExecutionMode { LOWERED, EXECUTED }
