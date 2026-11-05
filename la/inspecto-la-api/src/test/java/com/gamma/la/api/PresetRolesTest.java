package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.IndustryPack;
import com.gamma.la.core.DatasetProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PresetQueries#roles}: the Amount a preset sums is the column the Dataset's (or Mapping's) link mapping names,
 * and only when it names none the first number column the Industry pack hints ({@code LA-COLUMN-ROLES-1} P0 defect: the
 * mapping's {@code amount} was validated, never used, so a Mapping chosen for another amount got its base's sums).
 */
class PresetRolesTest {

    private static final List<DatasetProvider.Column> COLUMNS = List.of(
            new DatasetProvider.Column("caller", "text", null, null),
            new DatasetProvider.Column("callee", "text", null, null),
            new DatasetProvider.Column("amount", "number", null, null),
            new DatasetProvider.Column("fee", "number", null, null));

    @Test
    void theMappingsAmountWinsOverThePackHint() {
        Map<String, Object> dataset = Map.of("defaultLinkMapping",
                Map.of("from", "caller", "to", "callee", "amount", "FEE"));
        assertEquals("fee", PresetQueries.roles(dataset, COLUMNS, List.of(), null).amount());
    }

    /** LA-COLUMN-ROLES-1 P1: the primary Measure (and Time) are read under their names, before the old ones. */
    @Test
    void thePrimaryMeasureIsTheAmount() {
        Map<String, Object> dataset = Map.of("defaultLinkMapping",
                Map.of("from", "caller", "to", "callee", "primaryMeasure", "fee"));
        assertEquals("fee", PresetQueries.roles(dataset, COLUMNS, List.of(), null).amount());
    }

    // ── LA-PRESET-ROLES-1 + ENTITY-TYPE-RECORD-ROLES-1: From / To without a link mapping ─────────────────────────

    /** A subscriber's number and SIM first, then the two parties of the call: all four hold an Entity Type. */
    private static final List<DatasetProvider.Column> CDR = List.of(
            new DatasetProvider.Column("served_number", "text", null, null),
            new DatasetProvider.Column("served_sim", "text", null, null),
            new DatasetProvider.Column("other_party", "text", null, null),
            new DatasetProvider.Column("recording_party", "text", null, null));

    private static final List<EntityTypes.EntityType> TYPES = List.of(EntityTypes.coreDefault("msisdn"), EntityTypes.coreDefault("imsi"));

    private static Map<String, Object> cdr(String recordType) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("columns", List.of(Map.of("name", "served_number", "classification", "MSISDN"),
                Map.of("name", "served_sim", "classification", "IMSI"),
                Map.of("name", "other_party", "classification", "MSISDN"),
                Map.of("name", "recording_party", "classification", "MSISDN")));
        if (recordType != null) d.put("recordType", recordType);
        return d;
    }

    @Test
    void withoutAMappingTheEndsAreTwoOfOneEntityTypeNotANumberAndItsSim() {
        PresetQueries.Roles r = PresetQueries.roles(cdr(null), CDR, TYPES, null, List.of());
        assertEquals("served_number", r.from());
        assertEquals("other_party", r.to(), "the first two hold a number and a SIM: the first two numbers are the link");
    }

    @Test
    void theRecordTypesPartiesNameTheEndsFirst() {
        IndustryPack.RecordType call = new IndustryPack.RecordType("call", "Call", "", List.of("msisdn"),
                new IndustryPack.RoleHints(List.of(), List.of(), List.of(), List.of("^recording"), List.of("^other")));
        PresetQueries.Roles r = PresetQueries.roles(cdr("call"), CDR, TYPES, null, List.of(call));
        assertEquals("recording_party", r.from());
        assertEquals("other_party", r.to());
        PresetQueries.Roles unknown = PresetQueries.roles(cdr("sms"), CDR, TYPES, null, List.of(call));
        assertEquals("served_number", unknown.from(), "a record type not in force names no parties");
        // a mapping still wins over every guess
        Map<String, Object> mapped = cdr("call");
        mapped.put("defaultLinkMapping", Map.of("from", "served_number", "to", "served_sim"));
        assertEquals("served_sim", PresetQueries.roles(mapped, CDR, TYPES, null, List.of(call)).to());
    }

    @Test
    void withTwoEntityColumnsOfDifferentTypesTheyAreTheEnds() {
        Map<String, Object> d = Map.of("columns", List.of(Map.of("name", "served_number", "classification", "MSISDN"),
                Map.of("name", "served_sim", "classification", "IMSI")));
        PresetQueries.Roles r = PresetQueries.roles(d, CDR, TYPES, null, List.of());
        assertEquals("served_number", r.from());
        assertEquals("served_sim", r.to());
    }

    @Test
    void withoutAMappedAmountThePackHintStillPicksOne() {
        Map<String, Object> dataset = Map.of("defaultLinkMapping", Map.of("from", "caller", "to", "callee"));
        assertEquals("amount", PresetQueries.roles(dataset, COLUMNS, List.of(), null).amount());
    }
}
