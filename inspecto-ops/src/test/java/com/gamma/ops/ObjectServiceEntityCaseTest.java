package com.gamma.ops;

import com.gamma.objects.ObjectType;
import com.gamma.ops.link.LinkRelationship;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ObjectService#openCaseFromEntities} at the service level (LA-CASE-CREATE-IN-PLACE-1): the Entity
 * identity reuses an object only the caller can see, and a WRITE that fails part-way rolls back every object
 * the call created — the half the real-HTTP test cannot reach, because nothing over HTTP makes a store fail.
 */
class ObjectServiceEntityCaseTest {

    private static final ObjectService.EntityMember ACME = new ObjectService.EntityMember("entity:acme ltd", "orders", "ACME Ltd");
    private static final ObjectService.EntityMember BOB = new ObjectService.EntityMember("entity:bob", "orders", "Bob");

    /** An in-memory store that refuses to create a CASE — the last write before the links. */
    private static final class CaseRefusingStore implements ObjectStore {
        final InMemoryObjectStore inner = new InMemoryObjectStore();

        @Override public OperationalObject create(OperationalObject obj) {
            if (obj.objectType() == ObjectType.CASE) throw new IllegalStateException("disk full");
            return inner.create(obj);
        }
        @Override public Optional<OperationalObject> get(String id) { return inner.get(id); }
        @Override public List<OperationalObject> query(ObjectQuery q) { return inner.query(q); }
        @Override public List<OperationalObject> findByAttributes(ObjectType t, Map<String, String> a, int limit) {
            return inner.findByAttributes(t, a, limit);
        }
        @Override public OperationalObject update(OperationalObject obj) { return inner.update(obj); }
        @Override public void delete(String id) { inner.delete(id); }
    }

    @Test
    void aFailedWriteRollsBackEveryObjectThisCallCreatedButNeverAReusedOne() {
        CaseRefusingStore store = new CaseRefusingStore();
        ObjectService svc = new ObjectService(store);
        // BOB already exists as an Entity Incident (minted earlier) — it must survive the rollback untouched.
        OperationalObject bob = svc.open(ObjectType.INCIDENT, "Bob", "d", null, null, null, null, null,
                Map.of(ObjectService.ATTR_ENTITY_KEY, BOB.entityKey(), ObjectService.ATTR_ENTITY_DATASET, BOB.dataset()));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> svc.openCaseFromEntities(
                "ring", null, List.of(ACME, BOB), List.of(), o -> true, "mia"));
        assertEquals("disk full", failure.getMessage(), "the ORIGINAL failure propagates");

        assertEquals(List.of(bob.id()), store.inner.query(ObjectQuery.recent(10)).stream()
                .map(OperationalObject::id).toList(), "the minted ACME Incident was discarded; the reused Bob kept");
        assertTrue(svc.linksOf(bob.id()).isEmpty());
    }

    @Test
    void reuseIgnoresAnObjectTheCallerCannotSee() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject hidden = svc.open(ObjectType.INCIDENT, "ACME", "d", null, null, null, null, null,
                Map.of(ObjectService.ATTR_ENTITY_KEY, ACME.entityKey(), ObjectService.ATTR_ENTITY_DATASET, ACME.dataset(),
                        "caseType", "billing"));

        ObjectService.EntityCase made = svc.openCaseFromEntities("ring", null, List.of(ACME), List.of(),
                o -> !"billing".equals(o.attributes().get("caseType")), "mia");

        assertEquals(1, made.members().size());
        assertNotEquals(hidden.id(), made.members().get(0).id(), "existence-hiding: an invisible match is not reused");
        assertEquals(List.of(made.members().get(0).id()), svc.linksOf(made.caseObject().id()).stream()
                .filter(l -> LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship())).map(l -> l.toId()).toList());
    }
}
