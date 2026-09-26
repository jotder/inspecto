package com.gamma.geolink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-17 step 5 — {@code typed} masking reads an Entity List op's SEALED {@code masked} flag (design §4.4.2): a list
 * that sealed no flag is masked (fail closed), and a sealed {@code masked: false} stays raw only while today's type is
 * unmasked too — a type tightened (or removed) since the seal masks, as {@link EntityListRoutes} does. Driven over a hand-built
 * log, because no route can seal a list without the flag.
 */
class EntityMaskingTest {

    private static final String MEMBER = "+447700900123", RAW_FORM = "0044 7700-900123";

    private static InvestigationRoutes.Inv inv(Path root) throws Exception {
        InvestigationRoutes.Inv inv = new InvestigationRoutes.Inv(new SnapshotStore(root), root, "case-a",
                Map.of("dataset", "calls_ds", "sourceCol", "caller", "targetCol", "callee"));
        Files.createDirectories(inv.dir());
        return inv;
    }

    /** An expand that read {@code alice → 0044 7700-900123}, then an excludeBy over an msisdn list of {@code list}. */
    private static List<Map<String, Object>> log(Map<String, Object> list) {
        Map<String, Object> sealed = new HashMap<>(Map.of("listId", "mules", "entityType", "msisdn",
                "normaliser", "e164", "members", List.of(MEMBER)));
        sealed.putAll(list);
        return List.of(
                Map.of("op", "seed", "params", Map.of("ids", List.of("alice"))),
                Map.of("op", "expand", "params", Map.of(), "read",
                        Map.of("rows", List.of(Map.of("source", "alice", "target", RAW_FORM)))),
                Map.of("op", "excludeBy", "params", Map.of("listId", "mules", "reason", "r"), "list", sealed));
    }

    @Test
    void aSealedListWithNoMaskedFlagIsMaskedFailClosed(@TempDir Path root) throws Exception {
        EntityMasking m = EntityMasking.of(inv(root), log(Map.of()), List.of());
        assertEquals("typed", m.describe().get("mode"));
        assertEquals(2, m.describe().get("masked"), m.describe().toString());
        Object out = m.apply(List.of(MEMBER, RAW_FORM, "alice"));
        List<?> l = (List<?>) out;
        assertTrue(String.valueOf(l.get(0)).startsWith(EntityMasking.TOKEN_PREFIX), out.toString());
        assertTrue(String.valueOf(l.get(1)).startsWith(EntityMasking.TOKEN_PREFIX),
                "the raw form the member matches under its normaliser is masked too: " + out);
        assertEquals("alice", l.get(2), "an untyped id stays raw");
    }

    @Test
    void aSealedMaskedFalseStaysRawWhileTodaysTypeIsUnmaskedToo(@TempDir Path root) throws Exception {
        // handset is a default type with masked: false — sealed and current flag agree, so nothing is masked
        EntityMasking m = EntityMasking.of(inv(root), log(Map.of("masked", false, "entityType", "handset")), List.of());
        assertEquals(0, m.describe().get("masked"), m.describe().toString());
        assertEquals(List.of(MEMBER, RAW_FORM), m.apply(List.of(MEMBER, RAW_FORM)));
    }

    @Test
    void aSealedMaskedFalseIsMaskedOnceTodaysTypeIsMasked(@TempDir Path root) throws Exception {
        // sealed while msisdn was unmasked; the Space's msisdn is masked today (the default) — the list route masks
        // these members, so the Investigation must not keep showing them raw
        EntityMasking m = EntityMasking.of(inv(root), log(Map.of("masked", false)), List.of());
        assertEquals(2, m.describe().get("masked"), m.describe().toString());
        assertTrue(String.valueOf(((List<?>) m.apply(List.of(MEMBER))).get(0)).startsWith(EntityMasking.TOKEN_PREFIX));
    }

    @Test
    void aSealedMaskedFalseIsMaskedOnceItsTypeIsNoLongerInForce(@TempDir Path root) throws Exception {
        EntityMasking m = EntityMasking.of(inv(root), log(Map.of("masked", false, "entityType", "badge")), List.of());
        assertEquals(2, m.describe().get("masked"), m.describe().toString());
    }
}
