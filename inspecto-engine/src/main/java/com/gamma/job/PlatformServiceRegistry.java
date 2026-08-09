package com.gamma.job;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private record Binding(String id, Class<?> type, Object impl) {}

    private final Map<String, Binding> byId = new LinkedHashMap<>();

    /** Bind {@code impl} under {@code id}; throws when the id or the interface is already bound. */
    public synchronized <T> void register(String id, Class<T> type, T impl) {
        if (byId.containsKey(id))
            throw new IllegalStateException("Platform Service id already bound: " + id);
        for (Binding b : byId.values())
            if (b.type() == type)
                throw new IllegalStateException("Platform Service interface already bound: "
                        + type.getName() + " (as id '" + b.id() + "')");
        byId.put(id, new Binding(id, type, impl));
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
        for (String id : ids) {
            Binding b = byId.get(id);
            if (b == null)
                throw new IllegalStateException("Platform Service not available in this build: '" + id
                        + "' (available: " + byId.keySet() + ")");
            granted.put(b.type(), b.impl());
        }
        return new Granted(Map.copyOf(granted));
    }

    private record Granted(Map<Class<?>, Object> byType) implements PlatformServices {
        @SuppressWarnings("unchecked")
        @Override public <T> Optional<T> find(Class<T> type) {
            return Optional.ofNullable((T) byType.get(type));
        }
        @Override public Set<Class<?>> granted() {
            return byType.keySet();
        }
    }
}
