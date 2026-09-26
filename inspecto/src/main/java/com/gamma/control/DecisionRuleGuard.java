package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.BundleImporter;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>The ONE gate every writer of a {@code decision-rule} component runs</b> ({@code ASSURE-ACTION-REQUESTS-1},
 * round-2 finding 1). A Decision Rule with an {@code invoke-api} consequence raises Action Requests whenever it is
 * applied, so writing one is proposing outbound calls by proxy — and {@code decision-rule} is a
 * {@code ComponentStore.WRITABLE_TYPES} kind, reachable through {@code /decision-rules}, {@code /components/decision-rule},
 * version restore, {@code /bundle/import}, the raw {@code /import} and a new Space's bundle. Every one of them calls
 * {@link #prepare} (or, for a raw file bundle, {@link #guardImport}) BEFORE its first write, and
 * {@code DecisionRuleWritersTest} enumerates the repo-wide writer inventory to pin that.
 *
 * <p>{@link #prepare}:
 * <ol>
 *   <li>an {@code invoke-api} consequence needs the writer to hold {@code canWorkIncidents} (403), and its params
 *       must name a registered {@code https} Connection — {@code params.connection} required, a legacy
 *       {@code params.url} refused (422);</li>
 *   <li>{@code createdBy} / {@code updatedBy} are SERVER-STAMPED — any body value is discarded: {@code updatedBy} is
 *       the writer, {@code createdBy} the stored rule's (or the writer's on a create).</li>
 * </ol>
 *
 * <p><b>Makers come from the version history</b> ({@link #makers}): everyone whose stamped {@code updatedBy} is on a
 * version since the rule's invoke-api consequences (connection, method, payload) last changed — the current version
 * and each archived one back to the change. They are all co-authors of every Action Request the rule raises and
 * none may approve one. When the history cannot say (an unstamped version on the chain, or a chain that runs past
 * the retained history), the answer is unknown and the rule raises nothing — fail closed.
 */
public final class DecisionRuleGuard {

    private DecisionRuleGuard() {}

    static final String TYPE = "decision-rule";
    private static final String DIR_PREFIX = "registry/" + ComponentRegistry.dirForType(TYPE).orElse("decision-rules") + "/";

    /** Validate and stamp one write of a rule; returns the content to persist. {@code prev}: the stored rule or null. */
    static Map<String, Object> prepare(HttpExchange ex, Map<String, Object> content, Map<String, Object> prev) {
        return prepare(ex, content, prev, Map.of(), true);
    }

    /**
     * {@link #prepare} for a bundle: {@code carried} are the Connections (id → connector) the same bundle brings,
     * which count as registered; {@code live} says whether this Space's registry applies (not for a new Space).
     */
    static Map<String, Object> prepare(HttpExchange ex, Map<String, Object> content, Map<String, Object> prev,
                                       Map<String, String> carried, boolean live) {
        checkInvokeApi(ex, content, carried, live);
        Map<String, Object> out = new LinkedHashMap<>(content);
        String actor = ApiContext.actor(ex);
        Object created = prev == null ? null : prev.get("createdBy");
        out.put("createdBy", created == null ? actor : created);
        out.put("updatedBy", actor);
        return out;
    }

    /**
     * A server-side rewrite of a stored rule that must not touch what it sends (a pipeline rename moving
     * {@code target}): refused if the invoke-api consequences changed. The stamps are the stored rule's — the
     * content never came from a request body.
     */
    static void checkTargetRewrite(Map<String, Object> before, Map<String, Object> after) {
        if (!java.util.Objects.equals(signature(before), signature(after)))
            throw new IllegalStateException("a rewrite of Decision Rule '" + before.get("name")
                    + "' may not change its invoke-api consequences");
    }

    /**
     * A raw config bundle (the {@code /import} zip, a new Space's bundle): every {@code registry/decision-rules/*.toon}
     * entry is {@link #prepare}d against the stored rule of that name and re-encoded, all BEFORE the first write —
     * one refusal refuses the whole import. Returns the bundle to write.
     */
    static BundleImporter.Bundle guardImport(HttpExchange ex, Path config, BundleImporter.Bundle bundle) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(bundle.configEntries());
        Map<String, String> carried = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            if (!e.getKey().endsWith("_connection.toon")) continue;
            try {
                if (ConfigCodec.toMap(new String(e.getValue(), StandardCharsets.UTF_8)).get("connection") instanceof Map<?, ?> c
                        && c.get("id") != null)
                    carried.put(String.valueOf(c.get("id")), String.valueOf(c.get("connector")));
            } catch (RuntimeException unreadable) {
                // not a Connection this bundle can vouch for
            }
        }
        boolean changed = false;
        ComponentStore store = config == null ? null : new ComponentStore(config.resolve("registry"));
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            String rel = e.getKey().replace('\\', '/').toLowerCase(Locale.ROOT);
            while (rel.startsWith("./")) rel = rel.substring(2);
            if (!rel.startsWith(DIR_PREFIX)) continue;
            Map<String, Object> content;
            try {
                content = ConfigCodec.toMap(new String(e.getValue(), StandardCharsets.UTF_8));
            } catch (RuntimeException unparseable) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "import entry '" + e.getKey()
                        + "' is not a readable Decision Rule");
            }
            String file = rel.substring(rel.lastIndexOf('/') + 1);
            String name = file.contains(".") ? file.substring(0, file.indexOf('.')) : file;
            Map<String, Object> prev = null;
            if (store != null) {
                try {
                    prev = store.get(TYPE, name).map(ComponentRegistry.Component::content).orElse(null);
                } catch (RuntimeException unknownOrBadId) {
                    prev = null;
                }
            }
            Map<String, Object> prepared = prepare(ex, content, prev, carried, config != null);
            entries.put(e.getKey(), ConfigCodec.toToon(prepared).getBytes(StandardCharsets.UTF_8));
            changed = true;
        }
        return changed ? new BundleImporter.Bundle(bundle.kind(), bundle.manifest(), entries, bundle.spaceToon()) : bundle;
    }

    /**
     * The makers of {@code current} — see the class doc — or {@code null} when the history cannot say. Empty when
     * the rule has no invoke-api consequence.
     */
    static List<String> makers(ComponentStore store, String name, Map<String, Object> current) {
        String sig = signature(current);
        if (sig == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        if (current.get("updatedBy") == null) return null;
        out.add(String.valueOf(current.get("updatedBy")));
        List<ComponentStore.ComponentVersion> history = store.versions(TYPE, name);
        for (ComponentStore.ComponentVersion v : history) {
            if (!sig.equals(signature(v.content()))) return List.copyOf(out);   // the next-newer version made the change
            Object by = v.content().get("updatedBy");
            if (by == null) return null;   // an unstamped version on the chain: provenance unknown
            out.add(String.valueOf(by));
        }
        // No differing version retained: complete only if the history cannot have been pruned.
        return history.size() < ComponentStore.historyKeep() ? List.copyOf(out) : null;
    }

    /** The canonical JSON of the rule's invoke-api consequences (connection, method, payload); null for none. */
    @SuppressWarnings("unchecked")
    static String signature(Map<String, Object> rule) {
        if (rule == null || !(rule.get("consequences") instanceof List<?> cs)) return null;
        List<Object> sig = new ArrayList<>();
        for (Object o : cs)
            if (o instanceof Map<?, ?> c && "invoke-api".equals(String.valueOf(c.get("action"))))
                sig.add(c.get("params") instanceof Map<?, ?> p ? p : Map.of());
        return sig.isEmpty() ? null : ContentHash.canonicalJson(Map.of("invokeApi", sig));
    }

    /** The capability and parameter rules for an invoke-api consequence (round-1 findings 3 and 4). */
    @SuppressWarnings("unchecked")
    static void checkInvokeApi(HttpExchange e, Map<String, Object> rule, Map<String, String> carried, boolean live) {
        List<Object> cs = rule.get("consequences") instanceof List<?> l ? (List<Object>) l : List.of();
        boolean any = false;
        for (Object o : cs) if (o instanceof Map<?, ?> c && "invoke-api".equals(String.valueOf(c.get("action")))) any = true;
        if (!any) return;
        ApiContext.requireCapability(e, "canWorkIncidents");
        for (Object o : cs) {
            if (!(o instanceof Map<?, ?> raw) || !"invoke-api".equals(String.valueOf(raw.get("action")))) continue;
            Map<String, Object> p = raw.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            if (p.containsKey("url"))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an invoke-api consequence takes "
                        + "params.connection (the id of an https Connection), not params.url — the target is always an "
                        + "onboarded Connection, never a URL written into a rule");
            Object id = p.get("connection");
            if (id == null || String.valueOf(id).isBlank())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an invoke-api consequence needs "
                        + "params.connection (the id of an https Connection)");
            String connector = carried.get(String.valueOf(id));
            if (connector == null && live)
                connector = com.gamma.acquire.ConnectionRegistry.find(String.valueOf(id))
                        .map(com.gamma.acquire.ConnectionProfile::connector).orElse(null);
            if (connector == null)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "invoke-api: Connection '" + id
                        + "' is not registered in this Space");
            if (!com.gamma.pipeline.exec.WebhookSink.CONNECTOR.equals(connector))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "invoke-api: Connection '" + id
                        + "' is a '" + connector + "' connection — an Action Request target must be an https Connection");
        }
    }
}
