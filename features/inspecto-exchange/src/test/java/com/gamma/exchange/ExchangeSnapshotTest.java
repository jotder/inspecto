package com.gamma.exchange;

import com.gamma.event.EventLog;
import com.gamma.exchange.ExchangeSnapshots.SnapshotMeta;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.SharedRefResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2 end-to-end at the unit level: an owner publishes a versioned snapshot, and a consumer resolves
 * {@code shared/owner/item} through {@link DatasetRelation} — grant-checked and fail-closed.
 */
class ExchangeSnapshotTest {

    private static String unix(Path p) {
        return p.toString().replace('\\', '/');
    }

    /** Seed a two-row Parquet Table + its dataset component in an owner space, then publish a snapshot. */
    private SnapshotMeta seedAndPublish(Path spacesRoot) throws Exception {
        Path financeCfg = spacesRoot.resolve("finance").resolve("config");
        Path registry = financeCfg.resolve("registry");
        Path dataRoot = spacesRoot.resolve("finance").resolve("data");
        Files.createDirectories(dataRoot.resolve("tax_receipts"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES (1,'a'),(2,'b')) t(amount,label)) TO "
                    + "'" + unix(dataRoot.resolve("tax_receipts").resolve("part.parquet")) + "' (FORMAT PARQUET)");
        }
        new ComponentStore(registry).write("dataset", "tax_receipts", Map.of("physicalRef", "tax_receipts"));

        Exchange ex = Exchange.under(spacesRoot);
        return ExchangeSnapshotWriter.publish(ex.dir(), "finance", registry, dataRoot,
                spacesRoot.resolve("finance").resolve("views"), "tax_receipts");
    }

    @Test
    void theSnapshotCarriesTheOwnersColumnClassification(@TempDir Path spacesRoot) throws Exception {
        Path registry = spacesRoot.resolve("finance").resolve("config").resolve("registry");
        Path dataRoot = spacesRoot.resolve("finance").resolve("data");
        Files.createDirectories(dataRoot.resolve("calls"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES ('441700900101',1.0)) t(msisdn,amount)) TO "
                    + "'" + unix(dataRoot.resolve("calls").resolve("part.parquet")) + "' (FORMAT PARQUET)");
        }
        new ComponentStore(registry).write("dataset", "calls", Map.of("physicalRef", "calls", "columns", java.util.List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "amount"))));
        Files.createDirectories(dataRoot.resolve("plainstore"));
        Files.copy(dataRoot.resolve("calls").resolve("part.parquet"), dataRoot.resolve("plainstore").resolve("part.parquet"));
        new ComponentStore(registry).write("dataset", "plain", Map.of("physicalRef", "plainstore"));
        Path shared = Exchange.under(spacesRoot).dir();
        Path views = spacesRoot.resolve("finance").resolve("views");

        Map<String, Object> byName = new java.util.HashMap<>();
        ExchangeSnapshotWriter.publish(shared, "finance", registry, dataRoot, views, "calls")
                .columns().forEach(c -> byName.put(String.valueOf(c.get("name")), c.get("classification")));
        assertEquals("MSISDN", byName.get("msisdn"));
        assertEquals("NONE", byName.get("amount"), "a clean column is carried as clean, not left blank (blank means a pre-carry snapshot)");

        // The negative control: the same data under a Dataset over a store nothing classifies carries NONE everywhere.
        ExchangeSnapshotWriter.publish(shared, "finance", registry, dataRoot, views, "plain")
                .columns().forEach(c -> assertEquals("NONE", c.get("classification")));
    }

    /**
     * LINEAGE-SIBLING-NAME-TRIM-1 / BI-MASKING-SIBLING-TYPED-CLASS-1: a sibling's weaker class never overwrites the owner's own
     * (strictest wins), a sibling column name with blanks is the same column, and a sibling class outside SENSITIVE (IMEI) is
     * carried for the consumer to judge.
     */
    @Test
    void theSnapshotKeepsTheStrictestClassAndCarriesSiblingClassesOutsideSensitive(@TempDir Path spacesRoot) throws Exception {
        Path registry = spacesRoot.resolve("finance").resolve("config").resolve("registry");
        Path dataRoot = spacesRoot.resolve("finance").resolve("data");
        Files.createDirectories(dataRoot.resolve("devs"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES ('441700900101','356938035643809',1.0)) t(msisdn,imei,amount)) TO "
                    + "'" + unix(dataRoot.resolve("devs").resolve("part.parquet")) + "' (FORMAT PARQUET)");
        }
        ComponentStore reg = new ComponentStore(registry);
        reg.write("dataset", "devs", Map.of("physicalRef", "devs", "columns", java.util.List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "amount"))));
        // NEGATIVE PROBE: before the fix IMEI was dropped; trimming " msisdn " without strictest-wins lets the sibling's PII replace MSISDN
        reg.write("dataset", "devs_sib", Map.of("physicalRef", "devs", "columns", java.util.List.of(
                Map.of("name", " msisdn ", "classification", "PII"), Map.of("name", "imei", "classification", "IMEI"))));

        Map<String, Object> byName = new java.util.HashMap<>();
        ExchangeSnapshotWriter.publish(Exchange.under(spacesRoot).dir(), "finance", registry, dataRoot,
                        spacesRoot.resolve("finance").resolve("views"), "devs")
                .columns().forEach(c -> byName.put(String.valueOf(c.get("name")), c.get("classification")));
        assertEquals("MSISDN", byName.get("msisdn"), "the owner's stricter class stands");
        assertEquals("IMEI", byName.get("imei"), "a sibling class outside SENSITIVE is carried");
        assertEquals("NONE", byName.get("amount"));
    }

    @Test
    void publishThenResolveThroughGrant(@TempDir Path spacesRoot) throws Exception {
        SnapshotMeta meta = seedAndPublish(spacesRoot);
        assertEquals(2, meta.rows(), "snapshot captured both rows");
        assertFalse(meta.columns().isEmpty(), "Result Set metadata travels with the snapshot");
        // atomic pointer + version dir on disk
        Path itemDir = ExchangeSnapshots.itemDir(spacesRoot.resolve("_shared"), "finance", "tax_receipts");
        assertTrue(Files.exists(itemDir.resolve("current.toon")));
        assertTrue(Files.exists(itemDir.resolve(meta.version()).resolve("snapshot.parquet")));

        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(new Offer("dataset", "tax_receipts", "finance", "FY26", Map.of(), "a.rao", 1L));
        ShareGrant g = ex.request("dataset", "tax_receipts", "finance", "audit", "r.gupta", "audit", null);

        // Resolver mirrors the production one: consumer = calling-thread Space MDC, grant-checked.
        SharedRefResolver.install((owner, item) -> {
            String consumer = EventLog.currentSpaceId();
            if (ex.resolveForConsumer(consumer, owner, "dataset", item).isEmpty()) return Optional.empty();
            return ExchangeSnapshots.currentDir(ExchangeSnapshots.itemDir(ex.dir(), owner, item));
        });
        try {
            MDC.put(EventLog.SPACE_MDC_KEY, "audit");

            // no active grant yet ⇒ the shared ref is unusable (fail-closed → 422 at a route)
            assertThrows(IllegalArgumentException.class, () ->
                    DatasetRelation.relationSql(Map.of("physicalRef", "shared/finance/tax_receipts"), null, null));

            // approve ⇒ resolves, and the SQL reads the two snapshot rows
            ex.approve(g.id(), "a.rao");
            String sql = DatasetRelation.relationSql(
                    Map.of("physicalRef", "shared/finance/tax_receipts"), null, null);
            assertTrue(sql.contains(meta.version()), "resolves to the live version dir");
            assertEquals(2, countRows(sql));

            // revoke ⇒ closes again
            ex.revoke(g.id(), "a.rao");
            assertThrows(IllegalArgumentException.class, () ->
                    DatasetRelation.relationSql(Map.of("physicalRef", "shared/finance/tax_receipts"), null, null));
        } finally {
            MDC.remove(EventLog.SPACE_MDC_KEY);
            SharedRefResolver.install(null);
        }
    }

    private long countRows(String relationSql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM (" + relationSql + ") t")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
