package com.gamma.decision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * The registry of {@link ConsequenceProvider}s: the built-ins ({@link BuiltInConsequences}) first, then every
 * provider an installed module contributes through {@link ServiceLoader}. Fail-soft: a provider that cannot be
 * loaded, or that reuses an already-registered id (a module can never displace a built-in), is logged and
 * skipped — it never stops the host booting.
 */
public final class Consequences {

    private static final Logger log = LoggerFactory.getLogger(Consequences.class);

    private final Map<String, ConsequenceProvider> byId = new LinkedHashMap<>();

    private Consequences() {}

    /** Built-ins only (bare test registries). */
    public static Consequences builtIns() {
        Consequences c = new Consequences();
        BuiltInConsequences.all().forEach(c::add);
        return c;
    }

    /** Built-ins plus every {@link ConsequenceProvider} service on {@code loader}. */
    public static Consequences load(ClassLoader loader) {
        Consequences c = builtIns();
        var it = ServiceLoader.load(ConsequenceProvider.class, loader).iterator();
        while (true) {
            try {
                if (!it.hasNext()) break;
                c.add(it.next());
            } catch (ServiceConfigurationError | RuntimeException e) {
                log.warn("[DECISION] a ConsequenceProvider could not be loaded - skipped: {}", e.toString());
            }
        }
        return c;
    }

    private void add(ConsequenceProvider p) {
        String id = p.id();
        if (id == null || id.isBlank() || byId.containsKey(id)) {
            log.warn("[DECISION] ConsequenceProvider {} refused: blank or already-registered id '{}'", p.getClass().getName(), id);
            return;
        }
        byId.put(id, p);
    }

    public Optional<ConsequenceProvider> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Every provider, built-ins first, then contributed ones in load order. */
    public List<ConsequenceProvider> all() {
        return new ArrayList<>(byId.values());
    }
}
