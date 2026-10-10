package com.gamma.anomaly;

import com.gamma.entitylist.WatchListFeed;
import com.gamma.entitystore.EntityFactsForTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Design §9 with Entity Lists installed (the inspecto-entity-list module provides the {@link WatchListFeed}): a model's
 * {@code watchList} receives every {@code high} entity and only those, expiring {@code ttlHours} later; its
 * {@code exclusionList} removes live members before the scores are written; both refuse a list of the wrong purpose,
 * an unknown list, or an edition without Entity Lists — at save and at run.
 */
class AnomalyListsFeedTest {

    private static final Optional<WatchListFeed> FEED = WatchListFeed.installed();

    private static AnomalyModel model() {
        return AnomalyModel.fromMap("usage", AnomalyCorpus.model());
    }

    private static AnomalyScorer.Scored scored(String key, String band) {
        return new AnomalyScorer.Scored(key, "high".equals(band) ? 95 : 10, band, 1, List.of(), 0);
    }

    private static final AnomalyScoreEvaluator.Run RUN = new AnomalyScoreEvaluator.Run(LocalDate.parse("2026-09-30"),
            List.of(scored("+447700900001", "high"), scored("+447700900002", "elevated"),
                    scored("+447700900003", "high"), scored("+447700900004", "normal")));

    @Test
    void everyHighEntityAndOnlyThoseIsWatchedUntilTheTtl(@TempDir Path root, @TempDir Path data) throws Exception {
        EntityFactsForTest.create(root, "usage-watch", "watch");
        AnomalyLists lists = AnomalyLists.of(Map.of("watchList", Map.of("list", "usage-watch", "ttlHours", 6)));
        Instant now = Instant.now();
        assertEquals(2, AnomalyScoreJobType.feedWatchList(FEED, root, data, model(), lists, "r1", now, RUN.scored()));
        Map<String, Object> fed = EntityFactsForTest.facts(root).get(1);
        assertEquals(List.of("+447700900001", "+447700900003"), fed.get("keys"), "high only — not elevated");
        assertEquals(now.plusSeconds(6 * 3600).toString(), fed.get("expiresAt"));
        assertEquals("job:anomaly.score:usage", fed.get("actor"));
        assertEquals(0, AnomalyScoreJobType.feedWatchList(FEED, root, data, model(), AnomalyLists.of(Map.of()), "r1", now,
                RUN.scored()), "no watchList is a no-op");
    }

    @Test
    void liveExclusionMembersAreExcluded(@TempDir Path root) throws Exception {
        EntityFactsForTest.create(root, "test-sims", "exclusion");
        EntityFactsForTest.append(root, "analyst-1", "list.member.added", "test-sims",
                Map.of("keys", List.of("+447700900001", "+447700900004")));
        EntityFactsForTest.append(root, "analyst-1", "list.member.added", "test-sims",
                Map.of("keys", List.of("+447700900002"), "expiresAt", Instant.now().minusSeconds(60).toString()));
        AnomalyLists lists = AnomalyLists.of(Map.of("exclusionList", "test-sims"));
        java.util.function.Predicate<String> excluded = AnomalyScoreJobType.exclusion(FEED, root, model(), lists);
        assertEquals(List.of("+447700900002", "+447700900003"), RUN.scored().stream().map(AnomalyScorer.Scored::entityKey)
                .filter(excluded.negate()).toList(), "an expired member is scored again");
        assertFalse(AnomalyScoreJobType.exclusion(FEED, root, model(), AnomalyLists.of(Map.of())).test("+447700900001"),
                "no exclusionList excludes nothing");
    }

    @Test
    void aListOfTheWrongPurposeOrNoEntityListsFailsClosed(@TempDir Path root, @TempDir Path data) throws Exception {
        EntityFactsForTest.create(root, "usage-watch", "watch");
        EntityFactsForTest.create(root, "test-sims", "exclusion");
        AnomalyLists swapped = AnomalyLists.of(Map.of("watchList", Map.of("list", "test-sims"), "exclusionList", "usage-watch"));
        IllegalArgumentException save = assertThrows(IllegalArgumentException.class,
                () -> AnomalyKindValidator.requireLists(FEED, root, swapped));
        assertTrue(save.getMessage().contains("only a 'watch' list"), save.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AnomalyKindValidator.requireLists(FEED, root,
                AnomalyLists.of(Map.of("exclusionList", "usage-watch"))), "a watch list is no exclusion list");
        assertThrows(IllegalArgumentException.class, () -> AnomalyKindValidator.requireLists(FEED, root,
                AnomalyLists.of(Map.of("exclusionList", "nope"))), "an unknown list");
        AnomalyKindValidator.requireLists(FEED, root,
                AnomalyLists.of(Map.of("watchList", Map.of("list", "usage-watch"), "exclusionList", "test-sims")));

        AnomalyLists ok = AnomalyLists.of(Map.of("watchList", Map.of("list", "usage-watch"), "exclusionList", "test-sims"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AnomalyKindValidator.requireLists(Optional.empty(), root, ok)).getMessage()
                .contains("needs Entity Lists, which this edition does not carry"));
        assertThrows(IllegalStateException.class, () -> AnomalyScoreJobType.exclusion(Optional.empty(), root, model(), ok));
        assertThrows(IllegalStateException.class, () -> AnomalyScoreJobType.feedWatchList(Optional.empty(), root, data,
                model(), ok, "r1", Instant.now(), RUN.scored()));
    }
}
