package com.gamma.la.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The pure authority re-decision of the scheduled index build principal (D-ING2, the standing-detection model). */
class ScheduledIndexBuildTest {

    private static Map<String, Object> shared(String owner, Object... shares) {
        return Map.of("owner", owner, "shares", List.of(shares));
    }

    @Test
    void theOnlyCapabilityThePrincipalHoldsIsBuildingTheIndex() {
        assertEquals("canBuildLinkIndex", ScheduledIndexBuild.CAPABILITY);
        assertEquals("index-build:", ScheduledIndexBuild.PRINCIPAL_PREFIX);
    }

    @Test
    void aBlankOwnerOrAMissingDatasetIsRefused() {
        assertEquals(ScheduledIndexBuild.NO_OWNER, ScheduledIndexBuild.decide(null, Map.of(), null));
        assertEquals(ScheduledIndexBuild.NO_OWNER, ScheduledIndexBuild.decide("  ", Map.of(), null));
        assertEquals(ScheduledIndexBuild.DATASET_GONE, ScheduledIndexBuild.decide("u1", null, null));
    }

    @Test
    void anUnrestrictedDatasetOrOneTheOwnerOwnsOrIsSharedToByUserIdIsAllowed() {
        assertNull(ScheduledIndexBuild.decide("u1", Map.of(), null));
        assertNull(ScheduledIndexBuild.decide("u1", shared("u1"), null));
        assertNull(ScheduledIndexBuild.decide("u2", shared("u1",
                Map.of("subjectType", "user", "subjectId", "u2", "access", "view")), null));
    }

    @Test
    void aRoleShareIsRefusedBecauseItCannotBeReResolvedOffARequest() {
        assertEquals(ScheduledIndexBuild.ROLE_SHARE_ONLY, ScheduledIndexBuild.decide("u2", shared("u1",
                Map.of("subjectType", "role", "subjectId", "analysts", "access", "view")), null));
    }

    @Test
    void anOwnerWhoLostTheDatasetIsRefused() {
        assertEquals(ScheduledIndexBuild.DATASET_NOT_SHARED, ScheduledIndexBuild.decide("u2", shared("u1"), null));
    }
}
