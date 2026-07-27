package com.gamma.ops.note;

import com.gamma.ops.AnnotationKinds;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Phase-4-follow-up note value type + both stores: the comment/attachment factories (attachment
 * metadata rides the attribute bag), newest-first per-object reads with an optional kind filter, and a
 * DuckDB round-trip incl. the attachment attributes — mirroring {@code LinkCoreTest}/{@code DbObjectStoreTest}.
 */
class NoteCoreTest {

    @Test
    void factoriesValidateAndCarryAttachmentMetadata() {
        ObjectNote c = ObjectNote.comment("CASE-1", "alice", "looks bad");
        assertEquals(NoteKind.COMMENT, c.kind());
        assertEquals("looks bad", c.body());
        assertTrue(c.id().startsWith("NOTE-"));
        assertTrue(c.attributes().isEmpty());

        ObjectNote a = ObjectNote.attachment("CASE-1", "bob", "log.txt", "text/plain", "s3://x/log.txt", "tail");
        assertEquals(NoteKind.ATTACHMENT, a.kind());
        assertEquals("tail", a.body(), "caption becomes the body");
        assertEquals("log.txt", a.attributes().get("name"));
        assertEquals("text/plain", a.attributes().get("contentType"));
        assertEquals("s3://x/log.txt", a.attributes().get("uri"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectNote("", "o", NoteKind.COMMENT, "a", "b", null, 1), "blank id rejected");
    }

    @Test
    void inMemoryForObjectFiltersByKindNewestFirst() {
        InMemoryNoteStore store = new InMemoryNoteStore();
        store.add(new ObjectNote("N1", "O", NoteKind.COMMENT, "a", "first", null, 100));
        store.add(new ObjectNote("N2", "O", NoteKind.ATTACHMENT, "a", "", Map.of("name", "f"), 200));
        store.add(new ObjectNote("N3", "O", NoteKind.COMMENT, "a", "third", null, 300));
        store.add(new ObjectNote("NX", "OTHER", NoteKind.COMMENT, "a", "x", null, 400));

        assertEquals(3, store.forObject("O", null).size());
        assertEquals("N3", store.forObject("O", null).get(0).id(), "newest-first");
        assertEquals(2, store.forObject("O", NoteKind.COMMENT).size());
        assertEquals(1, store.forObject("O", NoteKind.ATTACHMENT).size());
        assertTrue(store.forObject("none", null).isEmpty());
    }

    @Test
    void dbNoteStoreRoundTrips() throws Exception {
        try (DbNoteStore store = DbNoteStore.open("jdbc:duckdb:", null, null)) {   // in-memory database
            store.add(ObjectNote.comment("O", "alice", "hello"));
            store.add(ObjectNote.attachment("O", "bob", "log.txt", "text/plain", "s3://x", "cap"));

            assertEquals(2, store.forObject("O", null).size());
            List<ObjectNote> atts = store.forObject("O", NoteKind.ATTACHMENT);
            assertEquals(1, atts.size());
            assertEquals("log.txt", atts.get(0).attributes().get("name"), "attachment metadata round-trips");
            assertEquals("cap", atts.get(0).body());
            assertEquals(1, store.forObject("O", NoteKind.COMMENT).size());
        }
    }

    // ── D10: any (kind, id) target ────────────────────────────────────────────────

    @Test
    void targetKindDefaultsToObjectAndIsCarriedByTheGenericFactories() {
        ObjectNote legacy = ObjectNote.comment("CASE-1", "alice", "hi");
        assertEquals(AnnotationKinds.OBJECT, legacy.targetKind(), "the pre-D10 factory still targets an object");
        assertEquals("CASE-1", legacy.targetId());
        assertEquals(AnnotationKinds.OBJECT,
                new ObjectNote("N", "CASE-1", NoteKind.COMMENT, "a", "b", null, 1).targetKind(),
                "the pre-D10 constructor still targets an object");
        assertEquals(AnnotationKinds.OBJECT,
                new ObjectNote("N", "CASE-1", null, NoteKind.COMMENT, "a", "b", null, 1).targetKind(),
                "a blank targetKind normalises to 'object', never to null");

        ObjectNote onView = ObjectNote.comment("link-analysis-view", "ring-1", "alice", "odd cluster");
        assertEquals("link-analysis-view", onView.targetKind());
        assertEquals("ring-1", onView.targetId());
        assertEquals(NoteKind.COMMENT, onView.kind(), "note kind and target kind stay orthogonal");
        assertEquals("link-analysis-view", onView.toMap().get("targetKind"));
        assertEquals("ring-1", onView.toMap().get("objectId"), "the shipped objectId key is unchanged");

        ObjectNote att = ObjectNote.attachment("widget", "w1", "bob", "f.png", "image/png", "s3://x", "cap");
        assertEquals("widget", att.targetKind());
        assertEquals("f.png", att.attributes().get("name"));
    }

    @Test
    void targetVocabularyIsTheComponentTypeSetPlusObject() {
        assertTrue(AnnotationKinds.isKnown("object"));
        assertTrue(AnnotationKinds.isKnown("LINK-ANALYSIS-VIEW"), "case-insensitive");
        assertTrue(AnnotationKinds.KINDS.containsAll(com.gamma.pipeline.ComponentStore.WRITABLE_TYPES),
                "one vocabulary — widening the component registry widens note targets");
        assertFalse(AnnotationKinds.isKnown("banana"));
        assertFalse(AnnotationKinds.isKnown(null));
        assertThrows(IllegalArgumentException.class, () -> AnnotationKinds.require("banana"));
    }

    @Test
    void inMemoryReadsAreScopedToTheKindIdPair() {
        InMemoryNoteStore store = new InMemoryNoteStore();
        store.add(new ObjectNote("N1", "X", AnnotationKinds.OBJECT, NoteKind.COMMENT, "a", "on the object", null, 100));
        store.add(new ObjectNote("N2", "X", "link-analysis-view", NoteKind.COMMENT, "a", "on the view", null, 200));

        assertEquals(1, store.forObject("X", null).size(), "same id, different family — no bleed");
        assertEquals("N1", store.forObject("X", null).get(0).id());
        assertEquals("N2", store.forTarget("link-analysis-view", "X", null).get(0).id());
        assertTrue(store.forTarget("widget", "X", null).isEmpty());
    }

    @Test
    void noteServiceFailsClosedOnUnknownKindAndAbsentTarget() {
        InMemoryNoteStore store = new InMemoryNoteStore();
        // a resolver that knows exactly one view
        NoteService notes = new NoteService(store,
                (kind, id) -> "link-analysis-view".equals(kind) && "ring-1".equals(id) ? "" : null);

        ObjectNote n = notes.comment("link-analysis-view", "ring-1", "alice", "odd cluster");
        assertEquals("link-analysis-view", n.targetKind());
        assertEquals(1, notes.notesOf("link-analysis-view", "ring-1", NoteKind.COMMENT).size());

        assertThrows(IllegalArgumentException.class,
                () -> notes.comment("banana", "ring-1", "a", "b"), "unknown kind rejected");
        assertThrows(NoSuchElementException.class,
                () -> notes.comment("link-analysis-view", "nope", "a", "b"), "absent target ⇒ no orphan note");
        assertThrows(NoSuchElementException.class,
                () -> notes.notesOf("link-analysis-view", "nope", null));
        assertTrue(store.forTarget("link-analysis-view", "nope", null).isEmpty(), "nothing was written");
    }

    @Test
    void dbNoteStoreSeparatesTargetKindsAndMigratesLegacyRows() throws Exception {
        try (java.sql.Connection conn = com.gamma.util.JdbcDrivers.connect("jdbc:duckdb:", null, null)) {
            // a pre-D10 table + row, exactly as an installed 4.6 deployment has it
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE inspecto_ops_notes (id VARCHAR PRIMARY KEY, object_id VARCHAR, "
                        + "kind VARCHAR, author VARCHAR, body VARCHAR, attributes VARCHAR, created_at BIGINT)");
                st.execute("INSERT INTO inspecto_ops_notes VALUES ('OLD','CASE-1','COMMENT','alice','legacy','',50)");
            }

            DbNoteStore store = new DbNoteStore(conn);          // ← runs the migration
            List<ObjectNote> migrated = store.forObject("CASE-1", null);
            assertEquals(1, migrated.size(), "the legacy row backfilled to targetKind 'object'");
            assertEquals(AnnotationKinds.OBJECT, migrated.get(0).targetKind());

            store.add(ObjectNote.comment("link-analysis-view", "CASE-1", "bob", "on the view"));
            assertEquals(1, store.forObject("CASE-1", null).size(), "the view note is not an object note");
            List<ObjectNote> onView = store.forTarget("link-analysis-view", "CASE-1", null);
            assertEquals(1, onView.size());
            assertEquals("on the view", onView.get(0).body());
        }
    }
}
