package com.gamma.alert;

import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PipelineConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The commit-hook contract of the Real-time Detection Add-on (PRODUCT-ASSEMBLY-1 PA-9d, D-PA38/D-PA43/D-PA44).
 *
 * <p>Implemented by the optional {@code inspecto-realtime} module and found through {@link java.util.ServiceLoader}; a
 * Space's host opens one {@link Session} per Space when an implementation is on the classpath. The session evaluates the
 * Space's windowed and Pattern Alert Rules ({@link AlertService#windowedRules}) on each commit of a
 * {@code trigger: stream} Pipeline that writes their Dataset ({@link Session#onCommit}), and judges absence Patterns on
 * the lane's clock floor while the Stream is drained ({@link Session#onStreamIdle}). Without the module no session
 * exists and those rules stay inert; with it, the session itself checks {@code EditionFeatures.present(realtime)} (the
 * signed entitlement may leave the Add-on out, and a renewed one may change that without a restart).
 */
public interface RealtimeDetection {

    /** Open the session of one Space. Never throws for a rule it cannot evaluate: it logs and leaves that rule inert. */
    Session open(Context context);

    /**
     * What a session reads from its Space.
     *
     * @param spaceId   the Space's id (metric labels, logs)
     * @param alerts    the Space's Alert engine: its windowed rules and the fold an evaluation raises through
     * @param pipelines the Space's loaded Pipelines, read live (a stream Pipeline's {@code dirs.database} is where its
     *                  committed files are, and its name or Stream is what a Dataset's {@code physicalRef} names)
     * @param registry  the Space's component registry folder ({@code <config>/registry}; {@code null} when it has none):
     *                  a Dataset's {@code physicalRef} is read there
     * @param stateDir  a folder the session owns for its durable state (the fired table, the evaluated ledger)
     */
    record Context(String spaceId, AlertService alerts, Supplier<List<PipelineConfig>> pipelines,
                   Supplier<Path> registry, Path stateDir) {}

    /** One Space's evaluation. Every method is safe to call from the publishing thread: it must not block a commit. */
    interface Session extends AutoCloseable {

        /** A terminal batch was published on the Space's bus; a SUCCESS (not dry) commit of a producer is evaluated. */
        void onCommit(ConsignmentEvent event);

        /** A stream Pipeline's lane found no backlog and nothing left to commit: absence Windows may close on the clock. */
        void onStreamIdle(String pipeline);

        /** What the session holds, per Dataset (rules, buffered rows, Watermark, inert reasons) - for logs and tests. */
        Map<String, Object> status();

        @Override
        void close();
    }
}
