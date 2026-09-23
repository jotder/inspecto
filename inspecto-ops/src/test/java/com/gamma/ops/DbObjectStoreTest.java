package com.gamma.ops;

import com.gamma.objects.ObjectType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbObjectStore} over an in-memory DuckDB: INSERT/SELECT round-trips every column (incl. the
 * attribute JSON), a real mutating UPDATE persists, query filters, and the primary-key dedup. Proves
 * the durable Layer-2 backend with the bundled engine — zero new dependency.
 */
class DbObjectStoreTest {

    private DbObjectStore store;

    @BeforeEach
    void open() throws Exception {
        store = DbObjectStore.open("jdbc:duckdb:", null, null);   // in-memory database
    }

    @AfterEach
    void close() {
        store.close();
    }

    private static OperationalObject alert(String status, long created) {
        return OperationalObject.builder(ObjectType.ALERT)
                .title("t").description("d").status(status).severity("WARNING")
                .correlationId("pipe").owner("ops").attr("rule", "r1").attr("value", "0.1")
                .createdAt(created).updatedAt(created).build();
    }

    @Test
    void createGetRoundTripsAllColumns() {
        OperationalObject o = alert("OPEN", 1000);
        store.create(o);
        OperationalObject back = store.get(o.id()).orElseThrow();
        assertEquals(o.id(), back.id());
        assertEquals(ObjectType.ALERT, back.objectType());
        assertEquals("OPEN", back.status());
        assertEquals("WARNING", back.severity());
        assertEquals("ops", back.owner(), "the quoted reserved-word column round-trips");
        assertEquals("pipe", back.correlationId());
        assertEquals("r1", back.attributes().get("rule"));
        assertEquals("0.1", back.attributes().get("value"));
        assertEquals(1000, back.createdAt());
        assertTrue(store.get("missing").isEmpty());
    }

    @Test
    void updatePersistsMutation() {
        OperationalObject o = alert("OPEN", 1000);
        store.create(o);
        store.update(o.withStatus("RESOLVED", 2000, true));
        OperationalObject back = store.get(o.id()).orElseThrow();
        assertEquals("RESOLVED", back.status());
        assertEquals(2000, back.closedAt());
        assertEquals(2000, back.updatedAt());
    }

    @Test
    void queryFiltersAndOrders() {
        store.create(alert("OPEN", 1000));
        store.create(alert("RESOLVED", 2000));
        assertEquals(2, store.query(ObjectQuery.builder().objectType(ObjectType.ALERT).build()).size());
        assertEquals(1, store.query(ObjectQuery.builder().status("open").build()).size());
        assertEquals(2000, store.query(ObjectQuery.recent(10)).get(0).createdAt(), "newest-first");
        assertEquals(1, store.query(ObjectQuery.builder().correlationId("pipe").limit(1).build()).size());
    }

    @Test
    void deleteRemovesAndRequiresExisting() {
        OperationalObject o = alert("OPEN", 1000);
        store.create(o);
        store.delete(o.id());
        assertTrue(store.get(o.id()).isEmpty());
        assertThrows(NoSuchElementException.class, () -> store.delete(o.id()));
        assertThrows(NoSuchElementException.class, () -> store.delete("missing"));
    }

    /**
     * LA-CASE-CREATE-IN-PLACE-1: the Entity identity lookup. Exact on every wanted attribute, oldest first, and
     * the LIKE prefilter must neither match a near-miss (a key that merely CONTAINS the wanted one, a LIKE
     * wildcard in the value) nor miss a value carrying characters JSON escapes.
     */
    @Test
    void findByAttributesMatchesExactlyAndOldestFirst() {
        OperationalObject older = incident("entity:a_b", "orders", 1000);
        OperationalObject newer = incident("entity:a_b", "orders", 2000);
        store.create(newer);
        store.create(older);
        store.create(incident("entity:a_b", "orders2", 3000));   // the wanted Dataset is a prefix of this one
        store.create(incident("entity:aXb", "orders", 4000));    // `_` must not act as a LIKE wildcard
        store.create(incident("entity:a_b-longer", "orders", 5000));
        OperationalObject quoted = incident("entity:\"acme\" 50% \\ ltd", "or\"ders", 6000);
        store.create(quoted);

        List<OperationalObject> hits = store.findByAttributes(ObjectType.INCIDENT,
                Map.of("entityKey", "entity:a_b", "entityDataset", "orders"), 10);
        assertEquals(List.of(older.id(), newer.id()), hits.stream().map(OperationalObject::id).toList());
        assertEquals(1, store.findByAttributes(ObjectType.INCIDENT,
                Map.of("entityKey", "entity:a_b", "entityDataset", "orders"), 1).size(), "limit honoured");
        assertEquals(List.of(quoted.id()), store.findByAttributes(ObjectType.INCIDENT,
                Map.of("entityKey", "entity:\"acme\" 50% \\ ltd", "entityDataset", "or\"ders"), 10)
                .stream().map(OperationalObject::id).toList(), "JSON-escaped characters still match");
        assertTrue(store.findByAttributes(ObjectType.CASE,
                Map.of("entityKey", "entity:a_b", "entityDataset", "orders"), 10).isEmpty(), "type is part of it");
    }

    private static OperationalObject incident(String key, String dataset, long created) {
        return OperationalObject.builder(ObjectType.INCIDENT).title("t").status("IDENTIFIED")
                .attr("entityKey", key).attr("entityDataset", dataset)
                .createdAt(created).updatedAt(created).build();
    }

    @Test
    void duplicateIdRejected() {
        OperationalObject o = alert("OPEN", 1000);
        store.create(o);
        assertThrows(IllegalStateException.class, () -> store.create(o));
    }
}
