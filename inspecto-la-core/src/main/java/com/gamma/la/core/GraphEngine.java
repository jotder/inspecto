package com.gamma.la.core;

import com.gamma.la.graph.GraphAborted;
import com.gamma.la.graph.RunControl;

import java.util.Map;
import java.util.Set;

/**
 * The graph-engine SPI (LA separation D-4, design §2.1): one implementation per backing store. {@link InMemoryGraphEngine}
 * runs the {@code inspecto-la-graph} ports over a materialised graph and is the whole of D-4; an index-backed engine
 * (D-3) would implement the same interface. Nothing here mentions memory.
 */
public interface GraphEngine {

    /** Stable engine id: {@code "memory"} for {@link InMemoryGraphEngine}. */
    String engineId();

    /** The algorithms this engine can run. */
    Set<Algorithm> supported();

    /**
     * Runs {@code algorithm}. {@code params} are validated first ({@link Algorithm#resolve}); a bad request throws
     * {@link InvalidGraphRequest} before any work. {@code ctl} (null = {@link RunControl#NONE}) carries cancellation, the
     * deadline, the work budget and progress; an abort surfaces as {@link GraphAborted}, never as a partial result.
     *
     * @throws InvalidGraphRequest the request is not runnable as asked (the route layer's 422)
     * @throws GraphAborted        cancelled, past its deadline, or over its work budget
     */
    GraphResult run(Algorithm algorithm, Map<String, Object> params, GraphInput input, RunControl ctl);
}
