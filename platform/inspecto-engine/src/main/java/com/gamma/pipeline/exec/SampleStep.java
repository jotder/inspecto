package com.gamma.pipeline.exec;

import com.gamma.analytics.sample.Sampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The config grammar of {@code transform.sample} ({@code steps:} kind {@code sample}, catalog
 * {@code quality.sample.reservoir}, ANALYTICS-CORE-1 S4): a deterministic, seeded sample of the rows — a fixed size or a
 * fraction per group. The SQL is the Analytics Kernel's ({@link Sampler}): rows ordered by an MD5 hash of the seed and
 * a stable row key, never DuckDB's {@code USING SAMPLE} or {@code random()}, so the same input, key and seed give the
 * same rows on every run and in the JVM twin.
 *
 * <p>The key defaults to every inbound column (in order); two rows equal in every column are interchangeable. A declared
 * {@code key} is tie-broken by every inbound column, ascending, NULLs last. Columns pass through unchanged.
 */
public final class SampleStep {

    private SampleStep() {}

    static final String KIND = "transform.sample";

    /** The prefix of the sampler's working columns; an inbound column starting with it is refused. */
    static final String SCRATCH = "__sample_";

    private static final Set<String> KEYS = Set.of("size", "fraction", "seed", "group_by", "key");

    /** A parsed config; an empty {@code key} means every inbound column. */
    public record Spec(Sampler.Spec sample, List<String> groupBy, List<String> key) {

        /** The columns the inbound relation must carry. */
        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            out.addAll(key);
            return out;
        }

        /** The sampled rows of {@code input}, columns unchanged. */
        public String select(String input, List<String> inbound) {
            return Sampler.sql(sample, input, groupBy, key.isEmpty() ? inbound : key, inbound, SCRATCH);
        }
    }

    /** Parse and validate a config; {@link IllegalArgumentException} names the first problem. */
    public static Spec parse(Map<?, ?> cfg) {
        StepKeys.known(cfg, KIND, KEYS);
        boolean sized = !StepKeys.blank(cfg.get("size")), shared = !StepKeys.blank(cfg.get("fraction"));
        if (sized == shared)
            throw StepKeys.bad(KIND, "needs exactly one of 'size' (rows per group) or 'fraction' (share of each group)");
        Long seed = StepKeys.wholeNumber(cfg, "seed", KIND, Long.MIN_VALUE, Long.MAX_VALUE);
        long s = seed == null ? 0 : seed;
        Sampler.Spec sample;
        if (sized) {
            sample = Sampler.Spec.ofSize(s, StepKeys.wholeNumber(cfg, "size", KIND, 1, Sampler.MAX_SIZE).intValue());
        } else {
            Object f = cfg.get("fraction");
            double fraction;
            try {
                fraction = Double.parseDouble(f.toString().trim());
            } catch (NumberFormatException e) {
                throw StepKeys.bad(KIND, "'fraction' '" + f + "' is not a number");
            }
            try {
                sample = Sampler.Spec.ofFraction(s, fraction);
            } catch (IllegalArgumentException e) {
                throw StepKeys.bad(KIND, "'fraction' must be more than 0 and at most 1 (at least 0.0001), got " + f);
            }
        }
        List<String> groupBy = StepKeys.columns(cfg, "group_by", KIND);
        List<String> key = StepKeys.columns(cfg, "key", KIND);
        return new Spec(sample, List.copyOf(groupBy), List.copyOf(key));
    }

    /** The first refusal against the inbound columns, or {@code null}. */
    public static String columnRefusal(Spec spec, List<String> inbound) {
        String missing = StepKeys.columnRefusal(KIND, spec.inputColumns(), List.of(), inbound, "");
        if (missing != null) return missing;
        for (String c : inbound)
            if (c.toLowerCase(Locale.ROOT).startsWith(SCRATCH))
                return KIND + ": inbound column '" + c + "' uses the sampler's reserved prefix '" + SCRATCH
                        + "' - rename it upstream";
        return null;
    }
}
