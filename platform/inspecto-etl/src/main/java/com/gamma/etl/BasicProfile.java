package com.gamma.etl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * <b>The Basic profile</b> ({@code BASIC-ELT-PROFILE-1} Gate): Basic is the ELT path with options locked, never a second
 * implementation, and this class is the one place that says which options. For each dimension - Collector schemes, the triggers
 * Basic's entry points write, Step node types, Job types - it names what Basic keeps; everything else needs the Standard feature
 * it names ({@link EditionFeatures}). Basic's own entry points are declared as {@link Entry}, each with the feature it needs.
 *
 * <p>Read by the screens - the {@code /bootstrap} flags ({@link #SCREEN_FLAGS}), the Step Processor catalog's
 * {@code featureFlag} ({@code ProcessorCatalog.featureOf}), {@code GET /jobs/types} - and by Basic's own routes
 * ({@link Entry#offered}). <b>DT-9 as narrowed</b> (operator ruling O2, 2026-11-19): nothing here refuses a configuration on
 * load or save; Basic runs any configuration it is given, and a route of Basic's refuses only an entry point this declaration
 * locks.
 */
public final class BasicProfile {

    private BasicProfile() {}

    /**
     * The screen gates by their {@code /bootstrap} {@code features} key (wire order): each flag is true when its feature is
     * {@link EditionFeatures#present}, and a withheld one has a {@code featureReasons} entry under the same key.
     */
    public static final Map<String, String> SCREEN_FLAGS = screenFlags();

    private static Map<String, String> screenFlags() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pipelineAutomation", EditionFeatures.PIPELINE_AUTOMATION);
        m.put("pipelineTransform", EditionFeatures.PIPELINE_TRANSFORM);
        m.put("pipelineEgress", EditionFeatures.PIPELINE_EGRESS);
        m.put("remoteSystems", EditionFeatures.REMOTE_SYSTEMS);
        return Collections.unmodifiableMap(m);
    }

    /** The {@link #SCREEN_FLAGS} key of {@code feature}, or null when it is not a screen gate. */
    public static String flagOf(String feature) {
        for (Map.Entry<String, String> e : SCREEN_FLAGS.entrySet()) if (e.getValue().equals(feature)) return e.getKey();
        return null;
    }

    /**
     * The Collector schemes Basic keeps: a folder on this machine ({@code local}), a registered Dataset ({@code dataset}) and Add
     * data's Postgres table copy ({@code postgres}, BE-4: DT-4 reversed for Postgres only). Every other scheme reaches a remote
     * system and needs {@link EditionFeatures#REMOTE_SYSTEMS}. A kept scheme's Collector settings are Basic's too, the
     * duplicate check included - how a re-seen file is recognised ({@code collector.duplicate.mode}) and what happens to a changed
     * one ({@code collector.duplicate.on_change}) are offered on Basic (operator ruling 2026-11-20); the profile changes no
     * default.
     */
    public static final Set<String> SCHEMES = Set.of("local", "dataset", "postgres");

    /**
     * The one schedule Basic writes: <i>Watch this folder</i>'s preset, its {@code local} Collector polled every 30 s (B1, ruling
     * O1). Basic's other entry points write {@code trigger: manual}; any other schedule is a Pipeline that runs on its own
     * ({@link EditionFeatures#PIPELINE_AUTOMATION}).
     */
    public static final String WATCH_EVERY = "30s";

    /**
     * The Step node types Basic keeps (DT-7, DT-15): filter rows and dedupe. {@code transform.mask} is left to its own gate
     * ({@link EditionFeatures#PII_MASK}, Advanced). Every other {@code transform.*} Step and enrichment need
     * {@link EditionFeatures#PIPELINE_TRANSFORM}.
     */
    public static final Set<String> STEPS = Set.of("transform.filter", "transform.dedup", "transform.mask");

    /** The Job types that deliver data out of the product (P5): {@link EditionFeatures#PIPELINE_EGRESS}. Every other is Basic's. */
    public static final Set<String> EGRESS_JOB_TYPES = Set.of("objectstore.export", "publish.postgres");

    /** The feature a Collector {@code scheme} needs beyond Basic, or null when Basic keeps it. */
    public static String schemeFeature(String scheme) {
        return scheme == null || SCHEMES.contains(scheme) ? null : EditionFeatures.REMOTE_SYSTEMS;
    }

    /** The feature a Step of node type {@code nodeType} needs beyond Basic, or null when Basic keeps it (or it is not a Step). */
    public static String stepFeature(String nodeType) {
        if (nodeType == null) return null;
        return "enrichment".equals(nodeType) || (nodeType.startsWith("transform.") && !STEPS.contains(nodeType))
                ? EditionFeatures.PIPELINE_TRANSFORM : null;
    }

    /** The feature a Job type needs beyond Basic, or null. */
    public static String jobTypeFeature(String type) {
        return EGRESS_JOB_TYPES.contains(type) ? EditionFeatures.PIPELINE_EGRESS : null;
    }

    /**
     * Basic's own entry points (the Add data surface) and the feature each needs beyond Basic: null where every edition has it.
     * The route of a locked one answers {@code 503 CAPABILITY_UNAVAILABLE} without its feature; what the others write stays within
     * {@link #SCHEMES}, {@code trigger: manual} or {@link #WATCH_EVERY}, and {@link #STEPS}.
     */
    public enum Entry {
        /** <i>Add a file</i>: {@code POST /onboard/files} and its commit, {@code POST /datasets/{id}/files}, Run now - a {@code local}, manual import. */
        ADD_FILE(null),
        /** <i>Choose a table</i>: {@code POST /onboard/postgres/test}, {@code POST /onboard/postgres} - a {@code postgres} copy, manual. */
        POSTGRES_TABLE(null),
        /** <i>Watch this folder</i>: {@code PUT /datasets/{id}/watch} - the {@code local} Collector on the {@link #WATCH_EVERY} preset. */
        WATCH_FOLDER(null),
        /** <i>Keep this up to date</i>: {@code PUT /datasets/{id}/feed} - a schedule the person sets, a folder or SFTP. */
        KEEP_UP_TO_DATE(EditionFeatures.PIPELINE_AUTOMATION),
        /** A retention policy: {@code PUT /datasets/{id}/storage} {@code retention}, and the policy in force at compaction. */
        RETENTION(EditionFeatures.PIPELINE_AUTOMATION);

        private final String feature;

        Entry(String feature) {
            this.feature = feature;
        }

        /** The feature this entry point needs beyond Basic, or null. */
        public String feature() {
            return feature;
        }

        /** Whether this install offers the entry point: Basic's own, or its feature is present. */
        public boolean offered() {
            return feature == null || EditionFeatures.present(feature);
        }
    }
}
