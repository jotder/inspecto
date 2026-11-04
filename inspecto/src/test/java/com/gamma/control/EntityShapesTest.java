package com.gamma.control;

import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.EntityTypes.EntityType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTITY-TYPE-CATALOG-1 P1: Add data proposes a column's Entity Type from the SPACE's types' value shapes, not from a list
 * of its own. Each case names a type and a shape the old hard-coded switch did not know.
 */
class EntityShapesTest {

    /** A Space's stated types: three new ids with shapes, plus the core imsi and imei. */
    private static final List<EntityType> TYPES = List.of(
            new EntityType("bank", "Bank account", "upper-trim", true, List.of("BANK"), "account", null, null, "iban"),
            new EntityType("mail", "Mail address", "default", true, List.of("MAIL"), "identifier", null, null, "email"),
            new EntityType("code", "Branch code", "digits", false, List.of("CODE"), "place", null, null, "digits(6)"),
            new EntityType("note", "Free text", "default", false, List.of("NOTE"), null, null, null, "free"),
            EntityTypes.coreDefault("imsi"), EntityTypes.coreDefault("imei"));

    private static List<String> values(IntFunction<String> f) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 60; i++) out.add(f.apply(i));
        return out;
    }

    private static Map<String, Object> propose(List<String> values) {
        return EntityShapes.columnProposal(new EntityShapes.Column("x", "VARCHAR", values), TYPES);
    }

    @Test
    void aStatedTypesShapeProposesItStrongShapesHigh() {
        Map<String, Object> iban = propose(values(i -> "GB82WEST12345698765432"));
        assertEquals("bank", iban.get("value"), iban.toString());
        assertEquals("high", iban.get("confidence"));
        assertEquals("BANK", iban.get("classification"));
        assertEquals("mail", propose(values(i -> "user" + i + "@example.org")).get("value"));
    }

    @Test
    void plainDigitsAreAWeakFitSoTheProposalIsLow() {
        Map<String, Object> p = propose(values(i -> String.format("%06d", 100000 + i)));
        assertEquals("code", p.get("value"), p.toString());
        assertEquals("low", p.get("confidence"), "digits alone could be another number");
    }

    @Test
    void aLuhnCheckedSerialWinsOverPlainDigitsOfTheSameLength() {
        // 15-digit values with a valid check digit fit imei (luhn(15)) and imsi (digits(14-15)) alike: the narrower shape wins
        Map<String, Object> p = propose(List.of("490154203237518", "350000000000006", "490154203237518"));
        assertEquals("imei", p.get("value"), p.toString());
        Map<String, Object> plain = propose(List.of("001010123456789", "001010123456780"));
        assertEquals("imsi", plain.get("value"), "without a check digit they are the plain-digits type: " + plain);
    }

    @Test
    void aFreeShapeIsNeverProposedFromValues() {
        Map<String, Object> p = propose(values(i -> "anything " + i));
        assertNull(p.get("value"), p.toString());
        assertTrue(String.valueOf(p.get("evidence")).contains("neither the name nor the values"), p.toString());
    }
}
