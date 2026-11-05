package com.gamma.geolink;

import com.gamma.linkindex.LinkIndexAccess;
import com.gamma.linkindex.LinkIndexBuilder;
import com.gamma.linkindex.LinkMapping;
import com.gamma.onboard.ImportFollowOn;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Link Analysis's follow-on step of an import (BASIC-ONBOARD-1 P1): after Add data, Add a file or Run now writes the
 * Dataset's Parquet, build its link index inside the user's run (DT-16). Reported under {@code index}; a Dataset without
 * a {@code defaultLinkMapping} (details about entities) is SKIPPED. Contributed by {@code ServiceLoader}, beside
 * {@link ScheduledLinkIndexBuilder}.
 */
public final class LinkIndexFollowOn implements ImportFollowOn {

    @Override
    public String key() {
        return "index";
    }

    @Override
    public String does() {
        return "builds its link index";
    }

    @Override
    public Set<String> needs() {
        return Set.of(LinkMapping.KEY);
    }

    @Override
    public Map<String, Object> deferred() {
        return outcome("DEFERRED", "the Space's Jobs build the index");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Context c) {
        if (!(c.entry().get(LinkMapping.KEY) instanceof Map<?, ?> raw))
            return outcome("SKIPPED", "the Dataset has no link mapping");
        Map<String, Object> m = (Map<String, Object>) raw;
        LinkIndexBuilder builder = com.gamma.spi.OptionalSpi.first(LinkIndexBuilder.class).orElse(null);
        if (builder == null) return outcome("NOT_AVAILABLE", "Link Analysis is not installed, so there is no index to build");
        try {
            LinkIndexAccess.Outcome o = builder.build(c.writeRoot(), c.dataRoot(), new LinkIndexAccess.Request(
                    "onboard:" + c.dataset(), c.dataset(), (String) m.get("from"), (String) m.get("to"), (String) m.get("kind"),
                    LinkMapping.primary(m, LinkMapping.PRIMARY_TIME, "time"), null,
                    // LA-COLUMN-ROLES-1 P2: every Time, Measure and Category column rides in the index
                    LinkMapping.primary(m, LinkMapping.PRIMARY_MEASURE, "amount"), LinkMapping.carriedColumns(c.entry()),
                    c.actor(), true, 300_000L, 0L));
            Map<String, Object> r = outcome(o.result(), o.message());
            r.put("mode", o.mode());   // append / full / compact / none: what the build cost (V4)
            r.put("code", o.code());
            r.put("edges", o.edges());
            r.put("nodes", o.nodes());
            return r;
        } catch (RuntimeException failed) {
            return outcome("FAILED", "the index could not be built: " + failed.getMessage());
        }
    }

    private static Map<String, Object> outcome(String result, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", result);
        m.put("message", message);
        return m;
    }
}
