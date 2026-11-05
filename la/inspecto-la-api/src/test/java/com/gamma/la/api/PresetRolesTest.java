package com.gamma.la.api;

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

    @Test
    void withoutAMappedAmountThePackHintStillPicksOne() {
        Map<String, Object> dataset = Map.of("defaultLinkMapping", Map.of("from", "caller", "to", "callee"));
        assertEquals("amount", PresetQueries.roles(dataset, COLUMNS, List.of(), null).amount());
    }
}
