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
 * rule, for the classpath layer — which is fixed at class-load.
 *
 * <p>On top of it sits the same <b>pack overlay</b> as {@link com.gamma.pipeline.PipelineNodeTypes}
 * (pipeline spec gap 7): {@link #register}/{@link #deregister}, owner-keyed, read through a volatile
 * copy-on-write snapshot. A pack contributing a descriptor without an executor is legal — the type is
 * then authorable and executed by whatever engine path already handles its shape — so the two overlays
 * are deliberately independent rather than one paired registration.
 */
@PublicApi(since = "4.0.0")
public final class PipelineNodeExecutors {

    private static final Map<String, PipelineNodeExecutor> BASE = load();
    private static final Map<String, PipelineNodeExecutor> PACKED = new LinkedHashMap<>();
    private static final Map<String, String> OWNERS = new LinkedHashMap<>();
    private static volatile Map<String, PipelineNodeExecutor> effective = BASE;

    private PipelineNodeExecutors() {}

    /**
     * Contribute a pack's executor. Refuses one another loaded pack already owns (first pack wins), so
     * load order cannot silently change how a verb runs.
     *
     * <p>⚠ Unlike the descriptor registry this does NOT refuse a built-in verb: specialising how an
     * existing verb runs is exactly what {@code RowShaper.shape} consults this registry for, and refusing
     * it would remove the one thing a processing pack is for. It stays owner-keyed, so an unload puts the
     * built-in behaviour back.
     *
     * @throws IllegalStateException if another pack already contributed an executor for this type
     */
    public static synchronized void register(PipelineNodeExecutor executor, String owner) {
        String id = executor.type();
        String existing = OWNERS.get(id);
        if (existing != null && !existing.equals(owner))
            throw new IllegalStateException(
                    "executor for '" + id + "' is already contributed by pack '" + existing + "'");
        PACKED.put(id, executor);
        OWNERS.put(id, owner);
        effective = snapshot();
    }

    /** Take back every executor {@code owner} contributed. A no-op for an owner that registered none. */
    public static synchronized void deregister(String owner) {
        if (owner == null || !OWNERS.containsValue(owner)) return;
        OWNERS.entrySet().removeIf(e -> {
            if (!owner.equals(e.getValue())) return false;
            PACKED.remove(e.getKey());
            return true;
        });
        effective = snapshot();
    }

    private static Map<String, PipelineNodeExecutor> snapshot() {
        if (PACKED.isEmpty()) return BASE;
        Map<String, PipelineNodeExecutor> m = new LinkedHashMap<>(BASE);
        m.putAll(PACKED);
        return Map.copyOf(m);
    }

    private static Map<String, PipelineNodeExecutor> load() {
        Map<String, PipelineNodeExecutor> m = new LinkedHashMap<>();
        for (PipelineNodeExecutor e : ServiceLoader.load(PipelineNodeExecutor.class)) m.put(e.type(), e);
        return Map.copyOf(m);
    }

    /** The executor for {@code type}, if a provider contributed one. */
    public static Optional<PipelineNodeExecutor> get(String type) {
        return Optional.ofNullable(effective.get(type));
    }

    /** Every contributed node type, in discovery order — empty in a stock build. */
    public static Set<String> all() {
        return effective.keySet();
    }
}
