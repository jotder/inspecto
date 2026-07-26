package com.gamma.ops;

import com.gamma.ops.link.DbLinkStore;
import com.gamma.ops.link.InMemoryLinkStore;
import com.gamma.ops.link.LinkStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.DbNoteStore;
import com.gamma.ops.note.InMemoryNoteStore;
import com.gamma.ops.note.NoteStore;
import com.gamma.ops.note.ObjectNote;
import com.gamma.ops.tag.DbTagAssignmentStore;
import com.gamma.ops.tag.InMemoryTagAssignmentStore;
import com.gamma.ops.tag.TagAssignment;
import com.gamma.ops.tag.TagAssignmentStore;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MNT-14 retention-sweep seams: purge-<b>eligibility</b> selection (G1), legal hold (G5) and the bulk
 * delete-by-target primitives the cascade needs (G2). No sweep exists yet — these are the prerequisites,
 * and each one has a silent failure mode, which is why they are tested before anything consumes them.
 *
 * <p>Every store check runs against <b>both</b> implementations through the same assertions (the
 * {@code TagAssignmentCoreTest} idiom): a divergence between the lean in-memory default and the durable
 * DB backend would mean a sweep that purges correctly on one deployment and orphans rows on the other.
 */
class RetentionSweepSeamTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_700_000_000_000L;

    // ── G1 — purge-eligibility selection ─────────────────────────────────────────

    private static void eachObjectStore(Consumer<ObjectStore> check) {
        try (ObjectStore mem = new InMemoryObjectStore()) {
            check.accept(mem);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try (ObjectStore db = DbObjectStore.open("jdbc:duckdb:", null, null)) {
            check.accept(db);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An archived incident closed at {@code closedAt} (its retention window starts there). */
    private static OperationalObject archived(String title, long closedAt) {
        OperationalObject o = OperationalObject.builder(ObjectType.INCIDENT)
                .title(title).description("d").status("DIAGNOSING").severity("WARNING")
                .createdAt(closedAt).updatedAt(closedAt).build();
        return o.withStatus("ARCHIVED", closedAt, true);
    }

    @Test
    void aCappedSweepFindsTheExpiredIncidentsEvenWhenTheyAreTheOldestInTheCorpus() {
        // THE point of G1. The old query was newest-first with no cutoff, so a sweep reading one page got
        // the NEWEST archived incidents — which are precisely the ones still inside their retention
        // window — and concluded "0 prunable" against a corpus that had 50 expired records in it.
        // Both halves matter: the cutoff makes every returned row eligible, the ordering makes the
        // longest-expired come first.
        eachObjectStore(store -> {
            for (int i = 0; i < 500; i++) store.create(archived("recent-" + i, NOW - i));
            for (int i = 0; i < 50; i++) store.create(archived("expired-" + i, NOW - (400 * DAY) - i));

            long cutoff = NOW - (90 * DAY);          // 90-day retention window
            List<OperationalObject> page =
                    store.query(ObjectQuery.purgeEligible(ObjectType.INCIDENT, "ARCHIVED", cutoff, 100));

            assertEquals(50, page.size(), "every expired incident is found despite a 100-row page");
            assertTrue(page.stream().allMatch(o -> o.title().startsWith("expired-")),
                    "and nothing still inside its retention window is offered for purge");
            assertTrue(page.getFirst().closedAt() < page.getLast().closedAt(),
                    "oldest-first: the longest-expired record is purged first");
        });
    }

    @Test
    void aReopenedIncidentIsNeverPurgeEligibleHoweverOldItIs() {
        // closedAt == 0 means "not closed". Without the `closed_at > 0` half of the predicate an ancient
        // reopened incident sorts to the front of the cutoff and gets hard-deleted while still being
        // worked — the worst outcome this feature could produce.
        eachObjectStore(store -> {
            OperationalObject reopened = archived("reopened", NOW - (400 * DAY))
                    .withStatus("DIAGNOSING", NOW, false);
            store.create(reopened);
            assertEquals(0, reopened.closedAt(), "reopening clears the archive timestamp");

            long cutoff = NOW - (90 * DAY);
            assertTrue(store.query(ObjectQuery.purgeEligible(ObjectType.INCIDENT, "ARCHIVED", cutoff, 100))
                    .isEmpty(), "not ARCHIVED and not closed — ineligible on both counts");
            assertTrue(store.query(ObjectQuery.builder().closedBefore(cutoff).limit(100).build())
                    .isEmpty(), "and the raw cutoff excludes it too, on closedAt == 0 alone");
        });
    }

    @Test
    void theDefaultQueryIsUnchangedByTheNewFields() {
        // Regression guard: eight existing call sites pass no cutoff and expect newest-first.
        eachObjectStore(store -> {
            store.create(archived("older", NOW - (10 * DAY)));
            store.create(archived("newer", NOW - DAY));

            List<OperationalObject> all = store.query(ObjectQuery.recent(10));
            assertEquals(2, all.size(), "no cutoff means no age constraint");
            assertEquals("newer", all.getFirst().title(), "still newest-first by default");
        });
    }

    // ── G5 — legal hold ──────────────────────────────────────────────────────────

    @Test
    void anyUnrecognisedLegalHoldValueHolds() {
        // Fail-safe by design: the two mistakes are not symmetric. Wrongly keeping a record is a storage
        // cost; wrongly purging one is unrecoverable, so only an explicit falsey spelling clears a hold.
        assertFalse(ObjectService.hasLegalHold(archived("none", NOW)), "absent key ⇒ no hold");
        for (String falsey : List.of("false", "FALSE", "0", "no", "off", "  ", ""))
            assertFalse(ObjectService.hasLegalHold(held(falsey)), "explicitly cleared: '" + falsey + "'");
        for (String truthy : List.of("true", "yes", "1", "DPA-2026-14", "held pending review", "maybe"))
            assertTrue(ObjectService.hasLegalHold(held(truthy)), "holds: '" + truthy + "'");
    }

    private static OperationalObject held(String value) {
        return archived("held", NOW)
                .withAttributes(java.util.Map.of(ObjectService.ATTR_LEGAL_HOLD, value), NOW);
    }

    @Test
    void aHeldIncidentIsStillReturnedByTheStoreBecauseHoldIsNotASqlConcern() {
        // Documents the seam split deliberately: the hold lives in the attribute bag, so the store cannot
        // filter on it and the sweep MUST apply hasLegalHold itself — at purge time, not only at preview.
        eachObjectStore(store -> {
            store.create(held("DPA-2026-14").withStatus("ARCHIVED", NOW - (400 * DAY), true));
            List<OperationalObject> page = store.query(
                    ObjectQuery.purgeEligible(ObjectType.INCIDENT, "ARCHIVED", NOW - (90 * DAY), 100));
            assertEquals(1, page.size(), "age-eligible at the store layer …");
            assertTrue(ObjectService.hasLegalHold(page.getFirst()), "… and held, for the caller to exclude");
        });
    }

    // ── G2 — bulk delete-by-target ───────────────────────────────────────────────

    @Test
    void deletingATargetsNotesLeavesASiblingTargetIntact() {
        eachNoteStore(store -> {
            store.add(ObjectNote.comment("INC-1", "alice", "root cause"));
            store.add(ObjectNote.attachment("INC-1", "bob", "log.txt", "text/plain", "s3://x", "tail"));
            store.add(ObjectNote.comment("INC-2", "alice", "unrelated"));
            store.add(ObjectNote.comment("link-analysis-view", "INC-1", "bob", "same id, other family"));

            assertEquals(2, store.deleteForTarget("object", "INC-1"), "comment AND attachment go");
            assertTrue(store.forObject("INC-1", null).isEmpty());
            assertEquals(1, store.forObject("INC-2", null).size(), "sibling target untouched");
            assertEquals(1, store.forTarget("link-analysis-view", "INC-1", null).size(),
                    "another family sharing the id is untouched — the pair is the key, not the id");
            assertEquals(0, store.deleteForTarget("object", "INC-1"), "idempotent: already gone");
        });
    }

    @Test
    void removingLinksIncidentToAnObjectClearsBothDirections() {
        eachLinkStore(store -> {
            store.add(ObjectLink.of("CASE-1", ObjectType.CASE, "INC-1", ObjectType.INCIDENT, "contains"));
            store.add(ObjectLink.of("INC-1", ObjectType.INCIDENT, "INC-9", ObjectType.INCIDENT, "relates"));
            store.add(ObjectLink.of("CASE-1", ObjectType.CASE, "INC-2", ObjectType.INCIDENT, "contains"));

            // Both ends, or purging INC-1 would leave CASE-1 → INC-1 dangling at an unresolvable id.
            assertEquals(2, store.removeAllIncident("INC-1"));
            assertTrue(store.incident("INC-1").isEmpty());
            assertEquals(1, store.incident("CASE-1").size(), "CASE-1's other membership survives");
        });
    }

    @Test
    void removingATargetsTagEdgesIsTheAxisNeitherExistingMethodHad() {
        eachTagStore(store -> {
            store.add(TagAssignment.of("q3-audit", "object", "INC-1", "alice"));
            store.add(TagAssignment.of("fraud", "object", "INC-1", "alice"));
            store.add(TagAssignment.of("q3-audit", "object", "INC-2", "bob"));
            store.add(TagAssignment.of("q3-audit", "dataset", "INC-1", "bob"));

            assertEquals(2, store.removeAllForTarget("object", "INC-1"), "every tag on the target, one call");
            assertTrue(store.tagsOf("object", "INC-1").isEmpty());
            assertEquals(List.of("q3-audit"), store.tagsOf("object", "INC-2"), "sibling target keeps its tag");
            assertEquals(List.of("q3-audit"), store.tagsOf("dataset", "INC-1"),
                    "and the same id in another kind keeps its own edges");
            assertEquals(2, store.forTag("q3-audit").size(), "the tag itself still exists elsewhere");
        });
    }

    private static void eachNoteStore(Consumer<NoteStore> check) {
        check.accept(new InMemoryNoteStore());
        try (NoteStore db = DbNoteStore.open("jdbc:duckdb:", null, null)) {
            check.accept(db);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void eachLinkStore(Consumer<LinkStore> check) {
        check.accept(new InMemoryLinkStore());
        try (LinkStore db = DbLinkStore.open("jdbc:duckdb:", null, null)) {
            check.accept(db);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void eachTagStore(Consumer<TagAssignmentStore> check) {
        check.accept(new InMemoryTagAssignmentStore());
        try (TagAssignmentStore db = DbTagAssignmentStore.open("jdbc:duckdb:", null, null)) {
            check.accept(db);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
