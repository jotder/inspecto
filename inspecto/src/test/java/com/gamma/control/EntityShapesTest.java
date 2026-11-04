package com.gamma.control;

import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.EntityTypes.EntityType;
import com.gamma.entitystore.IndustryPack;
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

    // ── ENTITY-TYPE-CATALOG-1 P2: the record type of a draft ──────────────────────────────────────────────────

    private static final List<IndustryPack.RecordType> RECORDS = List.of(
            new IndustryPack.RecordType("call", "Call", "", List.of("msisdn", "imei", "cell")),
            new IndustryPack.RecordType("sms", "Text message", "", List.of("msisdn", "cell")),
            new IndustryPack.RecordType("payment", "Payment", "", List.of("bank")));

    private static Map<String, Object> held(String... types) {
        List<Map<String, Object>> cols = new ArrayList<>();
        for (String t : types) cols.add(Map.of("column", t, "entityType", OnboardDraft.choice(t, "", true)));
        cols.add(Map.of("column", "detail", "entityType", OnboardDraft.choice(null, "", true)));
        return Map.of("columns", cols);
    }

    @Test
    void theRecordTypeWhoseLikelyTypesTheColumnsHoldIsProposed() {
        Map<String, Object> call = EntityShapes.recordType(held("msisdn", "imei"), RECORDS);
        assertEquals("call", call.get("value"));
        assertEquals("high", call.get("confidence"), call.toString());
        assertTrue(String.valueOf(call.get("evidence")).contains("msisdn, imei"), call.toString());

        // the same two held types: an SMS record's two likely types are all covered, a call's three are not
        Map<String, Object> sms = EntityShapes.recordType(held("msisdn", "cell"), RECORDS);
        assertEquals("sms", sms.get("value"), sms.toString());
        assertEquals("high", sms.get("confidence"));
        List<IndustryPack.RecordType> twins = List.of(new IndustryPack.RecordType("a", "A", "", List.of("msisdn", "cell")),
                new IndustryPack.RecordType("b", "B", "", List.of("cell", "msisdn")));
        Map<String, Object> tie = EntityShapes.recordType(held("msisdn", "cell"), twins);
        assertEquals("a", tie.get("value"), "a full tie goes to the first in force");
        assertEquals("low", tie.get("confidence"));
        assertEquals("low", EntityShapes.recordType(held("bank"), RECORDS).get("confidence"), "one held type is a guess");

        Map<String, Object> nothing = EntityShapes.recordType(held("note"), RECORDS);
        assertNull(nothing.get("value"), nothing.toString());
    }

    /** P3 content: a plain call file (two numbers and a handset serial) against the shipped telecom record types. */
    @Test
    void aPlainCallFileIsACallRecordAtLowConfidenceAndALowGuessIsNoEvidence() {
        List<IndustryPack.RecordType> telecom = com.gamma.entitystore.IndustryPacks.bundled().find("telecom").recordTypes();
        Map<String, Object> call = EntityShapes.recordType(held("msisdn", "imei"), telecom);
        assertEquals("voice_call", call.get("value"), "a handset change covers the same share, so the first in force: " + call);
        assertEquals("low", call.get("confidence"));

        // a number column that merely fits a digits shape (a low guess) does not steer the record type
        List<Map<String, Object>> cols = new ArrayList<>(List.of(
                Map.of("column", "caller", "entityType", OnboardDraft.choice("msisdn", "", true)),
                Map.of("column", "duration_ms", "entityType", OnboardDraft.choice("bank", "", false))));
        Map<String, Object> p = EntityShapes.recordType(Map.of("columns", cols), RECORDS);
        assertEquals("sms", p.get("value"), "counted, the low 'bank' guess would make it a payment (1 of 1): " + p);
    }

    @Test
    void aNameThatIsATypesIdWinsOverItsLastToken() {
        assertEquals("IP_ADDRESS", EntityShapes.nameHint("ip_address", EntityTypes.DEFAULTS));
        assertEquals("IP_ADDRESS", EntityShapes.nameHint("IpAddress", EntityTypes.DEFAULTS));
        assertEquals("ADDRESS", EntityShapes.nameHint("home_address", EntityTypes.DEFAULTS));
    }

    @Test
    void aFreeShapeIsNeverProposedFromValues() {
        Map<String, Object> p = propose(values(i -> "anything " + i));
        assertNull(p.get("value"), p.toString());
        assertTrue(String.valueOf(p.get("evidence")).contains("neither the name nor the values"), p.toString());
    }
}
