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

    // ── ENTITY-TYPE-RECORD-ROLES-1: the record type's usual column roles ───────────────────────────────────────

    private static final IndustryPack.RecordType CALL_WITH_ROLES = new IndustryPack.RecordType("call", "Voice call record", "",
            List.of("msisdn"), new IndustryPack.RoleHints(List.of("start"), List.of("duration", "charge"), List.of("cause", "service", "call"),
            List.of("calling"), List.of("called")));

    private static EntityShapes.Column col(String name, String type, String... values) {
        return new EntityShapes.Column(name, type, List.of(values));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> roleOf(Map<String, Object> entityTypes, String column) {
        for (Object o : (List<Object>) entityTypes.get("columns"))
            if (o instanceof Map<?, ?> row && column.equals(row.get("column"))) return (Map<String, Object>) row.get("role");
        throw new AssertionError("no column " + column);
    }

    @Test
    void theProposedRecordTypeGivesItsUsualRolesToTheColumnsItsHintsName() {
        List<EntityShapes.Column> cols = List.of(
                col("calling", "VARCHAR", "+447700900001", "+447700900002", "+447700900003"),
                col("called", "VARCHAR", "+447700900004", "+447700900005", "+447700900006"),
                col("start_at", "TIMESTAMP", "2026-01-01 10:00:00", "2026-01-01 11:00:00", "2026-01-01 12:00:00"),
                col("ended_at", "TIMESTAMP", "2026-01-01 10:01:00", "2026-01-01 11:01:00", "2026-01-01 12:01:00"),
                col("call_seconds", "BIGINT", "60", "61", "62"),
                col("charge_band", "VARCHAR", "a1", "b2", "c3"),
                col("release_cause", "VARCHAR", "x1", "x2", "x3"),
                col("served_cell", "VARCHAR", "c1", "c2", "c3"));
        List<EntityType> types = List.of(EntityTypes.coreDefault("msisdn"));
        Map<String, Object> et = new java.util.LinkedHashMap<>(EntityShapes.propose(cols, types, "stream"));
        et.put("recordType", OnboardDraft.choice("call", "", true));
        Map<String, Object> before = roleOf(et, "release_cause");
        assertEquals("detail", before.get("value"), "every sampled value differs: a Detail without the record type");
        EntityShapes.recordTypeRoles(et, cols, List.of(CALL_WITH_ROLES));

        Map<String, Object> cause = roleOf(et, "release_cause");
        assertEquals("dimension", cause.get("value"), cause.toString());
        assertEquals("high", cause.get("confidence"));
        assertEquals("detail", cause.get("base"), "the role without the record type, to undo it");
        assertEquals("high", cause.get("baseConfidence"), "and how sure that role was");
        assertTrue(String.valueOf(cause.get("evidence")).contains("a voice call record usually has a Category"), cause.toString());
        // a Measure hint on a text column does not make it a Measure; it may still be a Category
        assertEquals("detail", roleOf(et, "charge_band").get("value"), "'charge' is a Measure hint, and the column holds text");
        // "call" is a Category hint naming calling (an Entity) and call_seconds (the link's measure, a whole number): both keep their role
        assertEquals("entity", roleOf(et, "calling").get("value"), "a column holding an Entity Type keeps its role");
        assertEquals("measure", roleOf(et, "call_seconds").get("value"), "the link's measure keeps its role");
        // the link's primaries keep theirs: start_at is a Time either way, ended_at stays what its type says
        assertEquals("temporal", roleOf(et, "ended_at").get("value"));
        assertEquals("detail", roleOf(et, "served_cell").get("value"), "a hint is a whole pattern: 'service' does not name served_cell");

        // a guessed record type gives guessed roles
        Map<String, Object> low = new java.util.LinkedHashMap<>(EntityShapes.propose(cols, types, "stream"));
        low.put("recordType", OnboardDraft.choice("call", "", false));
        EntityShapes.recordTypeRoles(low, cols, List.of(CALL_WITH_ROLES));
        assertEquals("low", roleOf(low, "release_cause").get("confidence"));
        // no record type, no change
        Map<String, Object> none = new java.util.LinkedHashMap<>(EntityShapes.propose(cols, types, "stream"));
        none.put("recordType", OnboardDraft.choice(null, "", false));
        EntityShapes.recordTypeRoles(none, cols, List.of(CALL_WITH_ROLES));
        assertEquals("detail", roleOf(none, "release_cause").get("value"));
    }

    @Test
    void aHintedRoleNeedsAColumnTypeThatCanHoldIt() {
        IndustryPack.RoleHints h = CALL_WITH_ROLES.roleHints();
        assertEquals("temporal", EntityShapes.hintedRole("start_at", "TIMESTAMP", h)[0]);
        assertNull(EntityShapes.hintedRole("start_at", "VARCHAR", h), "a Time is a date or timestamp; 'start' names no Category");
        assertEquals("measure", EntityShapes.hintedRole("call_duration", "DOUBLE", h)[0]);
        assertNull(EntityShapes.hintedRole("duration", "VARCHAR", h));
        assertEquals("dimension", EntityShapes.hintedRole("cause", "BIGINT", h)[0], "a coded category may be a whole number");
        assertNull(EntityShapes.hintedRole("cause", "DOUBLE", h));
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
