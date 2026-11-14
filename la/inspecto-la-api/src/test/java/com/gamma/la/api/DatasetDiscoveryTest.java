package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;
import com.gamma.la.core.DatasetProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link DatasetDiscovery#held}: the Entity Types a Dataset holds, by the one "type a class names" rule. */
class DatasetDiscoveryTest {

    /**
     * MASKING-UNION-RESIDUALS-1: a column classed with a type's id ({@code sim}, which claims only SIM_CARD) holds that type,
     * as a claimed class does; a class no type names holds none.
     */
    @Test
    void aColumnClassedWithATypesIdHoldsThatType() {
        List<EntityTypes.EntityType> types = List.of(EntityTypes.coreDefault("msisdn"),
                new EntityTypes.EntityType("sim", "SIM", "digits", false, List.of("SIM_CARD")));
        List<DatasetProvider.Column> columns = List.of(new DatasetProvider.Column("caller", "text", null, null),
                new DatasetProvider.Column("served_sim", "text", null, null),
                new DatasetProvider.Column("note", "text", null, null));
        Map<String, Object> dataset = Map.of("columns", List.of(Map.of("name", "caller", "classification", "MSISDN"),
                Map.of("name", "served_sim", "classification", " Sim "),
                Map.of("name", "note", "classification", "NOBODY_NAMES")));
        List<DatasetDiscovery.Held> held = DatasetDiscovery.held(dataset, columns, types);
        assertEquals(List.of("msisdn", "sim"), held.stream().map(h -> h.type().id()).toList());
        assertEquals(List.of("served_sim"), held.get(1).columns());
    }
}
