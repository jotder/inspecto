package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.job.PlatformServices;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.util.RunLog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link StepExecutor}s (S2-3), the sibling of {@link PipelineNodeExecutors}: a classpath layer
 * fixed at class-load, plus an owner-keyed <b>pack overlay</b> that {@code JobPackManager} fills. Reads go
 * through a volatile copy-on-write snapshot, as in {@link PipelineNodeTypes}.
 *
 * <p>Each registration carries the Step's {@link Grant}: the {@code requires:} services, resolved once when
 * the pack loads against the loading Space's Platform Service registry. A classpath Step has no loading
 * Space, so it is granted nothing.
 *
 * <p>A kind whose run had to be abandoned (its thread ignored the interrupt past its deadline) is
 * {@link #disable disabled} until its pack is replaced or removed (D-7): every later run of it fails with
 * {@link StepFailure#STEP_DISABLED}.
 */
@PublicApi(since = "4.0.0")
public final class StepExecutors {

    /**
     * The stage-2 data-path ceiling: service ids a mid-walk Step may never be granted. A Step runs inside a
     * batch, before its commit, so it must not send outbound mail. (Dataset writes are the other half of the
     * ceiling; no Dataset-writing service exists yet.)
     */
    public static final Set<String> CEILING = Set.of("mail");

    /** Resolves a Step's granted services for one run. */
    @FunctionalInterface
    public interface Grant {
        /** The Step's services — dry-run-wrapped when {@code dryRun}, with would-be effects sent to {@code log}. */
        PlatformServices services(boolean dryRun, RunLog log);

        /** Grants nothing. */
        Grant NONE = (dryRun, log) -> PlatformServices.none();
    }

    /** One registered Step: its executor, its owning pack ({@code null} on the classpath) and its grant. */
    public record Registration(StepExecutor executor, String owner, Grant grant) {}

    private static final Map<String, Registration> BASE = load();
    private static final Map<String, Registration> PACKED = new LinkedHashMap<>();
    private static volatile Map<String, Registration> effective = BASE;
    private static final Map<String, String> DISABLED = new ConcurrentHashMap<>();

    private StepExecutors() {}

    private static Map<String, Registration> load() {
        Map<String, Registration> m = new LinkedHashMap<>();
        for (StepExecutor e : ServiceLoader.load(StepExecutor.class)) m.put(e.type(), new Registration(e, null, Grant.NONE));
        return Map.copyOf(m);
    }

    /**
     * Contribute a pack's Step. Refuses a built-in type, a type no node type of the SAME pack declares, and a
     * type another pack already runs — the S2-0 rules, now on the only pack-facing execution seam.
     *
     * @throws IllegalStateException on any of those
     */
    public static synchronized void register(StepExecutor executor, String owner, Grant grant) {
        String id = executor.type();
        for (BuiltinNodeType b : BuiltinNodeType.values())
            if (b.type().equals(id))
                throw new IllegalStateException("Step for '" + id + "' is a built-in verb and cannot be replaced by a pack");
        if (!PipelineNodeTypes.ownerOf(id).map(owner::equals).orElse(false))
            throw new IllegalStateException("Step for '" + id + "' has no node type declared by pack '" + owner
                    + "' — a pack may only execute its own node types");
        Registration existing = PACKED.get(id);
        if (existing != null && !existing.owner().equals(owner))
            throw new IllegalStateException("Step for '" + id + "' is already contributed by pack '" + existing.owner() + "'");
        PACKED.put(id, new Registration(executor, owner, grant == null ? Grant.NONE : grant));
        effective = snapshot();
    }

    /** Take back every Step {@code owner} contributed, and re-enable its kinds. No-op for an unknown owner. */
    public static synchronized void deregister(String owner) {
        if (owner == null) return;
        boolean changed = PACKED.values().removeIf(r -> {
            if (!owner.equals(r.owner())) return false;
            DISABLED.remove(r.executor().type());
            return true;
        });
        if (changed) effective = snapshot();
    }

    private static Map<String, Registration> snapshot() {
        if (PACKED.isEmpty()) return BASE;
        Map<String, Registration> m = new LinkedHashMap<>(BASE);
        m.putAll(PACKED);
        return Map.copyOf(m);
    }

    /** The Step registered for {@code type}, if any. */
    public static Optional<Registration> get(String type) {
        return Optional.ofNullable(effective.get(type));
    }

    /** Every Step-run node type. */
    public static Set<String> all() {
        return effective.keySet();
    }

    /** Disable {@code type} until its pack is replaced (D-7). */
    static void disable(String type, String reason) {
        DISABLED.put(type, reason);
    }

    /** Why {@code type} is disabled, or empty when it is not. */
    public static Optional<String> disabledReason(String type) {
        return Optional.ofNullable(DISABLED.get(type));
    }
}
