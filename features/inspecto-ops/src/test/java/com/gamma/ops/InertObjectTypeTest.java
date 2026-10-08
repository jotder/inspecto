package com.gamma.ops;

import com.gamma.ops.link.DbLinkStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.workflow.ObjectType;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stored row whose {@code type} this build does not know (a legacy type such as {@code ALERT}, or the type of a
 * module that is not installed) must load INERT: listed, readable, never mutated, never rewritten, never dropped.
 *
 * <p>Characterised before the mechanism existed: a {@code DbObjectStore} holding one row of type {@code ZZ_UNKNOWN}
 * threw {@code IllegalArgumentException: No enum constant ...ObjectType.ZZ_UNKNOWN} from BOTH {@code get(id)} and
 * {@code query(...)} - one unknown row made the whole list unreadable. The {@code InMemoryObjectStore} could not hold
 * such a row at all (its record was enum-typed).
 */
class InertObjectTypeTest {

    private static final String UNKNOWN = "ZZ_UNKNOWN";

    private static OperationalObject inert(String id, long created) {
        return OperationalObject.inert(UNKNOWN, id, "legacy", "d", "OPEN", "INFO", null, null, null, "corr",
                Map.of("k", "v"), created, created, 0, 0);
    }

    private static OperationalObject typed(String id, long created) {
        return OperationalObject.builder(ObjectType.INCIDENT).id(id).status("IDENTIFIED").severity("INFO")
                .createdAt(created).updatedAt(created).build();
    }

    /** A DuckDB store plus the connection to seed a raw row the typed API could never write. */
    private static DbObjectStore dbWithRawRow(Connection c, String id, String type, long closedAt) throws Exception {
        DbObjectStore s = new DbObjectStore(c);
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO inspecto_ops_objects (id, object_type, title, description, status, severity, "
                    + "priority, \"owner\", assignee, correlation_id, attributes, created_at, updated_at, closed_at, version) "
                    + "VALUES ('" + id + "','" + type + "','legacy','d','OPEN','INFO',null,null,null,'corr','{\"k\":\"v\"}',"
                    + "5,5," + closedAt + ",3)");
        }
        return s;
    }

    @Test
    void dbStoreLoadsAnUnknownTypeInertAndListsItBesideTypedRows() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            DbObjectStore s = dbWithRawRow(c, "z1", UNKNOWN, 0);
            s.create(typed("i1", 10));

            OperationalObject z = s.get("z1").orElseThrow();
            assertTrue(z.isInert());
            assertNull(z.objectType());
            assertEquals(UNKNOWN, z.typeName());
            assertEquals("v", z.attributes().get("k"), "the row is carried verbatim");
            assertEquals(3, z.version());

            List<OperationalObject> all = s.query(ObjectQuery.recent(10));
            assertEquals(List.of("i1", "z1"), all.stream().map(OperationalObject::id).toList(), "listed, newest first");
            assertEquals(List.of("i1"), s.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build())
                    .stream().map(OperationalObject::id).toList(), "a typed filter never matches an inert row");
        }
    }

    @Test
    void inertViewCarriesTheDiagnosticAndTheRawType() {
        Map<String, Object> m = inert("z1", 5).toMap();
        assertEquals(UNKNOWN, m.get("objectType"));
        assertEquals(true, m.get("inert"));
        assertEquals("type ZZ_UNKNOWN is not installed/known: left untouched", m.get("diagnostic"));
        assertFalse(OperationalObject.builder(ObjectType.INCIDENT).status("X").build().toMap().containsKey("inert"));
    }

    @Test
    void dbStoreNeverRewritesAnInertRow() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            DbObjectStore s = dbWithRawRow(c, "z1", UNKNOWN, 0);
            OperationalObject z = s.get("z1").orElseThrow();
            assertThrows(InertObjectException.class, () -> s.update(z));
            assertThrows(InertObjectException.class, () -> z.withStatus("RESOLVED", 9, true));
            assertThrows(InertObjectException.class, () -> z.withAssignee("a", 9));
            assertThrows(InertObjectException.class, () -> z.withAttributes(Map.of("a", "b"), 9));
            OperationalObject after = s.get("z1").orElseThrow();
            assertEquals(z, after, "the stored row is byte-for-byte what was seeded");
        }
    }

    @Test
    void inMemoryStoreHoldsAndListsAnInertRowButRefusesToRewriteIt() {
        InMemoryObjectStore s = new InMemoryObjectStore();
        s.create(inert("z1", 5));
        s.create(typed("i1", 10));
        assertTrue(s.get("z1").orElseThrow().isInert());
        assertEquals(List.of("i1", "z1"), s.query(ObjectQuery.recent(10)).stream().map(OperationalObject::id).toList());
        assertThrows(InertObjectException.class, () -> s.update(s.get("z1").orElseThrow()));
        assertEquals(1, s.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build()).size());
    }

    @Test
    void everyMutatingServiceCallRefusesAnInertObjectAndLeavesItUntouched() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        ObjectService svc = new ObjectService(store);
        store.create(inert("z1", 5));
        OperationalObject incident = svc.open(ObjectType.INCIDENT, "real", "d", "INFO", "corr", Map.of());
        OperationalObject before = svc.get("z1").orElseThrow();

        assertThrows(InertObjectException.class, () -> svc.transition("z1", "ack", "a"));
        assertThrows(InertObjectException.class, () -> svc.transitionTo("z1", "RESOLVED", "a"));
        assertThrows(InertObjectException.class, () -> svc.assign("z1", "bob", "a"));
        assertThrows(InertObjectException.class, () -> svc.comment("z1", "a", "hi"));
        assertThrows(InertObjectException.class, () -> svc.link(incident.id(), "z1", "RELATED_TO", "a"));
        assertThrows(InertObjectException.class, () -> svc.link("z1", incident.id(), "RELATED_TO", "a"));
        assertThrows(InertObjectException.class, () -> svc.purge("z1", "a"));
        assertEquals(before, svc.get("z1").orElseThrow(), "not one field moved");
    }

    @Test
    void theSlaSweepAndAnalyticsSkipAnInertRow() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        ObjectService svc = new ObjectService(store);
        store.create(inert("z1", 5));
        OperationalObject before = svc.get("z1").orElseThrow();
        assertEquals(0, svc.sweepIncidentSla(System.currentTimeMillis()));
        assertEquals(0, ((Number) svc.analytics(ObjectType.INCIDENT).get("total")).intValue());
        assertEquals(before, svc.get("z1").orElseThrow(), "the sweep stamped nothing on it");
        assertTrue(svc.purgeEligible(ObjectType.INCIDENT, "OPEN", Long.MAX_VALUE, 10).isEmpty());
    }

    @Test
    void aLinkNamingAnUnknownTypeStillLoads() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            DbLinkStore links = new DbLinkStore(c);
            links.add(new ObjectLink("i1", "INCIDENT", "x9", UNKNOWN, "ESCALATED_FROM", 7));
            ObjectLink back = links.incident("i1").get(0);
            assertEquals(UNKNOWN, back.toType());
            assertEquals("INCIDENT", back.fromType());
            assertEquals(UNKNOWN, back.toMap().get("toType"));
        }
    }
}
