package com.gamma.access;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENT-KIT-1 AK-2f (plan §6.6, operator 2026-11-25): the Link Analysis Draft writes moved from {@code canManageIncidents}
 * onto {@code canDraftInvestigations}, which {@code canManageIncidents} implies — so every person who drafted still
 * drafts, a role granting only the narrower right drafts without managing Incidents, and the implication runs one way.
 */
class RolesImpliedCapabilityTest {

    @Test
    void manageIncidentsImpliesDraftingAndOnlyThatWay() {
        assertTrue(Roles.holds(Set.of(Roles.CAN_MANAGE_INCIDENTS), Roles.CAN_DRAFT_INVESTIGATIONS),
                "no person who drafted before loses the right");
        assertTrue(Roles.holds(Set.of(Roles.CAN_DRAFT_INVESTIGATIONS), Roles.CAN_DRAFT_INVESTIGATIONS));
        assertFalse(Roles.holds(Set.of(Roles.CAN_DRAFT_INVESTIGATIONS), Roles.CAN_MANAGE_INCIDENTS),
                "drafting never implies managing Incidents (promote, a new Case, a direct Investigation edit)");
        assertFalse(Roles.holds(Set.of(Roles.CAN_WORK_INCIDENTS), Roles.CAN_DRAFT_INVESTIGATIONS));
        assertFalse(Roles.holds(Set.of(), Roles.CAN_DRAFT_INVESTIGATIONS));
        assertTrue(Roles.holds(Set.of(Roles.CAN_OPERATE_RUNS), Roles.CAN_OPERATE_RUNS), "a plain grant still holds");
    }

    @Test
    void theNewRightIsInTheVocabularyAndAMachineAgentMayKeepIt() {
        assertTrue(Roles.KNOWN_CAPABILITIES.contains(Roles.CAN_DRAFT_INVESTIGATIONS), "an authored role may grant it");
        assertTrue(Roles.AGENT_MACHINE_CAPABILITIES.contains(Roles.CAN_DRAFT_INVESTIGATIONS));
        assertFalse(Roles.AGENT_MACHINE_CAPABILITIES.contains(Roles.CAN_MANAGE_INCIDENTS));
    }
}
