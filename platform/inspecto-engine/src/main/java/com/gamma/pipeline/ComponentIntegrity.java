package com.gamma.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-component referential-integrity rules (System Maintenance MNT-7/MNT-16), shared by the
 * {@code metadata_validate} maintenance task and the bundle-import pre-check. Which keys are references lives in
 * {@link LinkCensus} (`SPACE-LINK-CENSUS-1` LC-1): the findings here are the {@link LinkGraph} edges of the
 * links this audit reads ({@link LinkCensus.Check#VALIDATE}) that dangle — a widget's
 * {@code datasetId}/{@code queryId}, a dashboard tile's {@code widgetId}, a Link Analysis / Geo Map view's
 * {@code query.projection.datasetId}, a reconciliation's sides; plus two components of one type that are
 * content-identical apart from {@code name}. Pure functions over an in-memory universe — no I/O, so callers
 * decide what the universe is (the live registry, or registry ∪ an incoming bundle).
 */
public final class ComponentIntegrity {

    private ComponentIntegrity() {}

    /** Broken widget→Dataset/Query, dashboard-tile→Widget, view→Dataset and reconciliation→Dataset references
     *  over {@code byType} (missing type keys are treated as empty). Human-readable, stable finding strings. */
    public static List<String> brokenRefs(Map<String, List<ComponentRegistry.Component>> byType) {
        List<String> findings = new ArrayList<>();
        for (LinkGraph.Edge e : LinkGraph.ofComponents(byType, null).dangling()) {
            if (!e.link().checks().contains(LinkCensus.Check.VALIDATE) || e.toKind().equals("pipeline")) continue;
            findings.add("broken reference: " + e.fromKind() + " '" + e.fromName() + "'"
                    + (e.fromKind().equals("dashboard") ? " tile" : "")
                    + " → missing " + e.toKind() + " '" + e.value() + "'");
        }
        return findings;
    }

    /**
     * Broken component→pipeline references over {@code byType}, checked against {@code pipelineIds}
     * (the registered-id universe, which only the caller can know — pipelines are files, not
     * components). Covers expectation / decision-rule {@code target} when {@code targetType} is
     * {@code pipeline} (the default when absent) — the same matching rule the delete-impact scan and
     * the rename path use. Matching is case-insensitive because registered ids are lower-cased at
     * parse time while authored targets may not be.
     *
     * <p>Deliberately does NOT check a dataset's {@code physicalRef}/{@code sourceName} against the
     * pipeline set: those name <em>stores</em>, and stores legitimately exist that no pipeline owns
     * (job-created ones like {@code maintenance_backups}). A dangling dataset is caught by the
     * data-root existence check instead.
     */
    public static List<String> brokenPipelineRefs(Map<String, List<ComponentRegistry.Component>> byType,
                                                  Set<String> pipelineIds) {
        List<String> findings = new ArrayList<>();
        for (LinkGraph.Edge e : LinkGraph.ofComponents(byType, pipelineIds).dangling()) {
            if (!e.link().checks().contains(LinkCensus.Check.VALIDATE) || !e.toKind().equals("pipeline")) continue;
            findings.add("broken reference: " + e.fromKind() + " '" + e.fromName() + "' → missing pipeline '"
                    + e.value().trim() + "'");
        }
        return findings;
    }

    /** Components of one type whose content is identical apart from {@code name}. */
    public static List<String> duplicates(Map<String, List<ComponentRegistry.Component>> byType) {
        List<String> findings = new ArrayList<>();
        for (Map.Entry<String, List<ComponentRegistry.Component>> e : byType.entrySet()) {
            Map<Map<String, Object>, String> seen = new HashMap<>();
            for (ComponentRegistry.Component c : e.getValue()) {
                Map<String, Object> body = new HashMap<>(c.content());
                body.remove("name");
                String first = seen.putIfAbsent(body, c.name());
                if (first != null && !first.equals(c.name()))
                    findings.add("duplicate definition: " + e.getKey() + "s '" + first + "' and '"
                            + c.name() + "' are identical apart from the name");
            }
        }
        return findings;
    }
}
