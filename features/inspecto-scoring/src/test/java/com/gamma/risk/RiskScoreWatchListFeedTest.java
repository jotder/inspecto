package com.gamma.risk;

import com.gamma.entitystore.EntityFactsForTest;
import com.gamma.risk.RiskScoreModel;
import com.gamma.risk.RiskScorer;
import com.gamma.entitylist.WatchListFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASSURE-ENTITY-LISTS-1 x ASSURE-RISK-SCORE-1: the {@code risk.score} Job's watch-list feed, with Entity Lists
 * installed (this module provides the {@link WatchListFeed}). The property: every HIGH entity, and only those, lands
 * on the watch Entity List expiring ttlHours later, as one Identity Fact, in the sidecar too. A permanent member is
 * never shortened, and only a live {@code watch} list can be fed.
 */
class RiskScoreWatchListFeedTest {

    private static RiskScoreModel model(String list, int ttl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", "subscriber");
        m.put("highThreshold", 50);
        m.put("factors", List.of(Map.of("id", "n", "dataset", "d", "key", "k", "measure", "count", "weight", 30)));
        m.put("watchList", Map.of("list", list, "ttlHours", ttl));
        return RiskScoreModel.fromMap("subs", m);
    }

    @Test
    void theEntityListsModuleProvidesTheFeed() {
        assertEquals("com.gamma.entitylist.RiskWatchListFeed", WatchListFeed.installed().orElseThrow().getClass().getName());
    }

    @Test
    void everyHighEntityAndOnlyThoseIsWatchedUntilTheTtl(@TempDir Path root, @TempDir Path data) throws Exception {
        EntityFactsForTest.create(root, "mule-watch", "watch");
        RiskScoreModel model = model("mule-watch", 12);
        List<RiskScorer.Scored> scored = List.of(
                RiskScorer.score(model, "0044 7700 900001", Map.of("n", 2.0), Map.of()),   // 60: high
                RiskScorer.score(model, "+447700900002", Map.of("n", 1.0), Map.of()),     // 30: not high
                RiskScorer.score(model, "+447700900003", Map.of("n", 3.0), Map.of()));    // 90: high
        Instant now = Instant.now();

        assertEquals(2, RiskScoreJobType.feedWatchList(root, data, model, "r1", now, scored));
        List<Map<String, Object>> facts = EntityFactsForTest.facts(root);
        assertEquals(2, facts.size(), "ONE fact for the whole run");
        Map<String, Object> fed = facts.get(1);
        assertEquals("list.member.added", fed.get("kind"));
        assertEquals(List.of("+447700900001", "+447700900003"), fed.get("keys"), "normalised by the list's sealed rule");
        assertEquals(now.plusSeconds(12 * 3600).toString(), fed.get("expiresAt"), "expires ttlHours after the run");
        assertEquals("job:risk.score:subs", fed.get("actor"));
        assertTrue(String.valueOf(fed.get("reason")).contains("run r1"), fed.toString());
        assertTrue(Files.isRegularFile(data.resolve("entity_list_mule-watch/entries.parquet")), "the sidecar is refreshed");

        assertEquals(0, RiskScoreJobType.feedWatchList(root, data, model, "r1", now, scored),
                "the same run again changes nothing and writes no fact");
        assertEquals(2, EntityFactsForTest.facts(root).size());
        assertEquals(2, RiskScoreJobType.feedWatchList(root, data, model, "r2", now.plusSeconds(60), scored),
                "a later run moves the expiry of both");
        assertEquals(now.plusSeconds(60 + 12 * 3600).toString(),
                EntityFactsForTest.expiries(root, "mule-watch").get("+447700900003"));
    }

    @Test
    void aPermanentWatchIsNeverShortenedByTheFeed(@TempDir Path root, @TempDir Path data) throws Exception {
        EntityFactsForTest.create(root, "mule-watch", "watch");
        EntityFactsForTest.append(root, "analyst-1", "list.member.added", "mule-watch", Map.of("keys", List.of("+441")));
        RiskScoreModel model = model("mule-watch", 1);
        assertEquals(0, RiskScoreJobType.feedWatchList(root, data, model, "r1", Instant.now(),
                List.of(RiskScorer.score(model, "+441", Map.of("n", 2.0), Map.of()))));
        assertNull(EntityFactsForTest.expiries(root, "mule-watch").get("+441"), "still permanent");
        assertEquals(2, EntityFactsForTest.facts(root).size(), "no fact written");
    }

    @Test
    void onlyALiveWatchListCanBeFedAndOnlyWithin24Hours(@TempDir Path root, @TempDir Path data) throws Exception {
        EntityFactsForTest.create(root, "blocked", "block");
        EntityFactsForTest.create(root, "old-watch", "watch");
        EntityFactsForTest.append(root, "analyst-1", "list.retired", "old-watch", Map.of());
        WatchListFeed feed = WatchListFeed.installed().orElseThrow();
        IllegalArgumentException purpose = assertThrows(IllegalArgumentException.class, () -> feed.check(root, "blocked"));
        assertTrue(purpose.getMessage().contains("'watch'"), purpose.getMessage());
        assertThrows(IllegalArgumentException.class, () -> feed.check(root, "nope"), "an unknown list");
        assertThrows(IllegalArgumentException.class, () -> feed.check(root, "old-watch"), "a retired list");
        assertThrows(IllegalArgumentException.class, () -> feed.feed(root, data, "blocked", List.of("+441"),
                Instant.now().plusSeconds(3600), "job:x", "r"), "a block list is never fed");
        EntityFactsForTest.create(root, "w", "watch");
        assertThrows(IllegalArgumentException.class, () -> feed.feed(root, data, "w", List.of("+441"),
                Instant.now().plusSeconds(25 * 3600), "job:x", "r"), "longer than 24 h needs four-eyes (D-P5)");
        assertEquals(4, EntityFactsForTest.facts(root).size(), "no refusal wrote a fact");
        assertFalse(Files.exists(data.resolve("entity_list_blocked")), "nor a sidecar");
    }
}
