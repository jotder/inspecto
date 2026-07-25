package com.gamma.ops.tag;

import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectStore;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.InMemoryLinkStore;
import com.gamma.ops.note.InMemoryNoteStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7 phase 2: the assignment store is the truth for an object's tags and the legacy
 * {@code attributes.tags} CSV is a <b>projection</b> of it.
 *
 * <p>Phase 1 left the two stores independent, so a Tag Rule's tag was invisible to
 * {@code GET /tags/{name}/targets} and vice versa. Every test here asserts the two agree after a
 * different mutation path — that agreement is the whole point of phase 2, and each path is a separate
 * way it could regress.
 */
class ObjectTagProjectionTest {

    private record Fixture(ObjectService objects, TagAssignmentStore tags, ObjectStore store) {}

    private static Fixture fixture() {
        ObjectStore store = new InMemoryObjectStore();
        TagAssignmentStore tags = new InMemoryTagAssignmentStore();
        return new Fixture(
                new ObjectService(store, Map.of(), new InMemoryLinkStore(), new InMemoryNoteStore(), tags),
                tags, store);
    }

    /** The invariant: what the CSV says and what the store says are the same set. */
    private static void assertAgree(Fixture f, String objectId, String... expected) {
        List<String> fromStore = f.tags.tagsOf("object", objectId);
        assertEquals(List.of(expected), fromStore, "assignment store");
        String csv = f.store.get(objectId).orElseThrow().attributes().get(ObjectService.ATTR_TAGS);
        List<String> fromCsv = csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
        assertEquals(List.of(expected), fromCsv, "CSV projection disagrees with the store");
    }

    @Test
    void aTagAuthoredAtOpenTimeLandsInTheAssignmentStore() {
        Fixture f = fixture();
        OperationalObject o = f.objects.open(ObjectType.INCIDENT, "spike", "d", "HIGH", "CRITICAL",
                null, null, "corr", Map.of(ObjectService.ATTR_TAGS, "q3-audit,urgent"));

        // Phase 1 would have left this invisible to the cross-kind query.
        assertAgree(f, o.id(), "q3-audit", "urgent");
        assertEquals(1, f.tags.forTag("q3-audit").size());
    }

    @Test
    void applyTagUpdatesBothSides() {
        Fixture f = fixture();
        OperationalObject o = f.objects.open(ObjectType.INCIDENT, "spike", "d", "HIGH", "CRITICAL",
                null, null, "corr", Map.of());

        f.objects.applyTag(o.id(), "q3-audit", "alice");
        assertAgree(f, o.id(), "q3-audit");
    }

    @Test
    void removeTagClearsBothSidesIncludingTheLastTag() {
        Fixture f = fixture();
        OperationalObject o = f.objects.open(ObjectType.INCIDENT, "spike", "d", "HIGH", "CRITICAL",
                null, null, "corr", Map.of(ObjectService.ATTR_TAGS, "only"));

        f.objects.removeTag(o.id(), "only");
        // Removing the LAST tag is the case a lazy CSV-fallback adoption would have got wrong, by
        // treating "no assignments" as "not yet migrated" and resurrecting the tag on the next read.
        assertAgree(f, o.id());
    }

    @Test
    void aTagRuleTagIsVisibleToTheCrossKindQuery() {
        Fixture f = fixture();
        f.objects.registerTagRule(TagRule.fromMap(Map.of(
                "name", "criticals", "tag", "urgent", "filter", Map.of("severity", "CRITICAL"))));

        OperationalObject hit = f.objects.open(ObjectType.INCIDENT, "spike", "d", "CRITICAL", "HIGH",
                null, null, "corr", Map.of());

        // The headline phase-1 bug: rule-applied tags lived only in the CSV.
        assertAgree(f, hit.id(), "urgent");
        assertEquals(hit.id(), f.tags.forTag("urgent").getFirst().targetId());
    }

    @Test
    void bulkApplyingARuleToExistingObjectsUpdatesBothSides() {
        Fixture f = fixture();
        OperationalObject o = f.objects.open(ObjectType.INCIDENT, "spike", "d", "CRITICAL", "HIGH",
                null, null, "corr", Map.of());
        f.objects.registerTagRule(TagRule.fromMap(Map.of(
                "name", "criticals", "tag", "urgent", "filter", Map.of("severity", "CRITICAL"))));

        ObjectService.TagRuleApplication r = f.objects.applyTagRule("criticals");

        assertEquals(1, r.matched());
        assertEquals(1, r.updated());
        assertAgree(f, o.id(), "urgent");

        // Idempotent: a second pass matches but changes nothing.
        assertEquals(0, f.objects.applyTagRule("criticals").updated());
        assertAgree(f, o.id(), "urgent");
    }

    @Test
    void backfillAdoptsLegacyCsvTagsAndIsIdempotent() {
        // Simulate a Space upgraded from before D7: tags in the CSV, nothing in the assignment store.
        ObjectStore store = new InMemoryObjectStore();
        OperationalObject legacy = store.create(OperationalObject.builder(ObjectType.INCIDENT)
                .title("old").status("OPEN")
                .attributes(Map.of(ObjectService.ATTR_TAGS, "q3-audit,urgent"))
                .createdAt(1_000L).updatedAt(1_000L)
                .build());
        TagAssignmentStore tags = new InMemoryTagAssignmentStore();
        ObjectService objects = new ObjectService(store, Map.of(), new InMemoryLinkStore(),
                new InMemoryNoteStore(), tags);
        assertTrue(tags.tagsOf("object", legacy.id()).isEmpty(), "precondition: not yet migrated");

        assertEquals(2, objects.backfillTagAssignments());
        assertEquals(List.of("q3-audit", "urgent"), tags.tagsOf("object", legacy.id()));
        assertEquals("migration", tags.forTag("q3-audit").getFirst().actor());

        // Re-running on an already-migrated Space must not duplicate or re-stamp anything.
        assertEquals(0, objects.backfillTagAssignments());
        assertEquals(List.of("q3-audit", "urgent"), tags.tagsOf("object", legacy.id()));
    }

    @Test
    void renamingATagReachesTheObjectCsv() {
        // The architectural payoff: under the pre-D7 per-entity CSV shape a rename could not propagate.
        Fixture f = fixture();
        f.objects.registerTag(new Tag("q3-audit", 1_000L));
        OperationalObject o = f.objects.open(ObjectType.INCIDENT, "spike", "d", "HIGH", "CRITICAL",
                null, null, "corr", Map.of(ObjectService.ATTR_TAGS, "q3-audit"));

        ObjectService.TagVocabularyChange changed = f.objects.renameTag("q3-audit", "q3-review");

        assertEquals(1, changed.assignments());
        // The store rename alone would leave every CSV stale; re-projection is what closes the gap.
        assertEquals(1, changed.objects());
        assertAgree(f, o.id(), "q3-review");
        assertTrue(f.objects.tag("q3-audit").isEmpty(), "the old name must stop existing");
    }

    @Test
    void renamingOntoAnExistingTagMergesThem() {
        Fixture f = fixture();
        f.objects.registerTag(new Tag("q3-audit", 1_000L));
        f.objects.registerTag(new Tag("q3-review", 1_000L));
        OperationalObject both = f.objects.open(ObjectType.INCIDENT, "a", "d", "HIGH", "CRITICAL",
                null, null, "corr", Map.of(ObjectService.ATTR_TAGS, "q3-audit,q3-review"));
        OperationalObject old = f.objects.open(ObjectType.INCIDENT, "b", "d", "HIGH", "CRITICAL",
                null, null, "corr", Map.of(ObjectService.ATTR_TAGS, "q3-audit"));

        f.objects.renameTag("q3-audit", "q3-review");

        // Two edges collapsing into one is correct, not a conflict — the composite key makes it so.
        assertAgree(f, both.id(), "q3-review");
        assertAgree(f, old.id(), "q3-review");
        assertEquals(2, f.tags.forTag("q3-review").size());
    }

    @Test
    void renamingCarriesTheTagRulesThatApplyTheTag() {
        Fixture f = fixture();
        f.objects.registerTagRule(TagRule.fromMap(Map.of(
                "name", "criticals", "tag", "urgent", "filter", Map.of("severity", "CRITICAL"))));

        assertEquals(List.of("criticals"), f.objects.renameTag("urgent", "sev1").rules());

        // Had the rule kept the old name it would resurrect it on the next object.
        OperationalObject o = f.objects.open(ObjectType.INCIDENT, "spike", "d", "CRITICAL", "HIGH",
                null, null, "corr", Map.of());
        assertAgree(f, o.id(), "sev1");
    }

    @Test
    void deletingATagDropsItsAssignmentsAndReprojects() {
        Fixture f = fixture();
        f.objects.registerTag(new Tag("q3-audit", 1_000L));
        OperationalObject o = f.objects.open(ObjectType.INCIDENT, "spike", "d", "HIGH", "CRITICAL",
                null, null, "corr", Map.of(ObjectService.ATTR_TAGS, "q3-audit,keep"));

        ObjectService.TagVocabularyChange changed = f.objects.deleteTag("q3-audit");

        assertEquals(1, changed.assignments());
        assertAgree(f, o.id(), "keep");
        assertTrue(f.tags.forTag("q3-audit").isEmpty(), "no orphaned edges may survive the tag");
    }

    @Test
    void deletingATagStillAppliedByARuleIsRefused() {
        Fixture f = fixture();
        f.objects.registerTagRule(TagRule.fromMap(Map.of(
                "name", "criticals", "tag", "urgent", "filter", Map.of("severity", "CRITICAL"))));

        // Deleting it would be undone by the next matching object — the rule must be dealt with first.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> f.objects.deleteTag("urgent"));
        assertTrue(refused.getMessage().contains("criticals"), refused.getMessage());
        assertTrue(f.objects.tag("urgent").isPresent());
    }
}
