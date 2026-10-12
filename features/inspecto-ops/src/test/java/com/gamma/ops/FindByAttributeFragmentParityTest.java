package com.gamma.ops;

import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ObjectStore#findByAttributeFragment}: the in-memory default and {@link DbObjectStore} scan the SAME capped
 * window — newest first by {@code createdAt}, then {@code id} descending — so the cap drops the same (oldest) rows
 * on both backends; types outside the set and non-matching rows are excluded; the match ignores case.
 */
class FindByAttributeFragmentParityTest {

    private static OperationalObject obj(String id, ObjectType t, long created, String value) {
        return OperationalObject.builder(t).id(id).title(id).description("d").status("OPEN")
                .attr("key.msisdn", value).createdAt(created).updatedAt(created).build();
    }

    private static List<String> ids(ObjectStore s, int cap) {
        s.create(obj("a", ObjectType.INCIDENT, 1000, "256700123456"));
        s.create(obj("b", ObjectType.INCIDENT, 3000, "+256700123456"));
        s.create(obj("c", ObjectType.CASE, 3000, "X256700123456"));    // ties with b on createdAt
        s.create(obj("d", ObjectType.INCIDENT, 2000, "256799999999"));  // no match
        s.create(obj("e", ObjectType.TASK, 4000, "256700123456"));     // type not asked for
        return s.findByAttributeFragment(List.of(ObjectType.INCIDENT, ObjectType.CASE), "x256700123456".substring(1), cap)
                .stream().map(OperationalObject::id).toList();
    }

    @Test
    void bothStoresScanTheSameNewestFirstWindow() throws Exception {
        try (DbObjectStore db = DbObjectStore.open("jdbc:duckdb:", null, null)) {
            assertEquals(List.of("c", "b", "a"), ids(new InMemoryObjectStore(), 10));
            assertEquals(List.of("c", "b", "a"), ids(db, 10));
        }
        try (DbObjectStore db = DbObjectStore.open("jdbc:duckdb:", null, null)) {
            assertEquals(List.of("c", "b"), ids(new InMemoryObjectStore(), 2), "the cap drops the oldest");
            assertEquals(List.of("c", "b"), ids(db, 2));
        }
    }

    private static OperationalObject withAttrs(String id, ObjectType t, long created, String k, String v) {
        return OperationalObject.builder(t).id(id).title(id).description("d").status("OPEN")
                .attr(k, v).createdAt(created).updatedAt(created).build();
    }

    private static void seed(ObjectStore s) {
        s.create(withAttrs("p1", ObjectType.INCIDENT, 100, "key.msisdn", "+256700123456"));
        s.create(withAttrs("p2", ObjectType.INCIDENT, 200, "key.caller_msisdn", "256700123456"));
        s.create(withAttrs("p3", ObjectType.INCIDENT, 300, "entityKey", "entity:msisdn:+256700123456"));
        s.create(withAttrs("p4", ObjectType.CASE, 400, "entityKey", "msisdn:256700123456"));
        s.create(withAttrs("p5", ObjectType.INCIDENT, 500, "key.msisdn", "256 700 123 456"));   // separators inside digits
        s.create(withAttrs("p6", ObjectType.INCIDENT, 600, "key.entity_key", "ABC-256700123456-X")); // case + embedded
        s.create(withAttrs("p7", ObjectType.INCIDENT, 700, "key.imsi", "999999999999"));
        s.create(withAttrs("p8", ObjectType.TASK, 800, "key.msisdn", "256700123456"));
        s.create(withAttrs("p9", ObjectType.INCIDENT, 900, "key.msisdn", "50%_off"));            // LIKE metacharacters
    }

    private static List<String> frag(ObjectStore s, String f, int cap) {
        return s.findByAttributeFragment(List.of(ObjectType.INCIDENT, ObjectType.CASE), f, cap)
                .stream().map(OperationalObject::id).toList();
    }

    @Test
    void indexedLookupEqualsTheScanOnEntityReferenceValues() throws Exception {
        try (DbObjectStore db = DbObjectStore.open("jdbc:duckdb:", null, null)) {
            ObjectStore mem = new InMemoryObjectStore();
            seed(mem);
            seed(db);
            for (String f : List.of("256700123456", "700123", "abc-256", "50%_", "%", "nomatch"))
                for (int cap : List.of(2, 100))
                    assertEquals(frag(mem, f, cap), frag(db, f, cap), "fragment '" + f + "' cap " + cap);
            assertEquals(List.of("p6", "p4", "p3", "p2", "p1"), frag(db, "256700123456", 100));
        }
    }

    @Test
    void sideTableFollowsUpdateDeleteAndBackfillsExistingRows() throws Exception {
        try (DbObjectStore db = DbObjectStore.open("jdbc:duckdb:", null, null)) {
            OperationalObject a = db.create(withAttrs("u1", ObjectType.INCIDENT, 1, "key.msisdn", "256711111111"));
            assertEquals(List.of("u1"), frag(db, "711111", 10));
            db.update(a.withAttributes(java.util.Map.of("key.msisdn", "256722222222"), 2));
            assertEquals(List.of(), frag(db, "711111", 10), "an update drops the old reference");
            assertEquals(List.of("u1"), frag(db, "722222", 10));
            db.delete("u1");
            assertEquals(List.of(), frag(db, "722222", 10));
        }
    }

    @Test
    void aFailingSideTableWriteRollsBackTheObjectWrite() throws Exception {
        try (DbObjectStore db = DbObjectStore.open("jdbc:duckdb:", null, null)) {
            db.browseSource().run(c -> { try (var st = c.createStatement()) { st.execute("DROP TABLE inspecto_ops_object_entity_ref"); } });
            assertThrows(IllegalStateException.class,
                    () -> db.create(withAttrs("r1", ObjectType.INCIDENT, 1, "key.msisdn", "256733333333")));
            assertTrue(db.get("r1").isEmpty(), "the object INSERT rolled back with the failed refs write");
        }
    }

    @Test
    void backfillRepairsAnObjectWhoseReferenceRowsAreMissingOrPartial() throws Exception {
        try (DbObjectStore db = DbObjectStore.open("jdbc:duckdb:", null, null)) {
            db.create(withAttrs("b1", ObjectType.INCIDENT, 1, "key.msisdn", "256744444444"));
            // A crash left an attribute row but no placeholder (and a stale value) for b1.
            db.browseSource().run(c -> { try (var st = c.createStatement()) {
                st.execute("DELETE FROM inspecto_ops_object_entity_ref");
                st.execute("INSERT INTO inspecto_ops_object_entity_ref VALUES ('b1','key.old','stale')");
            } });
            assertEquals(List.of(), frag(db, "744444", 10));
            db.browseSource().run(DbObjectStore::backfillRefs);
            assertEquals(List.of("b1"), frag(db, "744444", 10));
            assertEquals(List.of(), frag(db, "stale", 10), "the partial rows were rewritten, not kept");
        }
    }
}
