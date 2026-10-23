package com.gamma.etl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Standard+ features whose code ships in every build but whose USE the edition board
 * ({@code docs/EDITIONS.md}) reserves for Standard and Advanced — {@code PROCESSOR-RELEASE-READINESS-1}
 * G9, operator decision 2026-09-24 ("gate the code"):
 * <ul>
 *   <li>{@link #ALERT_DISPATCH} — Alert Rules ({@code SP-CTL-07}); declared by {@code inspecto-ops}, whose
 *       object store holds the ALERT objects a fired rule opens.</li>
 *   <li>{@link #SINK_ARCHIVE} — the compliance archive, {@code collector.post_action.on_success: MOVE}
 *       ({@code SP-SNK-06}); declared by {@code inspecto-backup}, which carries the archive's other half,
 *       the {@code backup} maintenance task.</li>
 *   <li>{@link #SINK_DUCKLAKE} — DuckLake catalog registration, {@code ducklake.enabled: true}
 *       ({@code SP-SNK-03}); declared by {@code inspecto-backup} too — it has no module of its own, and
 *       rides with the storage module every Standard bundle ships.</li>
 * </ul>
 *
 * <p>A feature is present when an {@link EditionFeatureProvider} on the classpath names it. Basic
 * bundles neither module, so all three are absent there and every door refuses them with
 * {@code ERR_EDITION_FEATURE}: the save gate, the Alert Rule routes, and — for a config that arrives on
 * disk instead — the boot-time Alert Rule load, the pipeline registry, the Collector's acquisition and the
 * DuckLake registrar.
 *
 * <p>⚠ A provider that is declared but cannot load reads as ABSENT: the feature is refused, which is the
 * safe direction for a gate.
 */
public final class EditionFeatures {

    public static final String ALERT_DISPATCH = "alert.dispatch";
    public static final String SINK_ARCHIVE = "sink.archive";
    public static final String SINK_DUCKLAKE = "sink.ducklake";
    /**
     * The PII masking Step ({@code steps:} kind {@code mask}, node {@code transform.mask}) — ENTERPRISE only
     * (board SEC-08, operator 2026-09-02; built 2026-10-06). Declared by {@code inspecto-policy}, the one module
     * only the Advanced (and Preview) flavours bundle.
     */
    public static final String PII_MASK = "quality.pii.mask";
    /**
     * The Link Analysis <b>Investigation surface</b> — Investigations (sealed op log, replay, compare), Drafts, the Dossier
     * and its bundle, templates and external references, members, embed links, Case linking, data trust (coverage,
     * Dataset Files, data-changes), Case history beside a node (D-LAE1), Graph Run, link index builds, and Alert Rules / standing detection on Investigations
     * ({@code LA-EDITIONS-1} ED-4, operator 2026-10-17: "Basic has no sign-in and no Investigations"). Declared by
     * {@code inspecto-ops}, which only the Standard, Advanced and Preview flavours bundle. Unlike the config features
     * above it is also a ROUTE FEATURE: a route module whose {@code featureIds()} names it is answered
     * {@code 503 CAPABILITY_UNAVAILABLE} with {@link #refusal} at dispatch when it is absent ({@link #gatesRoutes}),
     * while the Link Analysis EXPLORER routes beside it (projection, paths, patterns, Value Measures, Entity
     * identities, Entity profile pages) answer in Basic as in every edition. Since {@code LA-ONBOARD-1} OB-5 it is also the
     * one feature an ADD-ON withholds at runtime: present only while the install holds {@link #LA_INVESTIGATIONS_ADDON}.
     */
    public static final String INVESTIGATIONS = "investigations";
    /**
     * The Geo map, routes and co-location ({@code POST /geo/projection}, {@code POST /geo/routes}) - Standard and up (D-LAE5,
     * operator 2026-10-20). A ROUTE FEATURE of its own on {@code GeoRoutes} only, declared by {@code inspecto-ops} beside
     * {@link #INVESTIGATIONS}. ⛔ Never withhold the Geo map through {@code geoLink}: that Space-level module id is shared by every
     * Link Analysis route module, so gating it would take all of them down.
     */
    public static final String GEO_MAP = "geoMap";
    /**
     * Link Analysis widgets on dashboards (the Explore picker's Link view and Geo map widget types) - Standard and up (D-LAE2,
     * operator 2026-10-20). ⚠ A <b>UI flag only</b>, deliberately not a route feature (it is not in {@link #ROUTE_FEATURES}): the
     * projection routes those widgets call stay open on Basic, so hiding the offer is cosmetic, not access control. Reported to
     * the SPA as {@code /bootstrap} {@code features.laWidgets}; declared by {@code inspecto-ops} beside the route features.
     */
    public static final String LA_WIDGETS = "laWidgets";

    /** The finding / error code every refusal carries. Mirrors {@code FindingCodes.ERR_EDITION_FEATURE}. */
    public static final String CODE = "ERR_EDITION_FEATURE";

    /** One refused use: the feature, the config field that uses it, and the message naming both. */
    public record Refusal(String feature, String field, String message) {}

    private static volatile Set<String> discovered;
    private static volatile Set<String> override;

    private EditionFeatures() {}

    /**
     * The Investigations add-on ({@code LA-ONBOARD-1} OB-5, Product Map {@code addon.laInvestigations}): billable, enforced at
     * RUNTIME, included by default wherever a module declares {@link #INVESTIGATIONS} (Standard and above). An install without
     * it keeps the edition's governance (sign-in, masking, roles - DATA-TIERS-1 DT-11: the edition stays the one switch) and
     * runs Link Analysis at the Explorer level. Basic never has it: no module there declares the feature.
     */
    public static final String LA_INVESTIGATIONS_ADDON = "laInvestigations";
    /** The add-on's Order Code ({@code product-map/addon/laInvestigations.toon}). */
    public static final String LA_INVESTIGATIONS_ORDER_CODE = "INS-AO-LA-INVESTIGATIONS";
    /** The install setting that removes the add-on: {@code -Daddons.laInvestigations=false}. Absent = held. */
    public static final String LA_INVESTIGATIONS_SETTING = "addons." + LA_INVESTIGATIONS_ADDON;

    /**
     * Whether this build brings {@code feature} and the install holds what it needs: a module declares it, and - for
     * {@link #INVESTIGATIONS} only - the install holds the Investigations add-on ({@link #holdsInvestigationsAddon}).
     */
    public static boolean present(String feature) {
        return declared(feature) && (!INVESTIGATIONS.equals(feature) || holdsInvestigationsAddon());
    }

    /** Whether a module of this build declares {@code feature} (or a test pinned it), add-ons aside. */
    private static boolean declared(String feature) {
        Set<String> o = override;
        if (o != null) return o.contains(feature);
        Set<String> d = discovered;
        if (d == null) discovered = d = discover();
        return d.contains(feature);
    }

    /**
     * Whether this install holds the Investigations add-on. ⚠ THE HOOK (OB-5): today the proof is a plain install setting,
     * {@link #LA_INVESTIGATIONS_SETTING} (anything but {@code true} removes it; read live, never cached), so whoever runs the
     * server can claim the add-on. A signed entitlement replaces this method's body; every gate already reads it.
     */
    public static boolean holdsInvestigationsAddon() {
        return Boolean.parseBoolean(System.getProperty(LA_INVESTIGATIONS_SETTING, "true").trim());
    }

    /**
     * One short line saying why {@code feature} is locked and which edition or add-on has it - the {@code /bootstrap}
     * {@code featureReasons} wording a locked control shows ({@code LA-ONBOARD-1} OB-10). {@link #refusal} stays the full
     * error text a refused request carries; a feature with no short line of its own answers that.
     */
    public static String lockedReason(String feature) {
        if (INVESTIGATIONS.equals(feature))
            return declared(INVESTIGATIONS)
                    ? "Needs the Investigations add-on, which this installation does not hold."
                    : "Investigations are in Standard and above, with the Investigations add-on.";
        if (GEO_MAP.equals(feature)) return "The Geo map is in Standard and above.";
        return refusal(feature);
    }

    /**
     * {@code featureId -> lockedReason} for every route feature this install withholds (absent features only), sorted by id.
     * The {@code /bootstrap} {@code featureReasons} map; a feature that is present has no entry.
     */
    public static Map<String, String> lockedReasons() {
        Map<String, String> out = new TreeMap<>();
        for (String f : ROUTE_FEATURES) if (!present(f)) out.put(f, lockedReason(f));
        return out;
    }

    /** The refusal message for {@code feature}, naming it and the edition (or add-on) that has it. */
    public static String refusal(String feature) {
        String what = switch (feature) {
            case ALERT_DISPATCH -> "Alert Rules (control.alert.dispatch)";
            case SINK_ARCHIVE -> "the compliance archive (sink.archive — collector.post_action.on_success: MOVE)";
            case SINK_DUCKLAKE -> "DuckLake catalog registration (sink.ducklake — ducklake.enabled: true)";
            case PII_MASK -> "PII masking (quality.pii.mask — a steps: mask Step)";
            case INVESTIGATIONS -> "Link Analysis Investigations (Investigations, Drafts, Dossier, templates, members, embed links, "
                    + "data trust, Case history, Graph Run, index builds and Alert Rules on Investigations)";
            case GEO_MAP -> "The Geo map, routes and co-location (POST /geo/projection, POST /geo/routes)";
            default -> "'" + feature + "'";
        };
        if (PII_MASK.equals(feature))
            return what + " is an Advanced feature — this build does not include it";
        if (GEO_MAP.equals(feature))
            return what + " are available in Standard and above - this build (Basic) does not include them";
        if (INVESTIGATIONS.equals(feature))
            return declared(INVESTIGATIONS)
                    ? what + " need the Investigations add-on (" + LA_INVESTIGATIONS_ORDER_CODE + "), which this installation does "
                            + "not hold (" + LA_INVESTIGATIONS_SETTING + "=false) - Link Analysis runs at the Explorer level"
                    : what + " are available in Standard and above, with the Investigations add-on — this build (Basic) is the "
                            + "Link Analysis explorer and does not include them";
        return what + " is a Standard+ feature — this build (Basic) does not include it";
    }

    /**
     * The edition features the HOST enforces on routes ({@link #gatesRoutes}). A member needs its own {@link #refusal} text
     * (pinned by {@code EditionFeaturesTest}). {@code LA-EDITIONS-PLACEMENT-1}: a set, so a second route feature is one
     * more member instead of one more hard-coded comparison.
     */
    static final Set<String> ROUTE_FEATURES = Set.of(INVESTIGATIONS, GEO_MAP);

    /**
     * Whether {@code featureId} — a {@code RouteModule.featureIds()} id — is an edition feature the HOST enforces on routes:
     * a route owned by it answers {@code 503 CAPABILITY_UNAVAILABLE} with {@link #refusal} when the feature is absent.
     * The members of {@link #ROUTE_FEATURES}; every other route feature is decided by whether its module is installed.
     */
    public static boolean gatesRoutes(String featureId) {
        return featureId != null && ROUTE_FEATURES.contains(featureId);
    }

    /** Throws {@link IllegalStateException} with {@link #refusal} when {@code feature} is absent. */
    public static void require(String feature) {
        if (!present(feature)) throw new IllegalStateException(CODE + ": " + refusal(feature));
    }

    /** Every absent feature an unparsed pipeline DRAFT uses — the save gate's form. */
    public static List<Refusal> pipelineRefusals(Map<?, ?> draft) {
        List<Refusal> out = new ArrayList<>();
        if (!present(SINK_ARCHIVE) && draft.get("collector") instanceof Map<?, ?> col
                && col.get("post_action") instanceof Map<?, ?> pa
                && pa.get("on_success") != null && "MOVE".equalsIgnoreCase(String.valueOf(pa.get("on_success")).trim()))
            out.add(new Refusal(SINK_ARCHIVE, "collector.post_action.on_success", refusal(SINK_ARCHIVE)));
        if (!present(SINK_DUCKLAKE)) {
            if (draft.get("output") instanceof Map<?, ?> o && enabled(o.get("ducklake")))
                out.add(new Refusal(SINK_DUCKLAKE, "output.ducklake", refusal(SINK_DUCKLAKE)));
            if (draft.get("sinks") instanceof List<?> sinks)
                for (int i = 0; i < sinks.size(); i++)
                    if (sinks.get(i) instanceof Map<?, ?> s && enabled(s.get("ducklake")))
                        out.add(new Refusal(SINK_DUCKLAKE, "sinks[" + i + "].ducklake", refusal(SINK_DUCKLAKE)));
        }
        if (!present(PII_MASK) && draft.get("steps") instanceof List<?> steps)
            maskRefusals(steps, "steps", out);
        return out;
    }

    /** Every {@code mask} step in a draft chain, route branches included — a branch is no way around the gate. */
    private static void maskRefusals(List<?> steps, String prefix, List<Refusal> out) {
        for (int i = 0; i < steps.size(); i++) {
            if (!(steps.get(i) instanceof Map<?, ?> entry)) continue;
            for (Map.Entry<?, ?> e : entry.entrySet()) {
                String at = prefix + "[" + i + "]." + e.getKey();
                if (PipelineConfig.Step.MASK.equals(String.valueOf(e.getKey())))
                    out.add(new Refusal(PII_MASK, at, refusal(PII_MASK)));
                if (e.getValue() instanceof Map<?, ?> cfg && cfg.get("branches") instanceof List<?> branches)
                    for (int b = 0; b < branches.size(); b++)
                        if (branches.get(b) instanceof Map<?, ?> br && br.get("steps") instanceof List<?> sub)
                            maskRefusals(sub, at + ".branches[" + b + "].steps", out);
            }
        }
    }

    /** Every absent feature a PARSED pipeline uses — the run-time form (effective per-sink lakes). */
    public static List<Refusal> pipelineRefusals(PipelineConfig cfg) {
        List<Refusal> out = new ArrayList<>();
        if (!present(SINK_ARCHIVE) && cfg.collector() != null && cfg.collector().postAction() != null
                && "MOVE".equals(cfg.collector().postAction().onSuccess()))
            out.add(new Refusal(SINK_ARCHIVE, "collector.post_action.on_success", refusal(SINK_ARCHIVE)));
        if (!present(SINK_DUCKLAKE))
            for (PipelineConfig.Sink s : cfg.sinks())
                if (enabled(s.duckLake())) {
                    out.add(new Refusal(SINK_DUCKLAKE, "ducklake", refusal(SINK_DUCKLAKE)));
                    break;
                }
        if (!present(PII_MASK)) {
            List<Object> chain = new ArrayList<>();
            for (PipelineConfig.Step st : cfg.steps()) chain.add(Map.of(st.kind(), st.config()));
            maskRefusals(chain, "steps", out);
        }
        return out;
    }

    /** Throws the first of {@link #pipelineRefusals(PipelineConfig)}, naming the pipeline. */
    public static void requirePipeline(PipelineConfig cfg) {
        List<Refusal> r = pipelineRefusals(cfg);
        if (!r.isEmpty())
            throw new IllegalStateException(CODE + ": pipeline '" + cfg.identity().pipelineName() + "': "
                    + r.get(0).message());
    }

    /**
     * Test seam: pin the present features for the rest of this JVM's tests ({@code null} re-arms the
     * classpath scan). A test that pins must restore {@code null} in its teardown. Production never calls it.
     */
    public static void overrideForTest(Set<String> features) {
        override = features == null ? null : Set.copyOf(features);
    }

    private static boolean enabled(Object ducklake) {
        return ducklake instanceof Map<?, ?> d && Boolean.parseBoolean(String.valueOf(d.get("enabled")));
    }

    private static Set<String> discover() {
        Set<String> found = new HashSet<>();
        try {
            for (EditionFeatureProvider p : ServiceLoader.load(EditionFeatureProvider.class)) found.addAll(p.features());
        } catch (ServiceConfigurationError | LinkageError unloadable) {
            // absent, not fatal: the gate then refuses, which is the safe direction
        }
        return Set.copyOf(found);
    }
}
