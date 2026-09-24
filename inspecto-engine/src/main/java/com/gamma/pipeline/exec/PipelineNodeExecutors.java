package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.PipelineNodeTypes;

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
 * so a classpath provider may specialise a built-in verb as well as add a new one — a pack may not (S2-0,
 * see {@link #register}).
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
     * Contribute a pack's executor. Refuses (S2-0) an executor whose {@code type()} is a
     * {@link BuiltinNodeType built-in}, or is not a node type registered by the SAME pack — a pack may only
     * say how its OWN declared node type runs — and refuses one another loaded pack already owns (first
     * pack wins), so load order cannot silently change how a verb runs.
     *
     * <p>⛔ {@link RowShaper#shape} consults this registry BEFORE its built-in chain, so before S2-0 a jar
     * dropped in the packs dir could change how {@code transform.filter} runs for every pipeline — the very
     * thing {@link PipelineNodeTypes#register} already refused for descriptors. Specialising a built-in verb
     * stays possible for a CLASSPATH provider (an edition, reviewed and shipped with the build), never for a
     * pack. {@code JobPackManager} registers node types before executors, so the same-pack check sees them.
     *
     * @throws IllegalStateException if the type is a built-in, is not declared by {@code owner}'s node
     *                               types, or another pack already contributed an executor for it
     */
    public static synchronized void register(PipelineNodeExecutor executor, String owner) {
        String id = executor.type();
        for (BuiltinNodeType b : BuiltinNodeType.values())
            if (b.type().equals(id))
                throw new IllegalStateException(
                        "executor for '" + id + "' is a built-in verb and cannot be replaced by a pack");
        if (!PipelineNodeTypes.ownerOf(id).map(owner::equals).orElse(false))
            throw new IllegalStateException("executor for '" + id + "' has no node type declared by pack '"
                    + owner + "' — a pack may only execute its own node types");
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
