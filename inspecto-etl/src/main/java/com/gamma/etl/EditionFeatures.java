package com.gamma.etl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The Professional+ features whose code ships in every build but whose USE the edition board
 * ({@code docs/EDITIONS.md}) reserves for Professional and Enterprise — {@code PROCESSOR-RELEASE-READINESS-1}
 * G9, operator decision 2026-09-24 ("gate the code"):
 * <ul>
 *   <li>{@link #ALERT_DISPATCH} — Alert Rules ({@code SP-CTL-07}); declared by {@code inspecto-ops}, whose
 *       object store holds the ALERT objects a fired rule opens.</li>
 *   <li>{@link #SINK_ARCHIVE} — the compliance archive, {@code collector.post_action.on_success: MOVE}
 *       ({@code SP-SNK-06}); declared by {@code inspecto-backup}, which carries the archive's other half,
 *       the {@code backup} maintenance task.</li>
 *   <li>{@link #SINK_DUCKLAKE} — DuckLake catalog registration, {@code ducklake.enabled: true}
 *       ({@code SP-SNK-03}); declared by {@code inspecto-backup} too — it has no module of its own, and
 *       rides with the storage module every Professional bundle ships.</li>
 * </ul>
 *
 * <p>A feature is present when an {@link EditionFeatureProvider} on the classpath names it. Personal
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

    /** The finding / error code every refusal carries. Mirrors {@code FindingCodes.ERR_EDITION_FEATURE}. */
    public static final String CODE = "ERR_EDITION_FEATURE";

    /** One refused use: the feature, the config field that uses it, and the message naming both. */
    public record Refusal(String feature, String field, String message) {}

    private static volatile Set<String> discovered;
    private static volatile Set<String> override;

    private EditionFeatures() {}

    /** Whether this build brings {@code feature}. */
    public static boolean present(String feature) {
        Set<String> o = override;
        if (o != null) return o.contains(feature);
        Set<String> d = discovered;
        if (d == null) discovered = d = discover();
        return d.contains(feature);
    }

    /** The refusal message for {@code feature}, naming it and the edition that has it. */
    public static String refusal(String feature) {
        String what = switch (feature) {
            case ALERT_DISPATCH -> "Alert Rules (control.alert.dispatch)";
            case SINK_ARCHIVE -> "the compliance archive (sink.archive — collector.post_action.on_success: MOVE)";
            case SINK_DUCKLAKE -> "DuckLake catalog registration (sink.ducklake — ducklake.enabled: true)";
            default -> "'" + feature + "'";
        };
        return what + " is a Professional+ feature — this build (Personal) does not include it";
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
        return out;
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
