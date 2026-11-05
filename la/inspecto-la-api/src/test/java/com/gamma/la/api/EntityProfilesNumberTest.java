package com.gamma.la.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@code LA-COLUMN-ROLES-1} P3: a Measure aggregate keeps its value however large (byte volumes, minor-unit money). */
class EntityProfilesNumberTest {

    @Test
    void largeTotalsKeepTheirValueAndSmallOnesSixDecimals() {
        assertEquals(350_000_000_000_001.0, EntityProfiles.number(350_000_000_000_001.0));
        assertEquals(9.3e18, EntityProfiles.number(9.3e18));
        assertEquals(1.234568, EntityProfiles.number(1.2345678));
        assertEquals(-2.5, EntityProfiles.number(-2.5));
        assertNull(EntityProfiles.number(null));
        assertNull(EntityProfiles.number(Double.NaN));
    }
}
