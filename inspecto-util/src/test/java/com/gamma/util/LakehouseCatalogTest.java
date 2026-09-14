package com.gamma.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared-catalog config surface (scale-out phase C, §5.4 bullet 5).
 *
 * <p><b>What matters here, in order.</b> That <b>absence is the default</b> — Personal and single-node
 * Standard have no shared catalog and must not need one, so an unconfigured deployment must read as "no
 * catalog", never as an error. That a <b>half-configured pair is refused</b>, because a catalog with no
 * data path cannot be attached and a data path with no catalog names nothing, and either half alone would
 * surface as reads quietly seeing less than they should. And that the <b>shared-vs-file rule</b> has one
 * definition, since the write and read sides both apply it and two copies would drift.
 */
class LakehouseCatalogTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(LakehouseCatalog.CATALOG_PROPERTY);
        System.clearProperty(LakehouseCatalog.DATA_PROPERTY);
        System.clearProperty(Topology.PROPERTY);
    }

    // ── 1. absence is the default, not a misconfiguration ─────────────────────────────────────────

    @Test
    void nothingConfiguredMeansNoCatalog() {
        assertNull(LakehouseCatalog.configured(),
                "an unconfigured deployment has no shared lakehouse; that is Personal and single-node "
                        + "Standard, and it must not be an error");
        assertFalse(LakehouseCatalog.isConfigured());
    }

    @Test
    void blankValuesCountAsUnset() {
        System.setProperty(LakehouseCatalog.CATALOG_PROPERTY, "   ");
        System.setProperty(LakehouseCatalog.DATA_PROPERTY, "");

        assertNull(LakehouseCatalog.configured(),
                "a blank -D is how a property arrives from an unset shell variable; treating it as a "
                        + "configured empty catalog would be worse than treating it as absent");
    }

    // ── 2. both halves or neither ─────────────────────────────────────────────────────────────────

    @Test
    void aCatalogWithoutADataPathIsRefused() {
        System.setProperty(LakehouseCatalog.CATALOG_PROPERTY, "postgres:dbname=lake host=db");

        IllegalStateException boom = assertThrows(IllegalStateException.class, LakehouseCatalog::configured,
                "a catalog with no data path cannot be attached at all");
        assertTrue(boom.getMessage().contains("only the catalog"),
                "the message must say WHICH half is missing: " + boom.getMessage());
    }

    @Test
    void aDataPathWithoutACatalogIsRefused() {
        System.setProperty(LakehouseCatalog.DATA_PROPERTY, "/mnt/lake");

        IllegalStateException boom = assertThrows(IllegalStateException.class, LakehouseCatalog::configured,
                "a data path with no catalog names nothing");
        assertTrue(boom.getMessage().contains("only the data path"),
                "the message must say WHICH half is missing: " + boom.getMessage());
    }

    @Test
    void bothTogetherResolve() {
        System.setProperty(LakehouseCatalog.CATALOG_PROPERTY, "postgres:dbname=lake host=db");
        System.setProperty(LakehouseCatalog.DATA_PROPERTY, "/mnt/lake");

        LakehouseCatalog.Catalog c = LakehouseCatalog.configured();
        assertEquals("postgres:dbname=lake host=db", c.url());
        assertEquals("/mnt/lake", c.dataPath());
        assertTrue(LakehouseCatalog.isConfigured());
    }

    @Test
    void valuesAreTrimmed() {
        System.setProperty(LakehouseCatalog.CATALOG_PROPERTY, "  postgres:dbname=lake  ");
        System.setProperty(LakehouseCatalog.DATA_PROPERTY, "  /mnt/lake  ");

        LakehouseCatalog.Catalog c = LakehouseCatalog.configured();
        assertEquals("postgres:dbname=lake", c.url(),
                "a -D value picked up from a script commonly carries whitespace; an untrimmed catalog URL "
                        + "would fail the shared-catalog check for a reason the operator cannot see");
        assertEquals("/mnt/lake", c.dataPath());
    }

    // ── 3. the shared-vs-file rule, shared with the write side ────────────────────────────────────

    @Test
    void serverBackedCatalogsAreShared() {
        assertTrue(LakehouseCatalog.isShared("postgres:dbname=lake host=db"));
        assertTrue(LakehouseCatalog.isShared("mysql:host=db database=lake"),
                "the rule's subject is file-vs-server, not postgres specifically");
        assertTrue(LakehouseCatalog.isShared("POSTGRES:dbname=lake"), "the prefix match is case-insensitive");
    }

    @Test
    void fileAndUrlSpellingsAreNotShared() {
        assertFalse(LakehouseCatalog.isShared("/var/lib/inspecto/lake.ducklake"),
                "a path is a private file catalog");
        assertFalse(LakehouseCatalog.isShared("postgresql://u:p@localhost:5432/lake"),
                "measured 2026-09-14: a postgresql:// URL carries no backend prefix, so DuckLake reads it "
                        + "as a file path — it only LOOKS shared");
        assertFalse(LakehouseCatalog.isShared("postgres://u:p@localhost:5432/lake"),
                "the postgres:// URL form fails for the same reason; the working spelling is "
                        + "postgres: followed by libpq keywords");
        assertFalse(LakehouseCatalog.isShared(null));
    }

    @Test
    void requireSharedRefusesAFileCatalogWhenPartitioned() {
        System.setProperty(Topology.PROPERTY, "partitioned");

        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> LakehouseCatalog.requireShared("lake.ducklake", "-Dinspecto.ducklake.catalog"),
                "a file catalog on N nodes is N private catalogs, and nothing would ever report it");

        assertTrue(boom.getMessage().startsWith("-Dinspecto.ducklake.catalog="),
                "the message must name WHERE the bad value came from — the same rule serves a config key "
                        + "and a -D property: " + boom.getMessage());
        assertTrue(boom.getMessage().contains("postgres:dbname="),
                "refusing is not enough; the message must carry the spelling measured WORKING, or the "
                        + "operator's next guess is the postgresql:// URL that also fails: " + boom.getMessage());
    }

    // The falsification arm. Without it the test above would pass against a method that ALWAYS threw,
    // which would forbid the file catalog that is CORRECT on every single-node install.
    @Test
    void requireSharedAllowsAFileCatalogOnASingleNode() {
        System.setProperty(Topology.PROPERTY, "single");

        assertDoesNotThrow(() -> LakehouseCatalog.requireShared("lake.ducklake", "-Dinspecto.ducklake.catalog"),
                "one process owning its own lakehouse is the documented single-node shape");
    }

    @Test
    void requireSharedIsInertWhenTheTopologyIsUnset() {
        System.clearProperty(Topology.PROPERTY);

        assertDoesNotThrow(() -> LakehouseCatalog.requireShared("lake.ducklake", "-Dinspecto.ducklake.catalog"),
                "the default topology is single — this must not change behaviour for anyone who never set "
                        + "the flag");
    }
}
