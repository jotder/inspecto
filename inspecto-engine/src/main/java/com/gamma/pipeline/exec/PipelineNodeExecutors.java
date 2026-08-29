package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Registry of {@link PipelineNodeExecutor} providers, discovered through {@link ServiceLoader}
 * ({@code META-INF/services/com.gamma.pipeline.exec.PipelineNodeExecutor}).
 *
 * <p>The sibling of {@link com.gamma.pipeline.PipelineNodeTypes}: that one answers <em>what a node type
 * is</em>, this one answers <em>how it runs</em>. {@link RowShaper#shape} consults this registry first,
 * so a provider may specialise a built-in verb as well as add a new one.
 *
 * <p>⚠ <b>Last provider wins</b> for a duplicated {@code type()}, matching the descriptor registry's
 * rule. The registry is built once at class-load and is immutable thereafter.
 */
@PublicApi(since = "4.0.0")
public final class PipelineNodeExecutors {

    private static final Map<String, PipelineNodeExecutor> REGISTRY = load();

    private PipelineNodeExecutors() {}

    private static Map<String, PipelineNodeExecutor> load() {
        Map<String, PipelineNodeExecutor> m = new LinkedHashMap<>();
        for (PipelineNodeExecutor e : ServiceLoader.load(PipelineNodeExecutor.class)) m.put(e.type(), e);
        return Map.copyOf(m);
    }

    /** The executor for {@code type}, if a provider contributed one. */
    public static Optional<PipelineNodeExecutor> get(String type) {
        return Optional.ofNullable(REGISTRY.get(type));
    }

    /** Every contributed node type, in discovery order — empty in a stock build. */
    public static Set<String> all() {
        return REGISTRY.keySet();
    }
}
