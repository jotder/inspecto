package com.gamma.job;

import com.gamma.util.RunLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The boot-built registry behind {@link PlatformServices} (platform-services plan §3.1): the one
 * place engine facilities are bound to the service ids a consumer's {@code requires:} list may
 * declare. The host ({@code CollectorService}) constructs one per space at boot and registers the
 * built-in services; {@link #grant} then hands each consumer a view filtered to exactly its declared
 * ids — never the whole menu.
 *
 * <p>Fail-closed, both ways (plan §0.4): registering an already-bound id <em>or</em> interface
 * throws (the {@code JobPackManager} atomic load-or-reject posture, ahead of stage-3 contributed
 * services), and granting an unknown id throws naming it — a typo in a {@code requires:} list must
 * surface at registration/arming time, never as an empty lookup at fire time.
 */
public final class PlatformServiceRegistry {

    /** One bound service: the id authors declare in {@code requires:}, its public interface, the engine impl. */
    private record Binding(String id, Class<?> type, Object impl, String owner,
                           Function<RunLog, Object> standIn) {}

    /** What a granted view can say about contributed mutating services: the dry-run stand-in, if any. */
    interface StandIns {
        Optional<Object> standIn(Class<?> type, RunLog log);
    }

    private final Map<String, Binding> byId = new LinkedHashMap<>();
    /** Ids switched off (S3-2, operator 2026-10-04): in-memory only, so a restart re-enables every one. */
    private final Set<String> disabled = new java.util.HashSet<>();

    /** Bind {@code impl} under {@code id}; throws when the id or the interface is already bound. */
    public synchronized <T> void register(String id, Class<T> type, T impl) {
        if (byId.containsKey(id))
            throw new IllegalStateException("Platform Service id already bound: " + id);
        for (Binding b : byId.values())
            if (b.type() == type)
                throw new IllegalStateException("Platform Service interface already bound: "
                        + type.getName() + " (as id '" + b.id() + "')");
        byId.put(id, new Binding(id, type, impl, null, null));
    }

    /**
     * Bind a pack-contributed service under {@code owner} (S3-1). Refuses a colliding id or interface, an
     * interface the pack itself defines (D-12), an implementation that is not an instance of the interface,
     * and a mutating service without a usable dry-run stand-in. The caller rolls the pack back on a throw.
     */
    @SuppressWarnings("unchecked")
    public synchronized void registerContributed(String owner, ServiceProvider p) {
        String id = p.id();
        Class<?> type = p.type();
        if (id == null || id.isBlank() || type == null)
            throw new IllegalStateException(p.getClass().getName() + " has a blank service id or no interface");
        if (!type.isInterface() || type.getClassLoader() == p.getClass().getClassLoader())
            throw new IllegalStateException("service '" + id + "' interface " + type.getName()
                    + " must be an engine-published interface, not one the pack defines");
        Object impl = p.create();
        if (!type.isInstance(impl))
            throw new IllegalStateException("service '" + id + "' factory did not return a " + type.getName());
        Function<RunLog, Object> standIn = null;
        if (!p.readOnly()) {
            RunLog probe = new RunLog() {
                @Override public void info(String m, Object... kv) {}
                @Override public void warn(String m, Object... kv) {}
                @Override public void error(String m, Throwable t, Object... kv) {}
            };
            if (!type.isInstance(p.dryRun(probe)))
                throw new IllegalStateException("mutating service '" + id + "' has no dry-run stand-in "
                        + "(dryRun must return a " + type.getName() + ") and does not declare readOnly()");
            standIn = p::dryRun;
        }
        register(id, (Class<Object>) type, impl);
        byId.put(id, new Binding(id, type, impl, owner, standIn));
    }

    /**
     * Refuse every NEW grant of {@code id} (S3-2); a grant already handed out keeps working. Engine-internal
     * only (no route), not persisted. {@link #has} stays true, so a {@code requires:} registration check
     * still passes: it is the grant that refuses.
     */
    public synchronized void disable(String id) {
        if (!byId.containsKey(id))
            throw new IllegalStateException("Platform Service not available in this build: '" + id + "'");
        disabled.add(id);
    }

    /** Undo {@link #disable}; a no-op for an id that is not disabled. */
    public synchronized void enable(String id) { disabled.remove(id); }

    public synchronized boolean isDisabled(String id) { return disabled.contains(id); }

    /** Remove every service {@code owner} contributed; returns their ids. */
    public synchronized List<String> deregister(String owner) {
        List<String> removed = new ArrayList<>();
        byId.values().removeIf(b -> {
            boolean mine = owner.equals(b.owner());
            if (mine) removed.add(b.id());
            return mine;
        });
        disabled.removeAll(removed);   // a replaced pack's service comes back enabled
        return removed;
    }

    /** The packs that contributed any of {@code ids} (S3-2: a Run granted them pins these); built-ins have no owner. */
    public synchronized Set<String> ownersOf(Set<String> ids) {
        Set<String> owners = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            Binding b = byId.get(id);
            if (b != null && b.owner() != null) owners.add(b.owner());
        }
        return owners;
    }

    /** Whether {@code id} is available in this build — the S1-2 registration-time {@code requires:} check. */
    public synchronized boolean has(String id) {
        return byId.containsKey(id);
    }

    /** Every bound service id, for diagnostics and refusal messages. */
    public synchronized Set<String> ids() {
        return Set.copyOf(byId.keySet());
    }

    /** A view filtered to exactly {@code ids}; throws naming any id not bound in this build. */
    public synchronized PlatformServices grant(Set<String> ids) {
        Map<Class<?>, Object> granted = new LinkedHashMap<>();
        Map<Class<?>, Function<RunLog, Object>> standIns = new LinkedHashMap<>();
        for (String id : ids) {
            Binding b = byId.get(id);
            if (b == null)
                throw new IllegalStateException("Platform Service not available in this build: '" + id
                        + "' (available: " + byId.keySet() + ")");
            if (disabled.contains(id))
                throw new IllegalStateException("Platform Service disabled: '" + id + "'");
            granted.put(b.type(), b.impl());
            if (b.standIn() != null) standIns.put(b.type(), b.standIn());
        }
        return new Granted(Map.copyOf(granted), Map.copyOf(standIns));
    }

    private record Granted(Map<Class<?>, Object> byType, Map<Class<?>, Function<RunLog, Object>> standIns)
            implements PlatformServices, StandIns {
        @Override public Optional<Object> standIn(Class<?> type, RunLog log) {
            Function<RunLog, Object> f = standIns.get(type);
            return f == null ? Optional.empty() : Optional.ofNullable(f.apply(log));
        }
        @SuppressWarnings("unchecked")
        @Override public <T> Optional<T> find(Class<T> type) {
            return Optional.ofNullable((T) byType.get(type));
        }
        @Override public Set<Class<?>> granted() {
            return byType.keySet();
        }
    }
}
