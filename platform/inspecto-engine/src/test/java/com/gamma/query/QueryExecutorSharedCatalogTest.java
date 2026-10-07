package com.gamma.query;

import com.gamma.util.LakehouseCatalog;
import com.gamma.util.Topology;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read side of the shared lakehouse (scale-out phase C, §5.4 bullet 5, D4/D4a).
 *
 * <p><b>What this is for.</b> A query normally locates Parquet by absolute path, so a node can only read
 * what is on its own disk. Attaching the deployment's shared DuckLake catalog is what lets any node read
 * every slice, including ones another node ingested — the plan's difference between a platform and N
 * isolated islands.
 *
 * <p>⛔ <b>Why the DECISION is tested and not a live attach.</b> Driving a real attach needs either a live
 * Postgres or the {@code ducklake} extension present, and a test gated on either SKIPS on every machine
 * without it. This repo has already had a skipping test hide a broken classpath for months, so the branch
 * this code actually owns — which statement, when, and when none — is pinned directly, the same idiom
 * {@code DuckLakeRegistrar.onRegistrationFailure} uses on the write side. The live behaviour was measured
 * separately on 2026-09-14 against a real Postgres catalog, including the finding that a SEALED sandbox
 * refuses the attach, which is why it runs in the trusted registration phase.
 */
class QueryExecutorSharedCatalogTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(LakehouseCatalog.CATALOG_PROPERTY);
        System.clearProperty(LakehouseCatalog.DATA_PROPERTY);
        System.clearProperty(Topology.PROPERTY);
    }

    private static void configure(String url, String dataPath) {
        System.setProperty(LakehouseCatalog.CATALOG_PROPERTY, url);
        System.setProperty(LakehouseCatalog.DATA_PROPERTY, dataPath);
    }

    // ── 1. unconfigured is the common case and must cost nothing ──────────────────────────────────

    @Test
    void noCatalogConfiguredMeansNoAttach() {
        assertNull(QueryExecutor.attachSql(),
                "Personal and single-node Standard have no shared lakehouse; reads must behave exactly as "
                        + "they did before this existed");
    }

    // ── 2. configured: the statement it issues ────────────────────────────────────────────────────

    @Test
    void aConfiguredCatalogIsAttachedUnderTheLakeAlias() {
        configure("postgres:dbname=lake host=db port=5432 user=u password=p", "/mnt/lake");

        String sql = QueryExecutor.attachSql();

        assertTrue(sql.startsWith("ATTACH 'ducklake:postgres:dbname=lake host=db"),
                "the value is passed through as the DuckLake backend spec, not reinterpreted: " + sql);
        assertTrue(sql.contains(" AS " + QueryExecutor.SHARED_CATALOG_ALIAS + " "),
                "queries reach the lakehouse through a stable alias: " + sql);
        assertTrue(sql.contains("DATA_PATH '/mnt/lake'"),
                "an attach without the data path is refused by DuckLake when the catalog already records "
                        + "one, so it must always be named: " + sql);
    }

    @Test
    void windowsDataPathsAreNormalisedToForwardSlashes() {
        configure("postgres:dbname=lake host=db", "C:\\mnt\\lake");

        assertTrue(QueryExecutor.attachSql().contains("DATA_PATH 'C:/mnt/lake'"),
                "a backslash inside a single-quoted SQL literal is an escape on some paths and a separator "
                        + "on others; the write side normalises for the same reason");
    }

    // ── 3. the same shared-vs-private rule the WRITE side applies ─────────────────────────────────
    //
    // ⛔ One definition, in LakehouseCatalog. If the read side accepted a catalog the write side refused
    // (or the reverse), one of them would quietly be using a private catalog — which is the exact defect
    // the rule exists to catch, arriving through the rule itself.

    @Test
    void aPrivateFileCatalogIsRefusedWhenPartitioned() {
        System.setProperty(Topology.PROPERTY, "partitioned");
        configure("/mnt/lake/catalog.ducklake", "/mnt/lake");

        IllegalStateException boom = assertThrows(IllegalStateException.class, QueryExecutor::attachSql,
                "reading through a private catalog shows this node only its own slices, which is the "
                        + "failure this whole section exists to prevent");
        assertTrue(boom.getMessage().contains(LakehouseCatalog.CATALOG_PROPERTY),
                "the message must name the property carrying the bad value: " + boom.getMessage());
    }

    // The falsification arm. Without it the test above would pass against a method that ALWAYS threw,
    // which would break every single-node install that points at a perfectly legitimate file catalog.
    @Test
    void aFileCatalogIsFineOnASingleNode() {
        System.setProperty(Topology.PROPERTY, "single");
        configure("/mnt/lake/catalog.ducklake", "/mnt/lake");

        String sql = assertDoesNotThrow(QueryExecutor::attachSql,
                "one process owning its own lakehouse is the documented single-node shape");
        assertTrue(sql.contains("ATTACH 'ducklake:/mnt/lake/catalog.ducklake'"),
                "and it must still actually attach it: " + sql);
    }

    // ── 4. a half-configured pair is a boot mistake, not a silent partial read ────────────────────

    @Test
    void aCatalogWithNoDataPathIsRefusedRatherThanAttachedWithout() {
        System.setProperty(LakehouseCatalog.CATALOG_PROPERTY, "postgres:dbname=lake host=db");

        assertThrows(IllegalStateException.class, QueryExecutor::attachSql,
                "attaching with a missing data path would read a catalog whose files it cannot find, and "
                        + "return quietly short results rather than failing");
    }

    @Test
    void theAliasIsStable() {
        assertEquals("lake", QueryExecutor.SHARED_CATALOG_ALIAS,
                "authored queries reference this by name (lake.main.<table>); changing it silently breaks "
                        + "every saved query that reaches the lakehouse");
    }

    // ── 5. the backend scanner must be loaded BY NAME, or an air-gapped read egresses ─────────────
    // AIRGAP-PGSCANNER-LOAD-1 (2026-09-14). The ATTACH autoloads the scanner, and autoload consults
    // DuckDB's own extension_directory -- never -Dduckdb.extension.dir. So the file package.ps1 stages
    // is invisible to it, and the attach reaches for a network INSTALL on a host that has none, which
    // D10 makes FATAL in a partitioned topology. Naming the extension is what lets attachSharedCatalog
    // load it through the cached -> staged-file -> network ladder instead.

    @Test
    void aPostgresCatalogNamesTheScannerItsAttachWouldOtherwiseAutoload() {
        configure("postgres:dbname=lake host=db port=5432 user=u password=p", "/srv/lake");

        assertEquals("postgres_scanner", QueryExecutor.attachBackendExtension(),
                "without this the read side stages an extension nothing loads -- the defect shipped on "
                        + "the write side hours before this was found");
    }

    // The falsification arm. Without it this would pass against a method that ALWAYS returned
    // postgres_scanner, which would fail every unconfigured Personal read on a host that has no
    // extension at all.
    @Test
    void anUnconfiguredNodeNamesNoExtension() {
        assertNull(QueryExecutor.attachBackendExtension(),
                "no shared catalog means no attach, so there is nothing to load");
    }

    @Test
    void aFileCatalogNamesNoExtension() {
        configure("lake.ducklake", "/srv/lake");

        assertNull(QueryExecutor.attachBackendExtension(),
                "a single-node file catalog attaches with no scanner; loading one would turn a working "
                        + "install into a failure for nothing");
    }
}
