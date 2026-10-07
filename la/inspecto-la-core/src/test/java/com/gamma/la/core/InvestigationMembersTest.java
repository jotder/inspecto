package com.gamma.la.core;

import com.gamma.la.core.InvestigationMembers.Entry;
import com.gamma.la.core.InvestigationMembers.Op;
import com.gamma.la.core.InvestigationMembers.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationMembersTest {

    private static Entry e(long seq, String subject, Role role, Op op) {
        return new Entry(seq, "2026-10-03T00:00:00Z", "owner", subject, role, op);
    }

    @Test
    void noEntriesMeansTheOwnerIsTheSoleLead() {
        assertEquals(Map.of("o", Role.LEAD), InvestigationMembers.fold("o", List.of()));
    }

    @Test
    void theLastOperationOnASubjectWinsAndARevokeRemovesTheRole() {
        Map<String, Role> roles = InvestigationMembers.fold("o", List.of(
                e(1, "a", Role.ANALYST, Op.GRANT),
                e(2, "a", Role.REVIEWER, Op.GRANT),
                e(3, "r", Role.REVIEWER, Op.GRANT),
                e(4, "r", Role.REVIEWER, Op.REVOKE)));
        assertEquals(Role.REVIEWER, roles.get("a"));
        assertFalse(roles.containsKey("r"));
        assertEquals(Role.LEAD, roles.get("o"));
    }

    @Test
    void theOwnerIsOnlyTheFirstLeadAndCanBeRevokedOnceAnotherLeadExists() {
        Map<String, Role> roles = InvestigationMembers.fold("o", List.of(
                e(1, "l2", Role.LEAD, Op.GRANT), e(2, "o", Role.LEAD, Op.REVOKE)));
        assertEquals(Map.of("l2", Role.LEAD), roles);
    }

    @Test
    void theLastLeadCannotBeRevokedOrDemotedButACoLeadCanBe() {
        Map<String, Role> sole = InvestigationMembers.fold("o", List.of());
        assertTrue(InvestigationMembers.refusal(sole, Op.REVOKE, "o", Role.LEAD).isPresent());
        assertTrue(InvestigationMembers.refusal(sole, Op.GRANT, "o", Role.ANALYST).isPresent(), "self-demotion");
        assertTrue(InvestigationMembers.refusal(sole, Op.GRANT, "o", Role.LEAD).isEmpty(), "a no-change grant is fine");
        assertTrue(InvestigationMembers.refusal(sole, Op.GRANT, "x", Role.ANALYST).isEmpty());
        Map<String, Role> two = InvestigationMembers.fold("o", List.of(e(1, "l2", Role.LEAD, Op.GRANT)));
        assertTrue(InvestigationMembers.refusal(two, Op.REVOKE, "o", Role.LEAD).isEmpty());
        assertTrue(InvestigationMembers.refusal(two, Op.GRANT, "l2", Role.REVIEWER).isEmpty());
    }

    @Test
    void rolesMatchTheDesignTable() {
        for (Role r : Role.values()) assertTrue(r.canRead());
        assertTrue(Role.LEAD.canWriteMainLog() && Role.LEAD.canManageMembers());
        assertFalse(Role.ANALYST.canWriteMainLog() || Role.ANALYST.canManageMembers() || Role.ANALYST.canApprove());
        assertFalse(Role.REVIEWER.canWriteMainLog() || Role.REVIEWER.canManageMembers());
        assertTrue(Role.REVIEWER.canApprove() && Role.LEAD.canApprove());
    }

    @Test
    void parsingAndSubjectValidation() {
        assertEquals(Role.ANALYST, Role.parse("analyst").orElseThrow());
        assertTrue(Role.parse("ANALYST").isEmpty() && Role.parse(null).isEmpty() && Role.parse("owner").isEmpty());
        assertTrue(InvestigationMembers.validSubject("alice@example.com"));
        assertFalse(InvestigationMembers.validSubject(""));
        assertFalse(InvestigationMembers.validSubject(" a"));
        assertFalse(InvestigationMembers.validSubject("a\nb"));
        assertFalse(InvestigationMembers.validSubject("x".repeat(201)));
    }
}
