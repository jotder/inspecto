package com.gamma.la.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@code LA-COLUMN-ROLES-1} P1: an entity profile dates links by the mapping's {@code primaryTime}, or its old name {@code time}. */
class EntityTypeBindingsLinkTimeTest {

    @Test
    void thePrimaryTimeThenItsOldName() {
        assertEquals("booked_at", EntityTypeBindings.linkTime(Map.of("primaryTime", " booked_at ", "time", "old")));
        assertEquals("ts", EntityTypeBindings.linkTime(Map.of("time", "ts")));
        assertEquals("ts", EntityTypeBindings.linkTime(Map.of("primaryTime", " ", "time", "ts")));
        assertNull(EntityTypeBindings.linkTime(Map.of("from", "a")));
    }
}
