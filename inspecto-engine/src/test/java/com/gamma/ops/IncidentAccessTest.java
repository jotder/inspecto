package com.gamma.ops;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code incidents} Platform Service (S1-4): opening under the active-object convention — one
 * active INCIDENT per scope + dedupe-attribute value, never a clone for an operator mid-triage.
 */
class IncidentAccessTest {

    @Test
    void opensAnIncidentAndSuppressesAnActiveDuplicate() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        IncidentAccess incidents = IncidentAccess.over(() -> objects);

        Optional<OperationalObject> first = incidents.openIncident("recon breach on orders", "3 rows off",
                "critical", "orders", Map.of("rule", "recon-daily"), "rule");
        assertTrue(first.isPresent());
        assertEquals("IDENTIFIED", first.get().status());
        assertEquals("orders", first.get().correlationId());

        assertTrue(incidents.openIncident("recon breach on orders", "again", "critical", "orders",
                Map.of("rule", "recon-daily"), "rule").isEmpty(), "an active duplicate is suppressed");
        assertEquals(1, objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build()).size());
    }

    @Test
    void aDifferentScopeOrDedupeValueOpensItsOwnIncident() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        IncidentAccess incidents = IncidentAccess.over(() -> objects);
        incidents.openIncident("t", "m", "error", "orders", Map.of("rule", "r1"), "rule");

        assertTrue(incidents.openIncident("t", "m", "error", "billing",
                Map.of("rule", "r1"), "rule").isPresent(), "another scope is not suppressed");
        assertTrue(incidents.openIncident("t", "m", "error", "orders",
                Map.of("rule", "r2"), "rule").isPresent(), "another rule on the same scope is not suppressed");
        assertEquals(3, objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build()).size());
    }

    @Test
    void anArchivedIncidentNoLongerSuppresses() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        IncidentAccess incidents = IncidentAccess.over(() -> objects);
        OperationalObject first = incidents.openIncident("t", "m", "error", "orders",
                Map.of("rule", "r1"), "rule").orElseThrow();
        objects.transition(first.id(), "accept", "tester");   // DIAGNOSING is still non-terminal
        assertTrue(incidents.openIncident("t", "m", "error", "orders",
                Map.of("rule", "r1"), "rule").isEmpty(), "an incident mid-triage still suppresses");

        objects.transition(first.id(), "archive", "tester");   // ARCHIVED is the terminal state
        assertTrue(incidents.openIncident("t", "m", "error", "orders",
                Map.of("rule", "r1"), "rule").isPresent(), "a terminal incident is out of the convention");
    }
}
