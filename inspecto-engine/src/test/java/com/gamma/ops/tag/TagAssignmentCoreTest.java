package com.gamma.ops.tag;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core behaviour of the cross-entity tag graph (BACKLOG D7), mirroring {@code NoteCoreTest}.
 *
 * <p>Both store implementations are driven through the <b>same</b> assertions via
 * {@link #eachStore}, because the in-memory store is what most deployments run and the DB store is what
 * the large ones run — a behavioural divergence between them would surface as "works on my machine".
 */
class TagAssignmentCoreTest {

    /** Run one check against both implementations, so neither can drift from the contract. */
    private static void eachStore(java.util.function.Consumer<TagAssignmentStore> check) {
        check.accept(new InMemoryTagAssignmentStore());
        try (TagAssignmentStore db = DbTagAssignmentStore.open("jdbc:duckdb:", null, null)) {
            check.accept(db);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void appliesATagAndReadsItBackFromBothDirections() {
        eachStore(store -> {
            store.add(TagAssignment.of("q3-audit", "dataset", "orders", "alice"));

            assertEquals(List.of("q3-audit"), store.tagsOf("dataset", "orders"));
            List<TagAssignment> members = store.forTag("q3-audit");
            assertEquals(1, members.size());
            assertEquals("orders", members.getFirst().targetId());
            assertEquals("alice", members.getFirst().actor());
        });
    }

    @Test
    void oneTagSpansDifferentKinds() {
        // The entire point of D7: "everything tagged X" crosses entity families. Under the old
        // per-entity CSV shape this query did not exist at all.
        eachStore(store -> {
            store.add(TagAssignment.of("q3-audit", "dataset", "orders", "alice"));
            store.add(TagAssignment.of("q3-audit", "expectation", "no-nulls", "alice"));
            store.add(TagAssignment.of("q3-audit", "object", "INC-1", "bob"));

            assertEquals(3, store.forTag("q3-audit").size());
            assertEquals(List.of("q3-audit"), store.tagsOf("expectation", "no-nulls"));
        });
    }

    @Test
    void applyingTheSameTagTwiceIsOneEdgeAndKeepsTheOriginalProvenance() {
        eachStore(store -> {
            store.add(new TagAssignment("q3-audit", "dataset", "orders", "alice", 1_000L));
            TagAssignment second = store.add(new TagAssignment("q3-audit", "dataset", "orders", "bob", 2_000L));

            assertEquals(1, store.forTag("q3-audit").size());
            // Re-tagging must not rewrite who applied it first, or the audit trail reads backwards.
            assertEquals("alice", second.actor());
            assertEquals(1_000L, second.createdAt());
        });
    }

    @Test
    void readsAreScopedToTheKindIdPairSoFamiliesSharingAnIdDoNotBleed() {
        eachStore(store -> {
            store.add(TagAssignment.of("shared", "dataset", "same-id", "alice"));
            store.add(TagAssignment.of("other", "widget", "same-id", "alice"));

            assertEquals(List.of("shared"), store.tagsOf("dataset", "same-id"));
            assertEquals(List.of("other"), store.tagsOf("widget", "same-id"));
        });
    }

    @Test
    void removeIsIdempotentAndReportsWhetherTheEdgeExisted() {
        eachStore(store -> {
            store.add(TagAssignment.of("q3-audit", "dataset", "orders", "alice"));

            assertTrue(store.remove("q3-audit", "dataset", "orders"));
            assertFalse(store.remove("q3-audit", "dataset", "orders"), "already absent is not an error");
            assertEquals(List.of(), store.tagsOf("dataset", "orders"));
        });
    }

    @Test
    void renamePropagatesAcrossEveryKindAtOnce() {
        // This is the test the D7 architecture exists to pass: under per-entity CSV copies a rename
        // could not propagate, so a renamed tag silently split into two.
        eachStore(store -> {
            store.add(TagAssignment.of("q3-audit", "dataset", "orders", "alice"));
            store.add(TagAssignment.of("q3-audit", "object", "INC-1", "bob"));

            assertEquals(2, store.rename("q3-audit", "q3-review"));

            assertEquals(List.of(), store.forTag("q3-audit"));
            assertEquals(2, store.forTag("q3-review").size());
            assertEquals(List.of("q3-review"), store.tagsOf("dataset", "orders"));
            assertEquals(List.of("q3-review"), store.tagsOf("object", "INC-1"));
        });
    }

    @Test
    void renameMergesRatherThanFailingWhenTheTargetAlreadyCarriesTheNewName() {
        // A bulk UPDATE would hit the primary key here and abort the whole rename. Merging is correct:
        // the triple IS the edge identity, so two edges collapsing into one is the right answer.
        eachStore(store -> {
            store.add(TagAssignment.of("old", "dataset", "orders", "alice"));
            store.add(TagAssignment.of("new", "dataset", "orders", "bob"));

            store.rename("old", "new");

            assertEquals(List.of("new"), store.tagsOf("dataset", "orders"));
            assertEquals(1, store.forTag("new").size());
        });
    }

    @Test
    void removeTagClearsEveryEdgeForThatLabel() {
        eachStore(store -> {
            store.add(TagAssignment.of("stale", "dataset", "orders", "alice"));
            store.add(TagAssignment.of("stale", "widget", "chart", "alice"));
            store.add(TagAssignment.of("keep", "widget", "chart", "alice"));

            assertEquals(2, store.removeTag("stale"));
            assertEquals(List.of(), store.forTag("stale"));
            assertEquals(List.of("keep"), store.tagsOf("widget", "chart"));
        });
    }

    @Test
    void tagsOfIsAlphabeticalAndDeduplicated() {
        eachStore(store -> {
            store.add(TagAssignment.of("zulu", "dataset", "orders", "alice"));
            store.add(TagAssignment.of("alpha", "dataset", "orders", "alice"));

            assertEquals(List.of("alpha", "zulu"), store.tagsOf("dataset", "orders"));
        });
    }

    @Test
    void rejectsAnUnknownTargetKindAtTheSeam() {
        // Fail closed, exactly as notes do — an assignment must never point at nothing.
        assertThrows(IllegalArgumentException.class,
                () -> TagAssignment.of("q3-audit", "not-a-kind", "x", "alice"));
    }

    @Test
    void rejectsABlankNameAndACommaInTheName() {
        assertThrows(IllegalArgumentException.class,
                () -> TagAssignment.of("  ", "dataset", "orders", "alice"));
        // The legacy CSV attribute path still ships, so a comma would be one label in this store and
        // two in that one.
        assertThrows(IllegalArgumentException.class,
                () -> TagAssignment.of("a,b", "dataset", "orders", "alice"));
    }

    @Test
    void normalisesTheTargetKindAndDefaultsTheActor() {
        TagAssignment a = TagAssignment.of("q3-audit", "DataSet", "orders", null);
        assertEquals("dataset", a.targetKind());
        assertEquals("system", a.actor());
        assertTrue(a.createdAt() > 0);
        assertTrue(a.targets("DATASET", "orders"));
    }

    @Test
    void allReturnsEveryEdgeNewestFirst() {
        eachStore(store -> {
            store.add(new TagAssignment("first", "dataset", "orders", "alice", 1_000L));
            store.add(new TagAssignment("second", "widget", "chart", "alice", 2_000L));

            List<TagAssignment> all = store.all();
            assertEquals(2, all.size());
            assertEquals("second", all.getFirst().tag());
        });
    }

    @Test
    void toMapExposesStableKeys() {
        assertEquals(List.of("tag", "targetKind", "targetId", "actor", "createdAt"),
                List.copyOf(TagAssignment.of("q3-audit", "dataset", "orders", "alice").toMap().keySet()));
    }
}
