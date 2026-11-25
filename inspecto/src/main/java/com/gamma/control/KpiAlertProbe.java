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
 *       / {@link PostgresPublishJobType#canRead}): a KPI or Dataset the owner may not view reads as UNKNOWN — nothing
 *       fires. A rule carries its owner's id, not their roles, so a KPI shared to the owner only through a role is not
 *       readable here (fail closed); share it, and its Dataset, to the owner. With no authenticator (Basic) the sharing
 *       envelope is not enforced, as everywhere.</li>
 *   <li><b>Never reveals</b>: a Measure returning raw values of a masked column is refused ({@link BiResultMasking#vetMeasure},
 *       no reveal), as the KPI History Job refuses it.</li>
 *   <li>Computed at query time (D-BI7): nothing is stored.</li>
 * </ul>
 */
public final class KpiAlertProbe {
    private static final Logger LOG = LoggerFactory.getLogger(KpiAlertProbe.class);

    private KpiAlertProbe() {}

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
        PostgresPublishJobType.Author author = author(rule.owner());
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

    /** The rule's owner as a run author: their id and no roles; OPEN when no authenticator is active. */
    static PostgresPublishJobType.Author author(String owner) {
        if (Authenticators.active().isEmpty()) return PostgresPublishJobType.Author.OPEN;
        return new PostgresPublishJobType.Author(owner, Set.of(), Set.of(), false);
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
