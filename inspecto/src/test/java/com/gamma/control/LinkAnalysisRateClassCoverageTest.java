package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Link Analysis route must have an explicit rate-limit class: EXPENSIVE (throttled) or EXEMPT (listed
 * in {@link LinkAnalysisRateClasses}). Adding a route to either absent-surface table without classifying it
 * fails here. {@code GeoLinkAbsentSurfaceParityTest} / {@code LaApiRoutesManifestParityTest} pin the manifests' {@code provides.routes} to the live module routes.
 */
class LinkAnalysisRateClassCoverageTest {

    private static Set<String> keys(java.util.List<String[]> t) { return keys(t.toArray(new String[0][])); }

    private static Set<String> keys(String[][] t) {
        Set<String> out = new TreeSet<>();
        for (String[] r : t) out.add(r[0] + " " + r[1]);
        return out;
    }

    @Test
    void everyLinkAnalysisRouteIsClassifiedExactlyOnce() {
        Set<String> surface = new TreeSet<>(keys(AbsentModuleRoutes.surface("la-api", "geo-link")));
        surface.addAll(keys(AbsentModuleRoutes.surface("entity-list")));
        Set<String> expensive = keys(LinkAnalysisRateClasses.EXPENSIVE);
        Set<String> exempt = keys(LinkAnalysisRateClasses.EXEMPT);

        Set<String> both = new TreeSet<>(expensive);
        both.retainAll(exempt);
        assertEquals(Set.of(), both, "a route cannot be both EXPENSIVE and EXEMPT");

        Set<String> classified = new TreeSet<>(expensive);
        classified.addAll(exempt);
        Set<String> unclassified = new TreeSet<>(surface);
        unclassified.removeAll(classified);
        assertEquals(Set.of(), unclassified,
                "Link Analysis route(s) with no rate-limit class - add to LinkAnalysisRateClasses.EXPENSIVE or EXEMPT");
        Set<String> stale = new TreeSet<>(classified);
        stale.removeAll(surface);
        assertEquals(Set.of(), stale, "classified but no longer a Link Analysis route");
    }

    @Test
    void expensiveRoutesMatchTheClassifierAndExemptOnesDoNot() {
        List<String> wrong = new ArrayList<>();
        for (String[] r : LinkAnalysisRateClasses.EXPENSIVE)
            if (!LinkAnalysisRateClasses.isExpensive(r[0], r[1].replace("([^/]+)", "x"))) wrong.add(r[0] + " " + r[1]);
        for (String[] r : LinkAnalysisRateClasses.EXEMPT)
            if (LinkAnalysisRateClasses.isExpensive(r[0], r[1].replace("([^/]+)", "x"))) wrong.add("exempt " + r[0] + " " + r[1]);
        assertTrue(wrong.isEmpty(), "misclassified: " + wrong);
        assertTrue(LinkAnalysisRateClasses.isExpensive("POST", "/inv/graph/runs"));
        assertTrue(LinkAnalysisRateClasses.isExpensive("POST", "/inv/index/builds"));
        assertTrue(!LinkAnalysisRateClasses.isExpensive("GET", "/inv/graph/runs"));
    }
}
