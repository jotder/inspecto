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
}
