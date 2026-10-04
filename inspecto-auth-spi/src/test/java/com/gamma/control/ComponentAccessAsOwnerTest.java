package com.gamma.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.gamma.control.ComponentAccess.AsOwner.ALLOWED;
import static com.gamma.control.ComponentAccess.AsOwner.DENIED;
import static com.gamma.control.ComponentAccess.AsOwner.ROLE_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LD-1: the Subject-less R3 decider ({@code canViewAs}) and the caller-less PDP check ({@code RowScope.visibleAs}). */
class ComponentAccessAsOwnerTest {

    private static Map<String, Object> restricted(String owner, Object... shares) {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        if (owner != null) c.put("owner", owner);
        c.put("shares", List.of(shares));
        return c;
    }

    private static Map<String, Object> share(String type, String id, String access) {
        return Map.of("subjectType", type, "subjectId", id, "access", access);
    }

    @AfterEach
    void restore() {
        AccessDeciders.forTest(null);
    }

    @Test
    void anUnrestrictedComponentIsAllowedAndNoContentIsNeverAllowed() {
        assertEquals(ALLOWED, ComponentAccess.canViewAs("alice", Set.of(), Map.of("owner", "bob")));
        assertEquals(DENIED, ComponentAccess.canViewAs("alice", Set.of(), null));
    }

    @Test
    void theOwnerIdAndAUserShareGrantViewAndNothingElseDoes() {
        Map<String, Object> c = restricted("bob", share("user", "carol", "view"));
        assertEquals(ALLOWED, ComponentAccess.canViewAs("bob", Set.of(), c), "the owner id");
        assertEquals(ALLOWED, ComponentAccess.canViewAs("carol", Set.of(), c), "a user share");
        assertEquals(DENIED, ComponentAccess.canViewAs("alice", Set.of(), c), "a stranger");
        assertEquals(DENIED, ComponentAccess.canViewAs("", Set.of(), c), "a blank id matches nothing");
        assertEquals(DENIED, ComponentAccess.canViewAs(null, Set.of(), restricted(null, share("user", "", "view"))),
                "a blank id never matches a blank share");
    }

    @Test
    void aRoleShareIsUndecidableNotDenied() {
        Map<String, Object> c = restricted("bob", share("role", "analyst", "view"));
        assertEquals(ROLE_ONLY, ComponentAccess.canViewAs("alice", Set.of(), c),
                "alice may hold the role, but a role is never stored — the caller must refuse");
        assertEquals(ALLOWED, ComponentAccess.canViewAs("bob", Set.of(), c), "the owner id still decides it");
        assertEquals(ALLOWED, ComponentAccess.canViewAs("alice", Set.of(),
                restricted("bob", share("role", "analyst", "view"), share("user", "alice", "view"))),
                "a user share beside a role share is decisive");
    }

    @Test
    void canConfigureAccessGrantsOnlyWhenTheCallerPassesIt() {
        Map<String, Object> c = restricted("bob");
        assertEquals(DENIED, ComponentAccess.canViewAs("alice", Set.of(), c));
        assertEquals(ALLOWED, ComponentAccess.canViewAs("alice", Set.of(Roles.CAN_CONFIGURE_ACCESS), c));
    }

    @Test
    void malformedSharesGrantNothing() {
        assertEquals(DENIED, ComponentAccess.canViewAs("alice", Set.of(), Map.of("owner", "bob", "shares", "alice")));
        assertEquals(DENIED, ComponentAccess.canViewAs("alice", Set.of(), restricted("bob", "alice", 7)));
        assertEquals(DENIED, ComponentAccess.canViewAs("alice", Set.of(),
                restricted("bob", Map.of("subjectType", "group", "subjectId", "alice"))));
    }

    // ── RowScope.visibleAs ─────────────────────────────────────────────────────────────────────────────

    @Test
    void withNoDeciderNothingIsDenied() {
        assertTrue(RowScope.visibleAs(null, new Subject("alice", Set.of()), "investigation", Map.of()));
    }

    @Test
    void aDenyHidesItAndAllowOrAbstainGrantNothingButDoNotHideIt() {
        AtomicReference<AccessDecider.Decision> verdict = new AtomicReference<>();
        AtomicReference<Subject> seen = new AtomicReference<>();
        AtomicReference<String> seenKind = new AtomicReference<>();
        AccessDeciders.forTest((ex, subject, action, route, kind, resource) -> {
            seen.set(subject);
            seenKind.set(kind);
            assertEquals("read", action);
            return verdict.get();
        });
        Subject s = new Subject("alice", Set.of("canManageIncidents"));
        verdict.set(AccessDecider.Decision.DENY);
        assertFalse(RowScope.visibleAs(null, s, "investigation", Map.of("id", "case-a")));
        verdict.set(AccessDecider.Decision.ALLOW);
        assertTrue(RowScope.visibleAs(null, s, "investigation", Map.of()));
        verdict.set(AccessDecider.Decision.ABSTAIN);
        assertTrue(RowScope.visibleAs(null, s, "investigation", Map.of()));
        assertEquals("alice", seen.get().id(), "the decider judges the synthetic Subject it was handed");
        assertEquals("investigation", seenKind.get());
    }

    @Test
    void aDeciderThatThrowsFailsClosed() {
        AccessDeciders.forTest((ex, subject, action, route, kind, resource) -> {
            throw new IllegalStateException("boom");
        });
        assertFalse(RowScope.visibleAs(null, new Subject("alice", Set.of()), "investigation", Map.of()),
                "a sweep has no request to refuse, so an error is a refusal");
    }
}
