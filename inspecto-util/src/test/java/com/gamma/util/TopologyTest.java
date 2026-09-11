package com.gamma.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link Topology} — {@code -Dinspecto.topology}, and its refusal to guess. */
class TopologyTest {

    /**
     * ⛔ Clears ONLY this property. A blanket {@code System.clearProperty} sweep in a test has already
     * un-pinned an unrelated surefire property for a whole fork in this repo.
     */
    @AfterEach
    void clearOnlyOurs() {
        System.clearProperty(Topology.PROPERTY);
    }

    @Test
    void defaultsToSingleWhenUnsetOrBlank() {
        System.clearProperty(Topology.PROPERTY);
        assertEquals(Topology.Mode.SINGLE, Topology.mode());
        assertFalse(Topology.partitioned());

        System.setProperty(Topology.PROPERTY, "   ");
        assertEquals(Topology.Mode.SINGLE, Topology.mode(), "blank is unset, not a typo");
    }

    @Test
    void readsBothLegalValuesIgnoringCaseAndPadding() {
        System.setProperty(Topology.PROPERTY, "partitioned");
        assertEquals(Topology.Mode.PARTITIONED, Topology.mode());
        assertTrue(Topology.partitioned());

        System.setProperty(Topology.PROPERTY, "  PARTITIONED  ");
        assertTrue(Topology.partitioned(), "a stray space or capital must not silently mean 'single'");

        System.setProperty(Topology.PROPERTY, "Single");
        assertEquals(Topology.Mode.SINGLE, Topology.mode());
    }

    /**
     * 🔴 The whole point of the flag. Defaulting an unreadable value to {@code single} would turn one typo
     * into the silent degradation {@code partitioned} exists to prevent — the worst available reading.
     */
    @Test
    void anUnrecognisedValueRefusesRatherThanDefaulting() {
        System.setProperty(Topology.PROPERTY, "partitoned");   // sic — one transposed letter
        IllegalStateException e = assertThrows(IllegalStateException.class, Topology::mode);
        assertTrue(e.getMessage().contains("partitoned"), "the message must quote what was actually set: " + e);

        System.setProperty(Topology.PROPERTY, "cluster");      // D2 refused the cluster engine; not a value
        assertThrows(IllegalStateException.class, Topology::mode);
    }
}
