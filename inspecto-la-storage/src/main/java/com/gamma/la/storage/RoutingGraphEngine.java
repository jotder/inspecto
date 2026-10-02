package com.gamma.la.storage;

import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphEngine;
import com.gamma.la.core.GraphInput;
import com.gamma.la.core.GraphResult;
import com.gamma.la.graph.RunControl;

import java.util.Map;
import java.util.Set;

/**
 * One {@link GraphEngine} that delegates by the TYPE of the input (D-3 step 7, design 4.2): a {@link GraphInput.Materialised}
 * graph goes to the in-memory engine exactly as before, an {@link GraphInput.IndexRef} to the index engine. It routes on the
 * input alone - never on size, never as a fallback - so a caller who chose {@code index} never lands on the Working Set path and
 * one who chose {@code workingSet} never touches the index. {@link GraphEngine#engineIdFor} names the delegate, so a run reports
 * the engine that really ran it.
 */
public final class RoutingGraphEngine implements GraphEngine {

    private final GraphEngine memory;
    private final GraphEngine index;

    public RoutingGraphEngine(GraphEngine memory, GraphEngine index) {
        this.memory = memory;
        this.index = index;
    }

    /** The in-memory engine's id: what the catalogue's single {@code engine} field has always said. */
    @Override
    public String engineId() {
        return memory.engineId();
    }

    @Override
    public String engineIdFor(GraphInput input) {
        return delegate(input).engineId();
    }

    /** Everything the in-memory engine can run (the index engine's algorithms are a subset). */
    @Override
    public Set<Algorithm> supported() {
        return memory.supported();
    }

    /** The algorithms the index engine answers. */
    public Set<Algorithm> indexSupported() {
        return index.supported();
    }

    @Override
    public GraphResult run(Algorithm algorithm, Map<String, Object> params, GraphInput input, RunControl ctl) {
        return delegate(input).run(algorithm, params, input, ctl);
    }

    private GraphEngine delegate(GraphInput input) {
        return switch (input) {
            case GraphInput.Materialised m -> memory;
            case GraphInput.IndexRef r -> index;
        };
    }
}
