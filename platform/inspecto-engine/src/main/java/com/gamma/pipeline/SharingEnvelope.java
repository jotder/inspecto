package com.gamma.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The sharing envelope ({@code owner}, {@code shares}) of something derived from several components — KPI History
 * (`BI-INSIGHTS-1` I-3), a model's score Datasets (I-14): readable only by who may read EVERY source. A component
 * without {@code shares} is open to everyone, so the shared ones' envelope applies; shared ones must be shared alike,
 * since an envelope cannot state an intersection.
 */
public final class SharingEnvelope {

    private SharingEnvelope() {}

    /**
     * @return the derived thing's {@code owner} / {@code shares} (empty = open)
     * @throws SecurityException when two sources are shared differently
     */
    public static Map<String, Object> of(List<Map<String, Object>> sources) {
        Map<String, Object> shared = null;
        for (Map<String, Object> s : sources) {
            if (!s.containsKey("shares")) continue;
            if (shared == null) shared = s;
            else if (!(Objects.equals(shared.get("owner"), s.get("owner")) && Objects.equals(shared.get("shares"), s.get("shares"))))
                throw new SecurityException("its sources are shared differently; it would show one source's values to "
                        + "another's readers — share them alike, or leave all but one unshared");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (shared != null) for (String k : List.of("owner", "shares")) if (shared.containsKey(k)) out.put(k, shared.get(k));
        return out;
    }
}
