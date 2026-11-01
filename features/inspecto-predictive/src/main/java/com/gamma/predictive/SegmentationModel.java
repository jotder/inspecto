package com.gamma.predictive;

import com.gamma.analytics.score.EntityCap;
import com.gamma.analytics.score.ScoreKind;
import com.gamma.query.MeasureCompiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A <b>Segmentation Model</b> (PREDICTIVE-SEGMENT-RFM-1, the Type; a Customer Segment assignment is its Instance):
 * declared RFM segmentation over one Dataset, stored as the {@code segmentation-model} component kind at
 * {@code registry/segmentation-models/<id>.toon} and run by the {@code predict.segment} Job.
 *
 * <pre>
 * entityType: customer
 * dataset: orders            key: customer_id            time: order_date (DATE or TIMESTAMP)
 * window: 365                (trailing days as of the run's as_of, 1..3660; absent = all history up to as_of)
 * frequency: count           (count | countDistinct(field))
 * monetary: sum(amount)      (sum(field) only; an entity whose values are all NULL has monetary 0)
 * bins: 5                    (quantile bins per dimension, 2..10; score 1 = worst, bins = best)
 * maxEntities: 500000        (1..2000000; default -Dpredict.segment.maxEntities, else 200000)
 * segments[3]{label,recency,frequency,monetary}:     (ordered: the first matching row names the segment)
 *   Champions,4-5,4-5,4-5
 *   At risk,1-2,3-5,*
 *   New,5,1,*
 * otherLabel: Other          (an entity no row matches; default "Other")
 * </pre>
 *
 * A score range is {@code *} (any), {@code n} or {@code a-b} inside {@code 1..bins}. Validated fail closed: every
 * problem is an {@link IllegalArgumentException} naming the field. Unknown keys are refused, not ignored.
 */
public record SegmentationModel(String id, String entityType, String dataset, String key, String time, Integer window,
                                String frequency, String monetary, int bins, Integer maxEntities, String description,
                                List<Segment> segments, String otherLabel) {

    public static final String KIND = "segmentation-model";
    /** The prefix of every Customer Segment store — the output name is derived, never authored. */
    public static final String SEGMENTS_PREFIX = "customer_segments_";
    public static final String LATEST_SUFFIX = ScoreKind.LATEST_SUFFIX;
    /** The ownership marker in each output directory, holding the model id. */
    public static final String OWNER_MARKER = ".segmentation-model-output";
    /** This kind's output identity in the score output contract (ANALYTICS-CORE-1 M7). */
    public static final ScoreKind SCORE_KIND = new ScoreKind(KIND, SEGMENTS_PREFIX, OWNER_MARKER);

    public static final int MIN_BINS = 2;
    public static final int MAX_BINS = 10;
    public static final int MAX_WINDOW_DAYS = 3660;
    public static final int MAX_SEGMENTS = 64;
    public static final int MAX_LABEL_LENGTH = 64;
    public static final int MAX_ENTITIES_CEILING = EntityCap.CEILING;
    public static final Set<String> ENVELOPE_KEYS = Set.of("name", "owner", "shares");

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_]*");
    private static final Pattern RANGE = Pattern.compile("\\*|([0-9]{1,2})(?:-([0-9]{1,2}))?");
    private static final Set<String> MODEL_KEYS = Set.of("name", "owner", "shares", "id", "entityType", "dataset",
            "key", "time", "window", "frequency", "monetary", "bins", "maxEntities", "description", "segments",
            "otherLabel");
    private static final Set<String> SEGMENT_KEYS = Set.of("label", "recency", "frequency", "monetary");

    /** An inclusive score range {@code lo..hi}; {@code *} is {@code 1..bins}. */
    public record Range(int lo, int hi) {
        public boolean contains(int score) { return score >= lo && score <= hi; }
    }

    /** One row of the label table. */
    public record Segment(String label, Range recency, Range frequency, Range monetary) {
        public boolean matches(int r, int f, int m) {
            return recency.contains(r) && frequency.contains(f) && monetary.contains(m);
        }
    }

    public SegmentationModel { segments = List.copyOf(segments); }

    public String segmentsDataset() { return SEGMENTS_PREFIX + id; }
    public String latestDataset() { return segmentsDataset() + LATEST_SUFFIX; }

    /** The parsed {@code frequency} Measure. */
    public MeasureCompiler.Measure frequencyMeasure() { return measure(frequency); }

    /** The parsed {@code monetary} Measure. */
    public MeasureCompiler.Measure monetaryMeasure() { return measure(monetary); }

    /** Every column the Dataset must carry: key, time and the Measure fields. */
    public Set<String> referencedColumns() {
        Set<String> out = new LinkedHashSet<>(List.of(key, time));
        if (frequencyMeasure().field() != null) out.add(frequencyMeasure().field());
        out.add(monetaryMeasure().field());
        return out;
    }

    /** The label of the first table row the score triple matches, else {@link #otherLabel()}. */
    public String labelOf(int r, int f, int m) {
        for (Segment s : segments) if (s.matches(r, f, m)) return s.label();
        return otherLabel;
    }

    private static MeasureCompiler.Measure measure(String shorthand) {
        Map<String, Object> m = MeasureCompiler.splitShorthand(List.of(shorthand), null).get(0);
        return new MeasureCompiler.Measure(String.valueOf(m.get("agg")),
                m.get("field") == null ? null : String.valueOf(m.get("field")));
    }

    /** Parse and validate a stored model; {@code id} is the component id. */
    public static SegmentationModel fromMap(String id, Map<String, Object> content) {
        if (content == null) throw new IllegalArgumentException("segmentation-model content is required");
        if (id == null || !MODEL_ID.matcher(id).matches() || id.length() > 64)
            throw new IllegalArgumentException("segmentation-model id must match " + MODEL_ID.pattern()
                    + " (at most 64 characters): it names the derived output Datasets");
        Set<String> unknown = new TreeSet<>(content.keySet());
        unknown.removeAll(MODEL_KEYS);
        if (!unknown.isEmpty())
            throw new IllegalArgumentException("segmentation-model has unknown key(s) " + unknown);
        String entityType = requireText(content, "entityType", SAFE_ID);
        String dataset = requireText(content, "dataset", SAFE_ID);
        String key = requireText(content, "key", SAFE_IDENT);
        String time = requireText(content, "time", SAFE_IDENT);
        Integer window = optionalInt(content, "window", 1, MAX_WINDOW_DAYS);
        String frequency = content.get("frequency") == null ? "count" : String.valueOf(content.get("frequency")).trim();
        MeasureCompiler.Measure fm = parseMeasure("frequency", frequency);
        if (!("count".equals(fm.agg()) && fm.field() == null || "countDistinct".equals(fm.agg()) && fm.field() != null))
            throw new IllegalArgumentException("segmentation-model.frequency must be count or countDistinct(field), got '"
                    + frequency + "'");
        String monetary = requireText(content, "monetary", null);
        MeasureCompiler.Measure mm = parseMeasure("monetary", monetary);
        if (!"sum".equals(mm.agg()) || mm.field() == null)
            throw new IllegalArgumentException("segmentation-model.monetary must be sum(field), got '" + monetary + "'");
        for (MeasureCompiler.Measure m : List.of(fm, mm))
            if (m.field() != null && !SAFE_IDENT.matcher(m.field()).matches())
                throw new IllegalArgumentException("segmentation-model measure field '" + m.field() + "' must match "
                        + SAFE_IDENT.pattern());
        Integer bins0 = optionalInt(content, "bins", MIN_BINS, MAX_BINS);
        int bins = bins0 == null ? 5 : bins0;
        Integer maxEntities = optionalInt(content, "maxEntities", 1, MAX_ENTITIES_CEILING);
        String description = content.get("description") == null ? null : String.valueOf(content.get("description"));
        String otherLabel = content.get("otherLabel") == null ? "Other" : label("otherLabel", content.get("otherLabel"));
        if (!(content.get("segments") instanceof List<?> raw) || raw.isEmpty())
            throw new IllegalArgumentException("segmentation-model.segments must be a non-empty list (the label table)");
        if (raw.size() > MAX_SEGMENTS)
            throw new IllegalArgumentException("segmentation-model.segments has " + raw.size() + " rows; at most "
                    + MAX_SEGMENTS);
        List<Segment> segments = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String p = "segmentation-model.segments[" + i + "]";
            if (!(raw.get(i) instanceof Map<?, ?> row))
                throw new IllegalArgumentException(p + " must be an object {label, recency, frequency, monetary}");
            Set<String> extra = new TreeSet<>();
            for (Object k : row.keySet()) if (!SEGMENT_KEYS.contains(String.valueOf(k))) extra.add(String.valueOf(k));
            if (!extra.isEmpty()) throw new IllegalArgumentException(p + " has unknown key(s) " + extra);
            String label = label(p + ".label", row.get("label"));
            if (!labels.add(label) || label.equals(otherLabel))
                throw new IllegalArgumentException(p + ".label '" + label + "' is not unique (otherLabel counts too)");
            segments.add(new Segment(label, range(p + ".recency", row.get("recency"), bins),
                    range(p + ".frequency", row.get("frequency"), bins), range(p + ".monetary", row.get("monetary"), bins)));
        }
        return new SegmentationModel(id, entityType, dataset, key, time, window, frequency, monetary, bins, maxEntities,
                description, segments, otherLabel);
    }

    private static MeasureCompiler.Measure parseMeasure(String field, String shorthand) {
        try {
            return measure(shorthand);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("segmentation-model." + field + ": " + e.getMessage(), e);
        }
    }

    static Range range(String path, Object raw, int bins) {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        var m = RANGE.matcher(s);
        if (!m.matches())
            throw new IllegalArgumentException(path + " must be *, n or a-b (scores 1.." + bins + "), got '" + s + "'");
        if ("*".equals(s)) return new Range(1, bins);
        int lo = Integer.parseInt(m.group(1));
        int hi = m.group(2) == null ? lo : Integer.parseInt(m.group(2));
        if (lo < 1 || hi > bins || lo > hi)
            throw new IllegalArgumentException(path + " range '" + s + "' must lie inside 1.." + bins + " with low <= high");
        return new Range(lo, hi);
    }

    private static String label(String path, Object raw) {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        if (s.isEmpty() || s.length() > MAX_LABEL_LENGTH || s.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(path + " must be 1.." + MAX_LABEL_LENGTH
                    + " characters with no control characters");
        return s;
    }

    private static String requireText(Map<String, Object> content, String field, Pattern pattern) {
        Object v = content.get(field);
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.isEmpty()) throw new IllegalArgumentException("segmentation-model." + field + " is required");
        if (pattern != null && !pattern.matcher(s).matches())
            throw new IllegalArgumentException("segmentation-model." + field + " '" + s + "' must match " + pattern.pattern());
        return s;
    }

    private static Integer optionalInt(Map<String, Object> content, String field, int min, int max) {
        Object v = content.get(field);
        if (v == null) return null;
        Integer n = null;
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(String.valueOf(v).trim());
            if (d.stripTrailingZeros().scale() <= 0) n = d.intValueExact();
        } catch (NumberFormatException | ArithmeticException ignored) {
            // not a whole number in int range: refused below
        }
        if (n == null || n < min || n > max)
            throw new IllegalArgumentException("segmentation-model." + field + " must be a whole number in " + min + ".."
                    + max + ", got '" + v + "'");
        return n;
    }
}
