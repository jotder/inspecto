package com.gamma.service;

import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import com.gamma.ops.OpsStoreFamily;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The four Operational Object families, contributed by this module (MODULE-REORG-P1-FAMILY): the roster they join,
 * the manifest that declares them, and the {@code objects.backend} behaviour that used to be asserted in the
 * processor's {@code OperationalDbTest} (which no longer sees them). Every test restores the properties it touched.
 */
class ObjectFamiliesOperationalDbTest {

    private static void withProps(Map<String, String> props, Runnable body) {
        List<Map.Entry<String, String>> prior = new ArrayList<>();
        props.forEach((k, v) -> {
            prior.add(Map.entry(k, String.valueOf(System.getProperty(k))));
            System.setProperty(k, v);
        });
        try {
            body.run();
        } finally {
            for (Map.Entry<String, String> e : prior) {
                if ("null".equals(e.getValue())) System.clearProperty(e.getKey());
                else System.setProperty(e.getKey(), e.getValue());
            }
        }
    }

    private static void assumeDriverPresent() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "no PG driver on this test classpath");
        }
    }

    @Test
    void theRosterIsTheTwelveCoreFamiliesThenTheFourThisModuleContributes() {
        assertEquals(12, OperationalDb.core().size());
        assertEquals(List.of("OBJECTS", "LINKS", "NOTES", "TAGS"),
                OperationalDb.loaded().stream().map(StoreFamily::name).toList());
        assertEquals(16, OperationalDb.all().size(), "core + the ops families = the Enterprise roster of sixteen");
        assertEquals(OperationalDb.core(), OperationalDb.all().subList(0, 12), "core first, contributed after");
    }

    @Test
    void theManifestDeclaresExactlyTheFamiliesTheProviderContributes() {
        ModuleManifest m = ModuleManifests.load(getClass().getClassLoader()).manifests().stream()
                .filter(x -> "ops".equals(x.id())).findFirst().orElseThrow();
        assertEquals(4, m.provides().storeFamilies().size(), "the ops module owns four store families");
        assertEquals(List.of(OpsStoreFamily.values()).stream().map(Enum::name).toList(), m.provides().storeFamilies());
    }

    // ── OBJECTS-BACKEND-DEFAULT-MEMORY-1 (operator decision 2026-09-25) ────────────────────────────────
    // ⚠ The root pom pins objects.backend=memory for the test reactor; the default case CLEARS it.

    @Test
    void theObjectFamiliesDefaultToTheSpaceDuckdb_neverMemory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base) {
        String prior = System.getProperty(OperationalDb.OBJECTS_BACKEND);
        System.clearProperty(OperationalDb.OBJECTS_BACKEND);
        try {
            assertEquals("db", OperationalDb.objectsBackend());
            SpaceRoot root = SpaceRoot.under(base);
            for (StoreFamily f : OpsStoreFamily.values()) {
                OperationalDb.Resolved r = OperationalDb.resolve(f, root);
                assertEquals(OperationalDb.Source.SPACE_DEFAULT, r.source(), f.name());
                assertTrue(r.url().startsWith("jdbc:duckdb:"), r.url());
            }
            OperationalDb.verifySelectable();
        } finally {
            if (prior != null) System.setProperty(OperationalDb.OBJECTS_BACKEND, prior);
        }
    }

    @Test
    void enterprisePostgresWithoutAUrl_failsAtBoot_namingTheSettingToFix() {
        withProps(Map.of(OperationalDb.OBJECTS_BACKEND, "postgres"), () -> {
            IllegalStateException boom = assertThrows(IllegalStateException.class, OperationalDb::verifySelectable);
            assertTrue(boom.getMessage().contains("-Dinspecto.db.url"), boom.getMessage());
            assertTrue(boom.getMessage().contains("INSPECTO_DB_URL"), boom.getMessage());
        });
    }

    @Test
    void enterprisePostgres_refusesOneFamilyLeftOnDuckdb() {
        withProps(Map.of(OperationalDb.OBJECTS_BACKEND, "postgres",
                        "inspecto.db", "postgres",
                        "inspecto.db.url", "jdbc:postgresql://db:5432/inspecto",
                        "objects.notes.db.url", "jdbc:duckdb:/local/notes.db"),
                () -> {
                    IllegalStateException boom = assertThrows(IllegalStateException.class, OperationalDb::verifySelectable);
                    assertTrue(boom.getMessage().contains("objects.notes.db.url"), boom.getMessage());
                });
    }

    @Test
    void enterprisePostgresWithASharedUrl_passes_andEveryObjectFamilyResolvesToIt() {
        assumeDriverPresent();
        withProps(Map.of(OperationalDb.OBJECTS_BACKEND, "postgres",
                        "inspecto.db", "postgres",
                        "inspecto.db.url", "jdbc:postgresql://db:5432/inspecto"),
                () -> {
                    OperationalDb.verifySelectable();
                    assertEquals("jdbc:postgresql://db:5432/inspecto",
                            OperationalDb.resolve(OpsStoreFamily.TAGS, SpaceRoot.legacy()).url());
                });
    }
}
