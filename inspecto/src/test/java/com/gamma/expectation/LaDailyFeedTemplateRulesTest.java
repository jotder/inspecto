package com.gamma.expectation;

import com.gamma.alert.AlertRule;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-DATA-TRUST-1 S3, S4, S7 / S8: the rules the shipped {@code spaces/_templates/la-daily-feed} template carries load through
 * the same ComponentStore a Space registry uses and mean what the operator asked - a freshness Alert Rule that follows the
 * poll interval, an index-lag Alert Rule, and a trailing-days row-count band. Reads the template tree itself, not a copy.
 */
class LaDailyFeedTemplateRulesTest {

    private static ComponentStore registry() {
        return new ComponentStore(Path.of("..", "spaces", "_templates", "la-daily-feed", "config", "registry").toAbsolutePath().normalize());
    }

    private static AlertRule rule(String name) {
        ComponentRegistry.Component c = registry().get("alert-rule", name).orElseThrow(() -> new AssertionError("template ships no Alert Rule " + name));
        return AlertRule.fromMap(c.content());
    }

    @Test
    void theFreshnessRuleFollowsThePollIntervalOfTheFeedingPipeline() {
        AlertRule r = rule("daily_xdr_freshness");
        assertEquals("daily_xdr_dataset", r.dataset());
        assertTrue(r.isFreshnessRule());
        assertTrue(r.followsPoll(), "maximumAge: auto, not a hand-picked number");
        assertEquals("daily_xdr", r.onPipeline());
        assertFalse(r.indexLag());
    }

    @Test
    void theIndexLagRuleTakesALiteralLimit() {
        AlertRule r = rule("daily_xdr_index_lag");
        assertEquals("daily_xdr_dataset", r.dataset());
        assertTrue(r.indexLag());
        assertEquals("2h", r.maximumAge(), "an index-lag rule takes a literal limit");
    }

    @Test
    void theVolumeBandMeansWhatTheOperatorAsked() {
        List<ComponentRegistry.Component> found = registry().list("expectation");
        assertEquals(1, found.size());
        Expectation e = Expectation.fromMap(found.get(0).content());
        assertEquals("baseline", e.kind());
        assertEquals("daily_xdr/database", e.target());
        var b = e.baseline();
        assertEquals(List.of("EVENT_DATE"), b.groupBy());
        assertEquals(7, b.trailingDays());
        assertEquals(3, b.minPriorDays());
        assertEquals(50.0, b.maxDecrease());
        assertEquals(100.0, b.maxIncrease());
        assertEquals("percent", b.limitUnit());
    }
}
