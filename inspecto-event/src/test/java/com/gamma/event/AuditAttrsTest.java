package com.gamma.event;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link AuditAttrs#ALL} against the class's own constants by reflection: every public static
 * final String key must appear in ALL exactly once, so a newly added key cannot silently miss the
 * audit-shaped CSV projection (AUDIT-CSV-1 / compliance G10) — the derive-don't-hand-list guard.
 */
class AuditAttrsTest {

    @Test
    void allListsEveryKeyConstantExactlyOnce() throws Exception {
        Set<String> declared = new HashSet<>();
        for (Field f : AuditAttrs.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (f.getType() == String.class && Modifier.isPublic(m) && Modifier.isStatic(m) && Modifier.isFinal(m)) {
                declared.add((String) f.get(null));
            }
        }
        assertFalse(declared.isEmpty(), "reflection found the key constants");
        assertEquals(declared, new HashSet<>(AuditAttrs.ALL),
                "ALL must carry exactly the declared audit attribute keys");
        assertEquals(AuditAttrs.ALL.size(), new HashSet<>(AuditAttrs.ALL).size(), "no duplicates in ALL");
    }
}
