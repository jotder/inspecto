package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Registry of known {@link PipelineNodeType}s: the {@link BuiltinNodeType built-ins} plus any contributed
 * via {@link ServiceLoader} ({@code META-INF/services/com.gamma.pipeline.PipelineNodeType}). A provider may
 * override a built-in by declaring the same {@link PipelineNodeType#type()}, so an edition can specialise a
 * node type without forking the core.
 *
 * <p>The classpath layer is built once at class-load and never changes. On top of it sits a
 * <b>pack overlay</b>: {@link #register} / {@link #deregister} let {@code JobPackManager} contribute node
 * types from a hot-deployed pack jar (pipeline spec gap 7), keyed by the owning jar so an unload takes
 * back exactly that pack's types. Reads go through a <b>volatile copy-on-write snapshot</b>, so the hot
 * paths ({@link #isKnown}, {@link #get}) stay lock-free and allocation-free.
 *
 * <p>⛔ <b>A pack may NOT override a built-in.</b> A classpath provider still may — that is an edition
 * specialising the core at build time, reviewed and shipped together. A jar dropped into a directory
 * silently redefining {@code sink.persistent} is a different thing entirely, and it would change what
 * every existing pipeline means. The registration is refused, which (pack loading being atomic) rejects
 * the whole pack.
 *
 * <p>⚠ Unloading a pack makes its types unknown again, so a stored pipeline naming one stops loading —
 * the same exposure a Job typed on an unloaded pack already has, and the reason a pack is normally
 * replaced rather than removed.
 */
@PublicApi(since = "4.0.0")
public final class PipelineNodeTypes {

    /** Built-ins + classpath providers. Fixed at class-load; the pack overlay layers on top of it. */
    private static final Map<String, PipelineNodeType> BASE = load();

    /** Pack-contributed types by discriminator, and the owning jar per discriminator. */
    private static final Map<String, PipelineNodeType> PACKED = new LinkedHashMap<>();
    private static final Map<String, String> OWNERS = new LinkedHashMap<>();

    /** BASE + PACKED, rebuilt on every overlay change so readers never synchronise. */
    private static volatile Map<String, PipelineNodeType> effective = BASE;

    private PipelineNodeTypes() {}

    private static Map<String, PipelineNodeType> load() {
        Map<String, PipelineNodeType> m = new LinkedHashMap<>();
        for (BuiltinNodeType b : BuiltinNodeType.values()) m.put(b.type(), b);
        // Providers are layered last so an edition can override a built-in of the same type().
        for (PipelineNodeType t : ServiceLoader.load(PipelineNodeType.class)) m.put(t.type(), t);
        return Map.copyOf(m);
    }

    /** Whether {@code type} is a built-in — the set a pack may never redefine. */
    private static boolean isBuiltin(String type) {
        for (BuiltinNodeType b : BuiltinNodeType.values()) if (b.type().equals(type)) return true;
        return false;
    }

    /**
     * Contribute a pack's node type. Refuses a built-in discriminator, and refuses one another loaded
     * pack already owns — first pack wins, so load order cannot silently change a deployment's meaning.
     *
     * @throws IllegalStateException if the type is a built-in or is already owned by another pack
     */
    public static synchronized void register(PipelineNodeType type, String owner) {
        String id = type.type();
        if (isBuiltin(id))
            throw new IllegalStateException("node type '" + id + "' is a built-in and cannot be replaced by a pack");
        String existing = OWNERS.get(id);
        if (existing != null && !existing.equals(owner))
            throw new IllegalStateException("node type '" + id + "' is already contributed by pack '" + existing + "'");
        PACKED.put(id, type);
        OWNERS.put(id, owner);
        effective = snapshot();
    }

    /** Take back every type {@code owner} contributed. A no-op for an owner that registered none. */
    public static synchronized void deregister(String owner) {
        if (owner == null || !OWNERS.containsValue(owner)) return;
        OWNERS.entrySet().removeIf(e -> {
            if (!owner.equals(e.getValue())) return false;
            PACKED.remove(e.getKey());
            return true;
        });
        effective = snapshot();
    }

    /** The pack that contributed {@code type}, or empty for a built-in/classpath registration. */
    public static Optional<String> ownerOf(String type) {
        return Optional.ofNullable(OWNERS.get(type));
    }

    private static Map<String, PipelineNodeType> snapshot() {
        if (PACKED.isEmpty()) return BASE;
        Map<String, PipelineNodeType> m = new LinkedHashMap<>(BASE);
        m.putAll(PACKED);
        return Map.copyOf(m);
    }

    /** The descriptor for {@code type}, if registered. */
    public static Optional<PipelineNodeType> get(String type) {
        return Optional.ofNullable(effective.get(type));
    }

    /** Whether {@code type} is a registered node type. */
    public static boolean isKnown(String type) {
        return effective.containsKey(type);
    }

    /** All registered node-type discriminators (built-ins + providers), in registration order. */
    public static Set<String> all() {
        return effective.keySet();
    }

    /**
     * All registered node-type <em>descriptors</em> (built-ins + providers), in registration order —
     * the source for the UI palette: each carries its {@link PipelineNodeType#category() category},
     * {@link PipelineNodeType#label() label}, {@link PipelineNodeType#description() description} and the
     * relationships it {@link PipelineNodeType#emits() emits}/{@link PipelineNodeType#accepts() accepts}.
     */
    public static Collection<PipelineNodeType> catalog() {
        return effective.values();
    }

    /** The {@link NodeCategory} of {@code type}, if registered. */
    public static Optional<NodeCategory> categoryOf(String type) {
        return get(type).map(PipelineNodeType::category);
    }

    /** Whether {@code type} is a registered node type in the given {@link NodeCategory} (e.g. any sink). */
    public static boolean isCategory(String type, NodeCategory category) {
        PipelineNodeType t = effective.get(type);
        return t != null && t.category() == category;
    }
}
