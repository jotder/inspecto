package com.gamma.pipeline.exec;

import com.gamma.etl.FailureText;
import com.gamma.etl.PipelineConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The unmatched-key sample of a keyed {@code transform.join} (LOOKUP-1 §4.6), in the only form that is stored or
 * served: never the value, only its salted fingerprint ({@link FailureText#fingerprint}, the rejected rows' own), its
 * length, and a shape that keeps a short prefix — enough to see "every miss starts 4479" against a prefix table — with
 * the rest masked. Operator decision 2026-11-23: "Fingerprint + shape".
 */
public final class LookupMissSample {

    private LookupMissSample() {}

    /** One stored sample entry: the mapping (input column), the value's fingerprint, length and shape, and its count. */
    public record Entry(String mapping, String fingerprint, int length, String shape, long n) {}

    /** {@code misses} as stored entries, under {@code cfg}'s Space salt. */
    public static List<Entry> of(PipelineConfig cfg, List<RowShaper.LookupMiss> misses) {
        List<Entry> out = new ArrayList<>();
        for (RowShaper.LookupMiss m : misses) {
            String v = m.value() == null ? "" : m.value();
            out.add(new Entry(m.mapping(), FailureText.fingerprint(cfg, v), v.codePointCount(0, v.length()), shape(v), m.n()));
        }
        return out;
    }

    /**
     * The first {@code min(4, length / 3)} characters, each later one as {@code •}: {@code 447912345678} →
     * {@code 4479••••••••}; a value under 3 characters is fully masked.
     */
    static String shape(String v) {
        int[] cps = v.codePoints().toArray();
        int keep = Math.min(4, cps.length / 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            if (i < keep) sb.appendCodePoint(cps[i]);
            else sb.append('•');
        }
        return sb.toString();
    }
}
