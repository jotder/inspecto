package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImportPaths} as a unit. ⚠ WINDOWS-SENSITIVE: the aliases below (a trailing dot, an 8.3 short name) only
 * reach a reserved file on Windows — but the segment rules refuse them by NAME, so these run and must pass on
 * every platform; nothing here needs 8.3 support to prove the refusal.
 */
class ImportPathsTest {

    @Test
    void aliasesAndDeviceNamesAreRefusedOnEveryPlatform(@TempDir Path root) {
        for (String p : List.of("roles.toon.", "roles.toon ", "ROLES~1.TOO", "PENDIN~1/x.json", "audit./x.json",
                "registry/access-profiles./x.toon", "CON", "con.toon", "orders/aux.csv", "LPT9.toon", "a:b.toon",
                "x\u0000_pipeline.toon", ".history/v1.toon", "../x_pipeline.toon", "/etc/x_pipeline.toon", ""))
            assertNotNull(ImportPaths.refusal(root, p), p);
    }

    @Test
    void theAllowlistAdmitsTheShapesABundleCarriesAndNothingElse(@TempDir Path root) {
        for (String ok : List.of("orders_pipeline.toon", "orders/orders_pipeline.toon", "orders/orders_schema.toon",
                "orders/voucher_76.toon", "orders/orders_mapping.csv", "jobs/nightly_job.toon", "orders_enrich.toon",
                "sftp_connection.toon", "registry/datasets/sales.toon", "registry/widgets/kpi.toon", "sites.grammar.toon"))
            assertNull(ImportPaths.refusal(root, ok), ok);
        for (String no : List.of("roles.toon", "approval.toon", "branding.toon", "anything.toon", "Roles.Toon",
                "registry/access-profiles/x.toon", "registry/access-catalog/catalog.toon", "registry/x.toon",
                "demo-users.toon", "offers.toon", "grants.toon", "agent/policy.json", "agent/x.toon",
                "expectation-baselines/e.toon",
                "pending-changes/pc-1.json", "recon-state/r.json", "audit/entity-facts/1.json", "orders/data.parquet"))
            assertNotNull(ImportPaths.refusal(root, no), no);
    }

    /**
     * Hole 4: for a DIRECTORY alias on a Space where the directory does not exist yet, the trailing-dot rule is
     * the ONLY guard — "audit." is not the string "audit", the real-path check finds nothing to compare, and the
     * shape rule is satisfied by "x.toon". Windows then drops the dot and creates audit/.
     */
    @Test
    void theTrailingDotRuleAloneRefusesADirectoryAliasWhenTheDirectoryIsAbsent(@TempDir Path root) {
        for (String p : List.of("audit./x.toon", "pending-changes./x.toon", "agent./policy.toon", "recon-state./r.toon")) {
            String why = ImportPaths.refusal(root, p);
            assertNotNull(why, p);
            assertTrue(why.contains("ends in '.'"), p + " must be refused BY the trailing-dot rule: " + why);
        }
    }

    /** Hole 1: only REAL reference keys reference anything; a notes field or a description never does. */
    @Test
    void onlyRealReferenceKeysReferenceAnEntry() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("orders/orders_pipeline.toon", ("""
                name: orders
                notes:
                  r0: "demo-users.toon"
                  r1: "orders/g.asn"
                description: "anything.toon"
                processing:
                  schema_file: orders_v2.toon
                  mapping_file: m.csv
                parsing:
                  asn1:
                    grammar_file: g.asn
                """).getBytes(StandardCharsets.UTF_8));
        for (String e : List.of("orders/orders_v2.toon", "orders/m.csv", "orders/g.asn", "demo-users.toon", "anything.toon"))
            entries.put(e, "x: 1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(Set.of("orders/orders_v2.toon", "orders/m.csv", "orders/g.asn"), ImportPaths.referencedEntries(entries));
    }

    /** Even through a real reference key, a referenced file gets no way past the extension, registry or denylist rules. */
    @Test
    void aReferenceWidensTheExtensionOnlyNeverTheDenylist(@TempDir Path root) {
        assertNull(ImportPaths.refusal(root, "orders/g.asn", Set.of("orders/g.asn")), "a referenced grammar source");
        assertNotNull(ImportPaths.refusal(root, "orders/g.asn"), "the same file, unreferenced");
        assertNull(ImportPaths.refusal(root, "voucher_116.toon", Set.of("voucher_116.toon")), "a referenced root schema");
        for (String p : List.of("demo-users.toon", "roles.toon", "agent/policy.json", "agent/policy.toon",
                "registry/access-profiles/x.toon", "orders/x.exe", "offers.toon", "grants.toon"))
            assertNotNull(ImportPaths.refusal(root, p, Set.of(p)), p + " referenced");
    }

    /** Layer 3: a reserved directory reached through its real path is refused even with a plain-looking name. */
    @Test
    void theRealPathOfAReservedDirectoryIsRefused(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("pending-changes"));
        assertNotNull(ImportPaths.realPathRefusal(root, "pending-changes/x.toon"));
        assertNotNull(ImportPaths.realPathRefusal(root, "PENDING-CHANGES/x.toon"), "case-folded filesystems");
        assertNull(ImportPaths.realPathRefusal(root, "orders/x_schema.toon"));
    }
}
