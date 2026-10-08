package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
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
 *
 * <p><b>A version restore keeps the restored content's makers</b> ({@code ASSURE-ACTION-REQUESTS-RESIDUALS-1}
 * item 1): a restore writes an old version back with the RESTORER as {@code updatedBy}, so on its own the history
 * walk would stop at the version the restore replaced and forget who wrote the restored consequence. The restore
 * therefore stamps {@code restoredMakers} — the restored version's own makers, computed from the history AT that
 * version ({@link #restoredMakers}) — and {@link #makers} adds every chained version's {@code restoredMakers}. The
 * field is server-only: {@link #prepare} discards any body value — except a NEW Space's bundle, which has no history
 * to recompute it from and keeps its recorded makers (see {@code bundledMakers}). A restore of a version whose makers the history
 * cannot say is refused (409) — fail closed.
 *
 * <p><b>The makers are stamped on the rule at save</b> ({@code ASSURE-ACTION-REQUESTS-RESIDUALS-1} item 3, option C):
 * every {@link #prepare} computes the full maker set of the version it writes — the writer, plus the prior
 * version's makers when the invoke-api consequences are unchanged, plus a restore's {@code restoredMakers} — and
 * persists it as {@code makers}. {@link #makers} reads that stamp first, so pruning the history cannot erase who made
 * the consequence. Server-only like the other stamps (a body value is discarded) — except a NEW Space's bundle, whose
 * stamped {@code makers} only ADD. When the prior makers are unknown, or would exceed {@link #MAX_MAKERS}, nothing is
 * stamped and the history walk decides (fail closed).
 */
public final class DecisionRuleGuard {

    private DecisionRuleGuard() {}

    static final String TYPE = "decision-rule";
    /** Server-stamped on a version restore: the restored version's makers (see the class doc). */
    static final String RESTORED_MAKERS = "restoredMakers";
    /** Server-stamped on every save: the version's complete maker set (see the class doc). */
    static final String MAKERS = "makers";
    /** Bounds on {@code restoredMakers}: it is copied forward into later versions, so it must never grow unbounded. */
    static final int MAX_MAKERS = 64, MAX_MAKER_ID = 256;
    private static final String DIR_PREFIX = "registry/" + ComponentRegistry.dirForType(TYPE).orElse("decision-rules") + "/";

    /** Validate and stamp one write of a rule; returns the content to persist. {@code prev}: the stored rule or null. */
    static Map<String, Object> prepare(HttpExchange ex, Map<String, Object> content, Map<String, Object> prev,
                                       ComponentStore store, String name) {
        return prepare(ex, content, prev, Map.of(), true, null, store, name);
    }

    /** {@link #prepare} for a version restore: {@code restoredMakers} from {@link #restoredMakers}, or null. */
    static Map<String, Object> prepare(HttpExchange ex, Map<String, Object> content, Map<String, Object> prev,
                                       List<String> restoredMakers, ComponentStore store, String name) {
        return prepare(ex, content, prev, Map.of(), true, restoredMakers, store, name);
    }

    /**
     * {@link #prepare} for a bundle: {@code carried} are the Connections (id → connector) the same bundle brings,
     * which count as registered; {@code live} says whether this Space's registry applies (not for a new Space).
     */
    static Map<String, Object> prepare(HttpExchange ex, Map<String, Object> content, Map<String, Object> prev,
                                       Map<String, String> carried, boolean live, ComponentStore store, String name) {
        return prepare(ex, content, prev, carried, live, null, store, name);
    }

    private static Map<String, Object> prepare(HttpExchange ex, Map<String, Object> content, Map<String, Object> prev,
                                               Map<String, String> carried, boolean live, List<String> restoredMakers,
                                               ComponentStore store, String name) {
        checkInvokeApi(ex, content, carried, live);
        Map<String, Object> out = new LinkedHashMap<>(content);
        String actor = ApiContext.actor(ex);
        Object created = prev == null ? null : prev.get("createdBy");
        out.put("createdBy", created == null ? actor : created);
        out.put("updatedBy", actor);
        out.remove(RESTORED_MAKERS);   // server-only, like the stamps above
        if (!live) restoredMakers = bundledMakers(content);   // a NEW Space has no history to recompute them from
        if (restoredMakers != null && !restoredMakers.isEmpty()) out.put(RESTORED_MAKERS, List.copyOf(restoredMakers));
        out.remove(MAKERS);
        List<String> makers = stampedMakers(out, prev, restoredMakers, store, name);
        if (makers != null) out.put(MAKERS, makers);
        return out;
    }

    /**
     * The complete makers of the version being written ({@code out}, already stamped): its writer, any
     * {@code restoredMakers}, and — when the invoke-api consequences are unchanged from {@code prev} — prev's makers
     * (its stamp, else the history walk). Null (stamp nothing) when there is no invoke-api consequence, when prev's
     * makers are unknown, or when the set would exceed {@link #MAX_MAKERS}.
     */
    private static List<String> stampedMakers(Map<String, Object> out, Map<String, Object> prev,
                                              List<String> restoredMakers, ComponentStore store, String name) {
        String sig = signature(out);
        if (sig == null) return null;
        Set<String> all = new LinkedHashSet<>();
        all.add(String.valueOf(out.get("updatedBy")));
        if (restoredMakers != null) all.addAll(restoredMakers);
        if (prev != null && sig.equals(signature(prev))) {
            List<String> prior = stamp(prev);
            if (prior == null && store != null && name != null) {
                try {
                    prior = makers(store, name, prev);
                } catch (RuntimeException unreadableHistory) {
                    prior = null;
                }
            }
            if (prior == null) return null;
            all.addAll(prior);
        }
        return all.size() > MAX_MAKERS ? null : List.copyOf(all);
    }

    /** A version's stamped {@code makers}, or null when absent or malformed. */
    private static List<String> stamp(Map<String, Object> version) {
        if (!(version.get(MAKERS) instanceof List<?> list) || list.isEmpty() || list.size() > MAX_MAKERS) return null;
        List<String> out = new ArrayList<>();
        for (Object m : list) {
            if (!(m instanceof String s) || s.isBlank() || s.length() > MAX_MAKER_ID) return null;
            out.add(s);
        }
        return out;
    }

    /**
     * A new Space's bundle carries the rule but no version history, so the importer alone would be its maker and the
     * rule's recorded author could approve what it raises there. The bundle's {@code updatedBy} and a well-formed
     * {@code restoredMakers} are therefore kept as makers alongside the importer — an imported value can only ADD
     * people four-eyes refuses, never remove one. A malformed {@code restoredMakers} refuses the import (422).
     */
    private static List<String> bundledMakers(Map<String, Object> content) {
        Set<String> out = new LinkedHashSet<>();
        if (content.get("updatedBy") instanceof String by && !by.isBlank()) out.add(by);
        for (String key : List.of(RESTORED_MAKERS, MAKERS)) {   // MAKERS: a rule exported after option C
            Object restored = content.get(key);
            if (restored == null) continue;
            if (!(restored instanceof List<?> list) || list.stream().anyMatch(m -> !(m instanceof String s) || s.isBlank()
                    || s.length() > MAX_MAKER_ID))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "Decision Rule '" + content.get("name")
                        + "': " + key + " must be a list of editor ids (at most " + MAX_MAKER_ID + " characters each)");
            for (Object m : list) out.add((String) m);
        }
        if (out.size() > MAX_MAKERS)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "Decision Rule '" + content.get("name")
                    + "' names more than " + MAX_MAKERS + " makers");
        return List.copyOf(out);
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
    /** The Connections a config tree carries ({@code *_connection.toon}): id → connector. */
    static Map<String, String> carriedConnections(Map<String, byte[]> configEntries) {
        Map<String, String> carried = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : configEntries.entrySet()) {
            if (!e.getKey().endsWith("_connection.toon")) continue;
            try {
                if (ConfigCodec.toMap(new String(e.getValue(), StandardCharsets.UTF_8)).get("connection") instanceof Map<?, ?> c
                        && c.get("id") != null)
                    carried.put(String.valueOf(c.get("id")), String.valueOf(c.get("connector")));
            } catch (RuntimeException unreadable) {
                // not a Connection this bundle can vouch for
            }
        }
        return carried;
    }

    static BundleImporter.Bundle guardImport(HttpExchange ex, Path config, BundleImporter.Bundle bundle) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(bundle.configEntries());
        Map<String, String> carried = carriedConnections(bundle.configEntries());
        boolean changed = false;
        ComponentStore store = config == null ? null : new ComponentStore(config.resolve("registry"));
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            // the SAME normalisation the import's kind gate classifies by (case, "."/empty segments, trailing
            // dots/spaces), so no spelling reaches registry/decision-rules/ past this guard
            String rel = ImportCapabilityGuard.normalizedPath(e.getKey());
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
            Map<String, Object> prepared = prepare(ex, content, prev, carried, config != null, store, name);
            entries.put(e.getKey(), ConfigCodec.toToon(prepared).getBytes(StandardCharsets.UTF_8));
            changed = true;
        }
        return changed ? new BundleImporter.Bundle(bundle.kind(), bundle.manifest(), entries, bundle.spaceToon()) : bundle;
    }

    /**
     * The makers of {@code current} — see the class doc — or {@code null} when the history cannot say. Empty when
     * the rule has no invoke-api consequence.
     */
    public static List<String> makers(ComponentStore store, String name, Map<String, Object> current) {
        List<ComponentStore.ComponentVersion> history = store.versions(TYPE, name);
        return makersFrom(current, history, history.size() < ComponentStore.historyKeep());
    }

    /**
     * The makers of archived {@code version} — what {@link #makers} answered while it was current — for a restore to
     * stamp as {@code restoredMakers}. Empty when that version has no invoke-api consequence; 409 when the history
     * cannot say (fail closed: four-eyes could not exclude the restored content's author).
     */
    static List<String> restoredMakers(ComponentStore store, String name, int version) {
        List<ComponentStore.ComponentVersion> history = store.versions(TYPE, name);
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).version() != version) continue;
            List<String> m = makersFrom(history.get(i).content(), history.subList(i + 1, history.size()),
                    history.size() < ComponentStore.historyKeep());
            if (m == null)
                throw new ApiException(409, ErrorCodes.CONFLICT, "version " + version + " of Decision Rule '" + name
                        + "' has no recorded editor for its invoke-api consequence (saved before editors were recorded, "
                        + "or history pruned past it), so four-eyes could not exclude its author; save the rule instead");
            if (m.size() > MAX_MAKERS)   // never truncated — dropping a maker would let them approve
                throw new ApiException(409, ErrorCodes.CONFLICT, "version " + version + " of Decision Rule '" + name
                        + "' has more than " + MAX_MAKERS + " makers to carry forward; save the rule instead");
            return m;
        }
        throw new ApiException(404, ErrorCodes.NOT_FOUND, "no version " + version + " of decision-rule component '" + name + "'");
    }

    /** {@code head}'s makers, walking {@code older} (newest first); {@code complete}: nothing was pruned. */
    private static List<String> makersFrom(Map<String, Object> head, List<ComponentStore.ComponentVersion> older,
                                           boolean complete) {
        String sig = signature(head);
        if (sig == null) return List.of();
        List<String> stamped = stamp(head);
        if (stamped != null) return List.copyOf(stamped);   // option C: complete as of its save; pruning cannot erase it
        Set<String> out = new LinkedHashSet<>();
        if (!addMakers(out, head)) return null;
        for (ComponentStore.ComponentVersion v : older) {
            if (!sig.equals(signature(v.content()))) return List.copyOf(out);   // the next-newer version made the change
            if (!addMakers(out, v.content())) return null;   // an unstamped version on the chain: provenance unknown
        }
        // No differing version retained: complete only if the history cannot have been pruned.
        return complete ? List.copyOf(out) : null;
    }

    /** One version's makers — its {@code updatedBy} plus any {@code restoredMakers}; false when unknown or malformed. */
    private static boolean addMakers(Set<String> out, Map<String, Object> version) {
        Object by = version.get("updatedBy");
        if (by == null) return false;
        out.add(String.valueOf(by));
        Object restored = version.get(RESTORED_MAKERS);
        if (restored == null) return true;
        if (!(restored instanceof List<?> list) || list.size() > MAX_MAKERS) return false;
        for (Object m : list) {
            if (m == null || String.valueOf(m).isBlank() || String.valueOf(m).length() > MAX_MAKER_ID) return false;
            out.add(String.valueOf(m));
        }
        return true;
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

    /**
     * The guard for a BACKGROUND writer (no request, so no Subject — e.g. {@link PendingAlertRules}, operator
     * 2026-10-06): nobody holds {@code canWorkIncidents}, so any {@code invoke-api} consequence is refused (422).
     * {@link #checkInvokeApi} with a null exchange would pass the capability check; this fails closed instead.
     */
    static void refuseUnattended(Map<String, Object> content) {
        if (signature(content) != null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an invoke-api consequence cannot be "
                    + "written by a background writer — no one holding canWorkIncidents authored it; create the rule "
                    + "through its own page");
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
