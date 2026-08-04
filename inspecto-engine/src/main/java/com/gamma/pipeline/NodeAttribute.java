package com.gamma.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gamma.ops.findings.FindingsSpec;

/**
 * One config attribute of a pipeline node type — the server-side half of the UI's {@code AttributeSpec}
 * ({@code inspecto-ui/src/app/inspecto/component-model/attribute-spec.ts}), published on
 * {@code GET /pipelines/node-types} so the node cfg vocabulary has ONE definition instead of living only
 * client-side (§3.1 of {@code docs/superpower/vocabulary-and-config-contract-plan.md}).
 *
 * <p>Why this exists: §1 of that plan traced every config-key defect (D1–D9) to the same root cause —
 * per-node cfg keys were declared ONLY in the client table, the server's node-type catalog carried no
 * attribute vocabulary at all, and there is no case-conversion layer anywhere, so a client key that did
 * not exactly equal the engine's key silently no-opped. Publishing the specs closes that by construction:
 * the catalog is now the source and {@code node-attributes.ts} is its fallback.
 *
 * <p><b>The control vocabulary is deliberately NOT re-declared here.</b> {@link #TYPES} and {@link #TIERS}
 * delegate to {@link FindingsSpec}, the other server-authored spec surface, so the two can never drift into
 * disagreeing about what {@code <inspecto-schema-form>} can draw. Adding an {@code AttributeType} stays a
 * one-place change.
 *
 * <p>⚠ <b>{@code dependsOn} is intentionally absent.</b> No node attribute uses conditional visibility
 * today; the UI type supports it, and adding it here is a two-line change plus a {@code toMap} branch when
 * a node actually needs one. Modelling it unused would be speculative surface with no test to hold it
 * honest.
 *
 * <p>{@code required} is boxed so "unset" stays distinguishable from {@code false} — the renderer derives
 * it from {@code tier} when unset, and collapsing that would silently make optional fields mandatory.
 */
public record NodeAttribute(String key, String label, String type, String tier, Boolean required,
                            Object defaultValue, List<Option> options, Double min, Double max,
                            String help, String placeholder) {

    /** Renderer-supported control types. Single source: {@link FindingsSpec#TYPES}. */
    public static final java.util.Set<String> TYPES = FindingsSpec.TYPES;

    /** Disclosure tiers. Single source: {@link FindingsSpec#TIERS}. */
    public static final java.util.Set<String> TIERS = FindingsSpec.TIERS;

    /** A {@code select} choice. */
    public record Option(String value, String label) {}

    /**
     * Fail-fast on a spec the renderer could not draw. These tables are compiled in rather than authored,
     * so a bad one is a programming error that must surface on first touch — not a 422 at runtime.
     *
     * <p>⚠ Only per-field invariants belong here. "A {@code select} must have options" is checked by
     * {@link #validate} on the ASSEMBLED table instead, because the builders below chain — the type is set
     * by {@code of(...)} and the options arrive a call later, so a constructor check would reject every
     * select attribute in the file before it was finished.
     */
    public NodeAttribute {
        options = options == null ? List.of() : List.copyOf(options);
        if (key == null || key.isBlank()) throw new IllegalArgumentException("node attribute needs a key");
        if (!TYPES.contains(type)) throw new IllegalArgumentException("unknown attribute type '" + type + "' for " + key);
        if (!TIERS.contains(tier)) throw new IllegalArgumentException("unknown attribute tier '" + tier + "' for " + key);
    }

    /** Whole-spec invariants, checked once the builder chain has finished (see the constructor's note). */
    void validate() {
        if ("select".equals(type) && options.isEmpty())
            throw new IllegalArgumentException("select attribute '" + key + "' has no options");
    }

    // ── terse builders, so the tables below read as data rather than constructor noise ──────────

    static NodeAttribute of(String key, String label, String type, String tier) {
        return new NodeAttribute(key, label, type, tier, null, null, List.of(), null, null, null, null);
    }

    NodeAttribute help(String help) {
        return new NodeAttribute(key, label, type, tier, required, defaultValue, options, min, max, help, placeholder);
    }

    NodeAttribute placeholder(String placeholder) {
        return new NodeAttribute(key, label, type, tier, required, defaultValue, options, min, max, help, placeholder);
    }

    /** Explicitly decouple validation from visibility (an always-visible but optional field). */
    NodeAttribute required(boolean required) {
        return new NodeAttribute(key, label, type, tier, required, defaultValue, options, min, max, help, placeholder);
    }

    NodeAttribute defaultValue(Object defaultValue) {
        return new NodeAttribute(key, label, type, tier, required, defaultValue, options, min, max, help, placeholder);
    }

    NodeAttribute min(double min) {
        return new NodeAttribute(key, label, type, tier, required, defaultValue, options, min, max, help, placeholder);
    }

    /** {@code value, label, value, label, …} — the option list, kept inline so a table stays one line per key. */
    NodeAttribute options(String... valueLabelPairs) {
        if (valueLabelPairs.length % 2 != 0) throw new IllegalArgumentException("options need value,label pairs");
        List<Option> opts = new ArrayList<>();
        for (int i = 0; i < valueLabelPairs.length; i += 2)
            opts.add(new Option(valueLabelPairs[i], valueLabelPairs[i + 1]));
        return new NodeAttribute(key, label, type, tier, required, defaultValue, opts, min, max, help, placeholder);
    }

    /**
     * The wire shape {@code <inspecto-schema-form>} consumes directly — same contract as
     * {@code FindingsSpec.sectionMap}: absent keys stay ABSENT rather than serializing as null, because
     * the TS side distinguishes "unset" from "explicitly empty" for {@code required} and {@code default}.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("type", type);
        m.put("tier", tier);
        if (required != null) m.put("required", required);
        if (defaultValue != null) m.put("default", defaultValue);
        if (!options.isEmpty()) {
            List<Map<String, Object>> opts = new ArrayList<>();
            for (Option o : options) {
                // NOT Map.of: it is UNORDERED, so `value`/`label` would swap between JVM runs and the
                // committed contract JSON would fail its own byte comparison at random.
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("value", o.value());
                opt.put("label", o.label());
                opts.add(opt);
            }
            m.put("options", opts);
        }
        if (min != null) m.put("min", min);
        if (max != null) m.put("max", max);
        if (help != null) m.put("help", help);
        if (placeholder != null) m.put("placeholder", placeholder);
        return m;
    }
}
