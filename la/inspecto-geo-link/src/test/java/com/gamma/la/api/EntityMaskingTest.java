package com.gamma.la.api;

import com.gamma.la.core.InvestigationStores;
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
        InvestigationRoutes.Inv inv = new InvestigationRoutes.Inv(InvestigationStores.of(root), root, "case-a",
                Map.of("dataset", "calls_ds", "sourceCol", "caller", "targetCol", "callee"));
        inv.store().create(inv.id(), "{}");   // the Investigation must exist before it can mint its mask key
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

    // ── ASSURE-CLASSIFICATION-PROPAGATION-1 (operator 2026-10-04): the bound column's class also comes from the
    //    pipeline schema's lineage, through the platform's one resolver; untraceable lineage masks everything ──

    /** A pipeline "calls" whose raw CALLER carries {@code callerClass}, mapped to the stored {@code caller}; the
     *  Investigation's Dataset {@code calls_ds} reads it and classifies nothing itself. */
    private static void callsPipeline(Path root, String callerClass, String extraPipeline) throws Exception {
        Files.createDirectories(root.resolve("calls"));
        Files.writeString(root.resolve("calls/calls_pipeline.toon"), "name: calls\nactive: true\n\ndirs:\n"
                + "  poll: data/inbox/calls\n  database: data/calls/database\n  backup: data/calls/backup\n"
                + "  temp: data/calls/temp\n  errors: data/calls/errors\n  quarantine: data/calls/quarantine\n"
                + "  markers: data/calls/markers\n  status_dir: data/calls/status\n  log_dir: data/calls/logs\n\n"
                + "output:\n  format: PARQUET\n  compression: snappy\n\nprocessing:\n  threads: 1\n"
                + "  file_pattern: \"glob:**/*.csv\"\n  schema_file: calls_schema.toon\n" + extraPipeline);
        Files.writeString(root.resolve("calls/calls_schema.toon"), "partitionKey: DAY\nraw:\n  name: CALLS\n  format: CSV\n"
                + "  fields[2]{name,selector,type,description,unit,classification}:\n"
                + "    CALLER,\"0\",VARCHAR,\"\",\"\",\"" + callerClass + "\"\n    CALLEE,\"1\",VARCHAR,\"\",\"\",\"\"\n"
                + "mapping:\n  canonicalName: calls\n  rawName: CALLS\n  fields[2]:\n"
                + "    - name: caller\n      from: CALLER\n      fn: keep\n    - name: callee\n      from: CALLEE\n      fn: keep\n");
        new com.gamma.pipeline.ComponentStore(root.resolve("registry")).write("dataset", "calls_ds",
                Map.of("physicalRef", "calls"));
    }

    private static final List<Map<String, Object>> EXPAND_ONLY = List.<Map<String, Object>>of(
            Map.of("op", "seed", "params", Map.of("ids", List.of("alice"))),
            Map.of("op", "expand", "params", Map.of(), "read",
                    Map.of("rows", List.of(Map.of("source", "alice", "target", "bob")))));

    @Test
    void aBoundColumnClassifiedOnlyInThePipelineSchemaMasksEveryId(@TempDir Path root) throws Exception {
        callsPipeline(root, "MSISDN", "");   // msisdn is a masked default Entity Type
        EntityMasking m = EntityMasking.of(inv(root), EXPAND_ONLY, List.of());
        assertEquals(2, m.describe().get("masked"), m.describe().toString());
        assertTrue(String.valueOf(m.describe().get("basis")).contains("caller"), m.describe().toString());
    }

    @Test
    void anUnclassifiedPipelineColumnLeavesExpandedIdsRaw(@TempDir Path root) throws Exception {
        callsPipeline(root, "", "");
        EntityMasking m = EntityMasking.of(inv(root), EXPAND_ONLY, List.of());
        assertEquals(0, m.describe().get("masked"), m.describe().toString());
    }

    @Test
    void untraceablePipelineLineageMasksEveryIdFailClosed(@TempDir Path root) throws Exception {
        // a summarize step rewrites the columns while CALLER is MSISDN, so the bound columns cannot be traced
        callsPipeline(root, "MSISDN", "steps[1]:\n  - summarize:\n      group_by: [callee]\n");
        EntityMasking m = EntityMasking.of(inv(root), EXPAND_ONLY, List.of());
        assertEquals(2, m.describe().get("masked"), m.describe().toString());
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
