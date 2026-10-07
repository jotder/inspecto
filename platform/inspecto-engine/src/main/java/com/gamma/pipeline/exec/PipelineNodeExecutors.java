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
 * so a classpath provider may specialise a built-in verb as well as add a new one.
 *
 * <p>⛔ <b>Classpath-only (S2-3, D-2).</b> A raw-{@code Connection} executor writes its own SQL on the
 * batch connection, which is the third-party SQL ⛔ R2 keeps out of the engine. An edition shipped with
 * the build may still use it; a hot-deployed pack may not — {@code JobPackManager} rejects a pack that
 * carries one, and a pack runs its node types through {@link StepExecutor} instead. The pack overlay this
 * class had since pipeline spec gap 7 is therefore gone.
 *
 * <p>⚠ <b>Last provider wins</b> for a duplicated {@code type()}, matching the descriptor registry's rule.
 */
@PublicApi(since = "4.0.0")
public final class PipelineNodeExecutors {

    private static final Map<String, PipelineNodeExecutor> BASE = load();

    private PipelineNodeExecutors() {}

    private static Map<String, PipelineNodeExecutor> load() {
        Map<String, PipelineNodeExecutor> m = new LinkedHashMap<>();
        for (PipelineNodeExecutor e : ServiceLoader.load(PipelineNodeExecutor.class)) m.put(e.type(), e);
        return Map.copyOf(m);
    }

    /** The executor for {@code type}, if a classpath provider contributed one. */
    public static Optional<PipelineNodeExecutor> get(String type) {
        return Optional.ofNullable(BASE.get(type));
    }

    /** Every contributed node type, in discovery order — empty in a stock build. */
    public static Set<String> all() {
        return BASE.keySet();
    }
}
