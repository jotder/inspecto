package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineNodeTypes;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * S2-0: the pipeline-run half of the Job Pack in-flight-Run quiesce. {@code JobService} pins a pack for
 * the duration of a Job's {@code run(ctx)}; a PIPELINE run executes pack code too — a pack's node type
 * (its descriptor) and its executor — without going through that path, so the pack could be unloaded and
 * its classloader closed mid-run. {@link PipelineExecutor} takes a {@link #acquire lease} for every
 * pack owning a node type in the graph, for the whole walk, released in a {@code finally}.
 *
 * <p>The counting itself stays in {@code JobPackManager} (reference-tracked per owner, deferring the
 * close until the count reaches zero); it installs itself here as a {@link Leaser} and uninstalls on
 * close. The engine layer cannot see {@code com.gamma.job}, hence the seam. Every installed leaser is
 * pinned — one per Space's {@code JobService}, all sharing the process-wide node registries.
 *
 * <p>Node-type owners are enough: a pack executor is only accepted for its own pack's node type
 * ({@link PipelineNodeExecutors#register}), so no pack code runs in a walk whose node types it does not own.
 */
public final class PackRunLeases {

    /** Reference-tracked run pinning, keyed by pack owner (the jar filename). */
    public interface Leaser {
        void acquireRun(String owner);
        void releaseRun(String owner);
    }

    /** A held lease; {@link #close} releases it. Never throws. */
    public interface Lease extends AutoCloseable {
        @Override void close();
    }

    private static final Set<Leaser> LEASERS = new CopyOnWriteArraySet<>();
    private static final Lease NONE = () -> {};

    private PackRunLeases() {}

    public static void install(Leaser leaser) { LEASERS.add(leaser); }

    public static void uninstall(Leaser leaser) { LEASERS.remove(leaser); }

    /** Pin every pack owning a node type in {@code g} on every installed leaser. A no-op lease when none do. */
    static Lease acquire(PipelineGraph g) {
        Set<String> owners = new LinkedHashSet<>();
        for (PipelineNode n : g.nodes()) PipelineNodeTypes.ownerOf(n.type()).ifPresent(owners::add);
        if (owners.isEmpty() || LEASERS.isEmpty()) return NONE;
        List<Leaser> held = List.copyOf(LEASERS);
        for (Leaser l : held) for (String o : owners) l.acquireRun(o);
        return () -> { for (Leaser l : held) for (String o : owners) l.releaseRun(o); };
    }
}
