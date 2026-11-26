package com.gamma.control;

import com.gamma.alert.AlertRule;
import com.gamma.job.PostgresPublishJobType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.KpiDefinition;
import com.gamma.query.KpiEvaluator;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reads what a KPI Alert Rule watches (`BI-INSIGHTS-1` I-17, {@link AlertRule#isKpiRule}): the KPI's value for its period
 * to date, the RAG rank of that value, the projected landing (I-12, {@link KpiEvaluator#project}) or its RAG, or
 * P(target met). Wired into the sweep by {@code CollectorService}; the save gate ({@link #requireReadable}) applies the
 * same checks, so a rule that saves is a rule that evaluates.
 *
 * <ul>
 *   <li><b>Runs as the rule's owner</b>, as {@code kpi.snapshot} runs as its author ({@link PostgresPublishJobType#canView}
 *       / {@link PostgresPublishJobType#canRead}), with the roles stamped on the rule at save ({@link #OWNER_ROLES},
 *       as a Job's {@code updatedByRoles}) re-resolved against the role table as it is NOW. {@link #accessRefusal} is the
 *       sweep's re-check (`BI-INSIGHTS-1` loose ends A): a rule whose owner may no longer view the KPI or read its
 *       Dataset is skipped with one Incident. With no authenticator (Basic) the sharing envelope is not enforced, as
 *       everywhere.</li>
 *   <li><b>Never reveals</b>: a Measure returning raw values of a masked column is refused ({@link BiResultMasking#vetMeasure},
 *       no reveal), as the KPI History Job refuses it.</li>
 *   <li>Computed at query time (D-BI7): nothing is stored.</li>
 * </ul>
 */
public final class KpiAlertProbe {
    private static final Logger LOG = LoggerFactory.getLogger(KpiAlertProbe.class);

    private KpiAlertProbe() {}

    /**
     * The rule key holding its saver's roles (comma-separated), SERVER-STAMPED by the Alert Rule routes
     * ({@link #stampRoles}); any value a client sends is dropped ({@link #stripRoles}).
     */
    public static final String OWNER_ROLES = "ownerRoles";

    /** {@code body} without a client-supplied {@link #OWNER_ROLES}. */
    static Map<String, Object> stripRoles(Map<String, Object> body) {
        if (body == null || !body.containsKey(OWNER_ROLES)) return body;
        Map<String, Object> out = new java.util.LinkedHashMap<>(body);
        out.remove(OWNER_ROLES);
        return out;
    }

    /**
     * A raw {@code alert-rule} component write ({@code /components/alert-rule}, version restore): the client's
     * {@link #OWNER_ROLES} dropped, and for a KPI rule {@link #rolesFor} stamped.
     */
    static Map<String, Object> stampContent(com.sun.net.httpserver.HttpExchange ex, Map<String, Object> content,
                                            Map<String, Object> stored) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(stripRoles(content));
        java.util.Collection<String> roles = out.get("kpi") == null ? null : rolesFor(ex, out.get("owner"), stored);
        if (roles != null) out.put(OWNER_ROLES, String.join(",", new java.util.TreeSet<>(roles)));
        return out;
    }

    /** {@link #stripRoles} on a rule file's map, flat or wrapped in {@code alert:}; the same instance when nothing was there. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> stripDeep(Map<String, Object> doc) {
        if (doc.get("alert") instanceof Map<?, ?> inner && inner.containsKey(OWNER_ROLES)) {
            Map<String, Object> out = new java.util.LinkedHashMap<>(doc);
            out.put("alert", stripRoles((Map<String, Object>) inner));
            return out;
        }
        return stripRoles(doc);
    }

    /**
     * The roles a save may stamp on a rule owned by {@code owner}: the saver's held roles only when the saver IS the owner;
     * otherwise the stored rule's stamp, kept only while its owner is unchanged; else none (fail closed).
     */
    static java.util.Collection<String> rolesFor(com.sun.net.httpserver.HttpExchange ex, Object owner, Map<String, Object> stored) {
        var subject = com.gamma.spi.http.ApiContext.subject(ex);
        if (subject.isPresent() && owner != null && subject.get().id().equals(String.valueOf(owner)))
            return com.gamma.access.ComponentAccess.heldRoles(ex);
        if (stored != null && stored.get(OWNER_ROLES) != null && java.util.Objects.equals(stored.get("owner"), owner))
            return java.util.List.of(String.valueOf(stored.get(OWNER_ROLES)).split(","));
        return null;
    }

    /** {@code rule} with {@code roles} stamped as {@link #OWNER_ROLES} (a KPI rule only; nothing when {@code roles} is null). */
    static AlertRule stampRoles(AlertRule rule, java.util.Collection<String> roles) {
        if (!rule.isKpiRule() || roles == null) return rule;
        Map<String, Object> m = new java.util.LinkedHashMap<>(rule.toMap());
        m.put(OWNER_ROLES, String.join(",", new java.util.TreeSet<>(roles)));
        return AlertRule.fromMap(m);
    }

    /**
     * The sweep's owner re-check: why {@code rule}'s owner (with its stamped roles, as the role table grants them now)
     * may no longer view the KPI or read its Dataset, else {@code null}. Only access is judged here — a KPI that cannot
     * be computed still reads as UNKNOWN through {@link #of}.
     */
    public static Function<AlertRule, String> accessRefusal(Supplier<Path> writeRoot) {
        return rule -> {
            Path wr = writeRoot.get();
            if (wr == null) return null;
            ComponentStore store = new ComponentStore(wr.resolve("registry"));
            PostgresPublishJobType.Author author = author(rule, wr);
            Map<String, Object> content = store.get(KpiRoutes.TYPE, rule.kpi()).map(ComponentRegistry.Component::content).orElse(null);
            if (content == null || !PostgresPublishJobType.canView(author, content))
                return "its owner '" + rule.owner() + "' may no longer view kpi '" + rule.kpi() + "' (or it was deleted)";
            Object ds = content.get("dataset");
            Map<String, Object> dataset = ds == null ? null
                    : store.get("dataset", String.valueOf(ds)).map(ComponentRegistry.Component::content).orElse(null);
            if (dataset == null || !PostgresPublishJobType.canRead(author, store, dataset))
                return "its owner '" + rule.owner() + "' may no longer read dataset '" + ds + "' of kpi '" + rule.kpi() + "'";
            return null;
        };
    }

    /** The sweep's probe over one Space's roots (each resolved per call, as the Dataset measure probe's are). */
    public static Function<AlertRule, OptionalDouble> of(Supplier<Path> writeRoot, Supplier<Path> dataRoot) {
        return rule -> {
            try {
                Path wr = writeRoot.get(), data = dataRoot.get();
                if (wr == null || data == null) return OptionalDouble.empty();
                return read(wr, data, rule, LocalDate.now(zone(wr, rule)));
            } catch (Exception e) {   // unknown: never fires, never heals
                LOG.warn("alert rule {} could not read KPI '{}': {}", rule.name(), rule.kpi(), e.getMessage());
                return OptionalDouble.empty();
            }
        };
    }

    private static java.time.ZoneId zone(Path writeRoot, AlertRule rule) {
        Map<String, Object> content = new ComponentStore(writeRoot.resolve("registry")).get(KpiRoutes.TYPE, rule.kpi())
                .map(ComponentRegistry.Component::content).orElse(null);
        return content == null ? KpiDefinition.UTC
                : KpiDefinition.fromMap(rule.kpi(), content, TimezoneSettings.forRoot(writeRoot).effective()).zone();
    }

    /** What {@code rule} reads as of {@code today} (the KPI's zone); empty when there is nothing to read. Package-visible for tests. */
    static OptionalDouble read(Path writeRoot, Path data, AlertRule rule, LocalDate today) throws Exception {
        Resolved r = resolve(writeRoot, rule);
        String relationSql = DatasetRelation.relationSql(r.dataset(), data, new ViewStore(writeRoot.resolve("views")));
        LocalDate asOf = today;
        if (r.kpi().asOfLatest()) {
            LocalDate latest = KpiEvaluator.latestDay(r.kpi(), relationSql);
            if (latest != null && latest.isBefore(asOf)) asOf = latest;
        }
        if (rule.projection() == null) {
            KpiEvaluator.Result v = KpiEvaluator.evaluate(r.kpi(), relationSql, asOf);
            if (rule.rag() != null) return rank(v.rag());
            return v.value() == null ? OptionalDouble.empty() : OptionalDouble.of(v.value());
        }
        KpiEvaluator.Projection p = KpiEvaluator.project(r.kpi(), relationSql, asOf);
        if (!p.projected()) return OptionalDouble.empty();
        if ("probability".equals(rule.projection())) {
            Double prob = p.probability();
            return prob == null ? OptionalDouble.empty() : OptionalDouble.of(prob);
        }
        return rule.rag() != null ? rank(p.rag()) : OptionalDouble.of(p.result().value());
    }

    private static OptionalDouble rank(KpiDefinition.Rag rag) {
        if (rag == null) return OptionalDouble.empty();
        return OptionalDouble.of(switch (rag) {
            case GREEN -> 0;
            case AMBER -> 1;
            case RED -> 2;
        });
    }

    private record Resolved(KpiDefinition kpi, Map<String, Object> dataset) {}

    /** The KPI and its Dataset as the rule's owner may read them; {@link SecurityException} otherwise. */
    private static Resolved resolve(Path writeRoot, AlertRule rule) {
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        PostgresPublishJobType.Author author = author(rule, writeRoot);
        Map<String, Object> content = store.get(KpiRoutes.TYPE, rule.kpi()).map(ComponentRegistry.Component::content).orElse(null);
        if (content == null || !PostgresPublishJobType.canView(author, content))
            throw new SecurityException("kpi '" + rule.kpi() + "' does not exist or the rule's owner '" + rule.owner()
                    + "' may not view it");
        KpiDefinition kpi = KpiDefinition.fromMap(rule.kpi(), content, TimezoneSettings.forRoot(writeRoot).effective());
        Map<String, Object> dataset = store.get("dataset", kpi.dataset()).map(ComponentRegistry.Component::content).orElse(null);
        if (dataset == null || !PostgresPublishJobType.canRead(author, store, dataset))
            throw new SecurityException("dataset '" + kpi.dataset() + "' of kpi '" + rule.kpi()
                    + "' does not exist or the rule's owner '" + rule.owner() + "' may not read it");
        BiResultMasking.of(writeRoot, store, new ViewStore(writeRoot.resolve("views")), kpi.dataset(), dataset, false)
                .vetMeasure(kpi.measure());   // an alert never reveals
        return new Resolved(kpi, dataset);
    }

    /**
     * The rule's owner as a run author: their id and the roles stamped at save ({@link #OWNER_ROLES}), with the
     * capabilities those roles grant under the role table as it is NOW; OPEN when no authenticator is active. A rule
     * saved before the stamp has no roles (fail closed: only what is shared to the owner by id).
     */
    static PostgresPublishJobType.Author author(AlertRule rule, Path writeRoot) {
        if (Authenticators.active().isEmpty()) return PostgresPublishJobType.Author.OPEN;
        java.util.List<String> roles = new java.util.ArrayList<>();
        Object stamped = rule.extra().get(OWNER_ROLES);
        if (stamped != null)
            for (String r : String.valueOf(stamped).split(","))
                if (!r.isBlank()) roles.add(r.trim().toLowerCase(java.util.Locale.ROOT));
        return new PostgresPublishJobType.Author(rule.owner(), Set.copyOf(roles),
                Set.copyOf(JobAuthority.capabilitiesNow(roles, writeRoot)), false);
    }

    /**
     * The save gate of a KPI rule (run inside {@code AlertRoutes.parseContent}, so every door that writes an Alert Rule
     * meets it): the KPI exists and the rule's owner may view it and read its Dataset, its Measure reveals nothing
     * masked, and what the rule watches can be computed — a status ({@code rag}) or a probability needs a target or
     * bands, a projection a day-summable Measure on a grain wider than a day. 422 otherwise.
     */
    static void requireReadable(Path writeRoot, AlertRule rule) {
        if (!rule.isKpiRule()) return;
        if (writeRoot == null)
            throw new ApiException(503, ErrorCodes.CONTROL_PLANE_READ_ONLY, "alert rule write disabled: set -Dassist.write.root to enable");
        KpiDefinition kpi;
        try {
            kpi = resolve(writeRoot, rule).kpi();
        } catch (SecurityException | IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "alert.kpi: " + e.getMessage());
        }
        boolean graded = kpi.target() != null || kpi.bands() != null;
        if ((rule.rag() != null || "probability".equals(rule.projection())) && !graded)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "alert.kpi '" + rule.kpi() + "' has no target "
                    + "and no bands, so it has no RAG status and no target to meet; give the KPI a target or bands");
        if (rule.projection() != null && (kpi.grain() == KpiDefinition.Grain.DAY || !KpiEvaluator.ADDITIVE.contains(kpi.measure().agg())))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "alert.projection: kpi '" + rule.kpi()
                    + "' cannot be projected — a projection needs a count or sum Measure on a week, month, quarter or year grain");
    }
}
