package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;

import java.time.Duration;
import java.util.Set;

/**
 * <b>The pack-facing execution half of a contributed Step kind</b> (Platform Services Stage 2, S2-3). A
 * provider runs one {@code EXECUTED} node type as Java: it reads the node's input through
 * {@link StepContext#in()} and writes rows through {@link StepContext#emit}. It never holds a
 * {@link java.sql.Connection} — the engine owns every statement and every appender, which is what lets the
 * watchdog cancel a Step and lets a failed Step leave no tables behind.
 *
 * <p><b>Registration.</b> List the provider in {@code META-INF/services/com.gamma.pipeline.exec.StepExecutor}
 * inside a Job Pack, beside the {@link com.gamma.pipeline.PipelineNodeType} it runs; the descriptor must
 * declare {@link com.gamma.pipeline.ExecutionMode#EXECUTED} and a {@code transform.*} type. A pack may run
 * only its own node types, never a built-in. ⛔ A pack carrying the raw-{@code Connection}
 * {@link PipelineNodeExecutor} is rejected whole (D-2); that seam is classpath-only.
 *
 * <p><b>Cost.</b> A row-at-a-time Step runs ~30–40× slower than a fused SQL path (S2-2, about 0.6–0.9 µs per
 * cell). Anything SQL can express belongs in a built-in {@code LOWERED} verb; this seam is for logic SQL
 * cannot express.
 */
@PublicApi(since = "4.0.0")
public interface StepExecutor {

    /** The node {@code type} this Step runs, e.g. {@code "transform.acme_score"}. */
    String type();

    /**
     * The Platform Service ids this Step needs ({@code requires:}). Resolved fail-closed when the pack
     * loads: an unknown id, or one above the stage-2 data-path ceiling ({@link StepExecutors#CEILING}),
     * rejects the pack. Only these services are visible through {@link StepContext#services()}.
     */
    default Set<String> requires() {
        return Set.of();
    }

    /**
     * This kind's default deadline. A node may lower or raise it with a {@code timeout_seconds} attribute;
     * the system ceiling ({@code -Dpipeline.step.timeoutCeilingSeconds}, default 30 min) caps both.
     */
    default Duration timeout() {
        return StepRunner.DEFAULT_TIMEOUT;
    }

    /**
     * Run the Step once over its node's input. A throw fails the batch and the engine drops every table the
     * Step created; reject rows by emitting them to a declared {@code reject:<reason>} relation instead.
     * Honour {@link Thread#interrupt()}: a Step that ignores it past its deadline is abandoned and its kind
     * disabled until the pack is replaced.
     */
    void execute(StepContext ctx) throws Exception;
}
