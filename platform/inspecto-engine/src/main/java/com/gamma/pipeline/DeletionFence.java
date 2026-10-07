package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deletion fence (§3.8 rule 4). Pipelines and jobs share one store and touch different slices at
 * different times; concurrent <b>append/read</b> on disjoint slices is safe. <b>The one hazard is
 * deletion</b> — a {@code maintenance}/delete job that removes a slice another driver is reading or
 * writing. This fence detects exactly that race: a delete of a store that has an <em>active</em>
 * producer or consumer right now.
 *
 * <p>Keyed by sink kind (§3.1): only a store that <b>rests on disk</b> ({@code sink.persistent} /
 * {@code sink.materialized}) can be a deletion hazard, so a store with no resting producer (e.g. a
 * {@code sink.view}, which persists nothing and is re-derived on demand) is never a conflict. A delete
 * is <em>clear</em> when the store's producers/consumers are all idle — the "quiet window" the model
 * relies on. This is pure over the IR + a running-set; the live wiring ({@code CollectorService}) supplies
 * the running flows and surfaces a {@code STORE_DELETE_CONFLICT} event/alert per conflict.
 */
@PublicApi(since = "4.0.0")
public final class DeletionFence {

    private DeletionFence() {}

    /** A delete of {@code store} that races at least one active (running) producer/consumer of that resting store. */
    public record Conflict(String store, List<String> activeProducers, List<String> activeConsumers) {
        public Conflict {
            activeProducers = List.copyOf(activeProducers);
            activeConsumers = List.copyOf(activeConsumers);
        }
    }

    /** The fence consultation seam injected into the job runtime: conflicts from deleting {@code stores} now. */
    @FunctionalInterface
    public interface Guard {
        List<Conflict> check(Collection<String> stores);
    }

    /**
     * Whether the fence can ever fire for a target store, and if not, why. {@link #check} treats the last three
     * identically — it skips them all in silence — which is correct for {@link #VIEW_ONLY} and {@link #CONSUMED_ONLY}
     * but hides {@link #UNMATCHED}, the typo class: a {@code store:} that names nothing makes the fence inert while
     * looking armed. This is the distinction {@code check} deliberately does not draw, exposed for a diagnostic.
     *
     * <p>⚠ Derived from configuration alone — the same basis {@link #check} uses. It says nothing about whether
     * bytes rest on disk <em>yet</em>: a configured pipeline that has never run still reports {@link #FENCED},
     * because the fence matches the authored topology, never the filesystem.
     */
    public enum Coverage {
        /** A resting producer is configured — the fence is live for this store and can report a conflict. */
        FENCED,
        /** Produced, but only by non-resting sinks ({@code sink.view}): nothing rests, so there is nothing to delete. */
        VIEW_ONLY,
        /** Consumed by a configured pipeline but produced by none — the name is attested, the producer is elsewhere. */
        CONSUMED_ONLY,
        /** No configured pipeline produces or consumes this store. The fence can never fire for it. */
        UNMATCHED
    }

    /**
     * Classify each of {@code targetStores} against the configured {@code pipelines} — the same topology
     * {@link #check} derives — without consulting the running set and without any side effect. A store that maps to
     * anything other than {@link Coverage#FENCED} is one {@code check} will skip; only {@link Coverage#UNMATCHED}
     * indicates the skip is unintended.
     *
     * @return one entry per distinct target store, in encounter order
     */
    public static Map<String, Coverage> coverage(Collection<String> targetStores,
                                                 Collection<PipelineGraph> pipelines) {
        Topology t = Topology.of(pipelines);
        Map<String, Coverage> out = new LinkedHashMap<>();
        for (String store : targetStores) {
            if (out.containsKey(store)) continue;
            out.put(store, t.coverageOf(store));
        }
        return out;
    }

    /**
     * Conflicts that would result from deleting {@code targetStores} given the configured {@code flows} and
     * the names of the flows currently {@code running}. A store with no resting producer is skipped (no
     * bytes to delete); otherwise a conflict is reported when any resting producer — or any consumer — of
     * that store is currently running.
     */
    public static List<Conflict> check(Collection<String> targetStores,
                                       Collection<PipelineGraph> flows, Set<String> running) {
        Topology t = Topology.of(flows);
        List<Conflict> out = new ArrayList<>();
        for (String store : targetStores) {
            List<String> producers = t.restingProducers.getOrDefault(store, List.of());
            if (producers.isEmpty()) continue;                       // nothing rests on disk → no deletion hazard
            List<String> activeProducers = producers.stream().filter(running::contains).toList();
            List<String> activeConsumers = t.consumers.getOrDefault(store, List.of()).stream()
                    .filter(running::contains).toList();
            if (!activeProducers.isEmpty() || !activeConsumers.isEmpty())
                out.add(new Conflict(store, activeProducers, activeConsumers));
        }
        return out;
    }

    /**
     * The store→pipeline index both {@link #check} and {@link #coverage} read, so the one definition of "has a
     * resting producer" cannot drift between the fence and any diagnostic reporting on it.
     */
    private record Topology(Map<String, List<String>> restingProducers,
                            Map<String, List<String>> anyProducers,
                            Map<String, List<String>> consumers) {

        static Topology of(Collection<PipelineGraph> pipelines) {
            Map<String, List<String>> resting = new LinkedHashMap<>();
            Map<String, List<String>> any = new LinkedHashMap<>();
            Map<String, List<String>> consumed = new LinkedHashMap<>();
            for (PipelineGraph g : pipelines) {
                for (PipelineStores.Produced p : PipelineStores.producedStores(g)) {
                    any.computeIfAbsent(p.store(), k -> new ArrayList<>()).add(g.name());
                    if (p.restsOnDisk())
                        resting.computeIfAbsent(p.store(), k -> new ArrayList<>()).add(g.name());
                }
                for (String s : PipelineStores.consumed(g)) {
                    consumed.computeIfAbsent(s, k -> new ArrayList<>()).add(g.name());
                }
            }
            return new Topology(resting, any, consumed);
        }

        Coverage coverageOf(String store) {
            if (!restingProducers.getOrDefault(store, List.of()).isEmpty()) return Coverage.FENCED;
            if (!anyProducers.getOrDefault(store, List.of()).isEmpty()) return Coverage.VIEW_ONLY;
            if (!consumers.getOrDefault(store, List.of()).isEmpty()) return Coverage.CONSUMED_ONLY;
            return Coverage.UNMATCHED;
        }
    }
}
