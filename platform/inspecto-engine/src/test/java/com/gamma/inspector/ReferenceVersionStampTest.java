package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reference Phase-2 P1 write-side verify: {@link ConsignmentIngestStrategy#stampReferenceVersions} appends
 * the §2.1 system columns ({@code __key_hash}/{@code __valid_from}/{@code __op}/{@code __batch_id}) and
 * folds out within-batch key duplicates (one version per key per batch).
 */
class ReferenceVersionStampTest {

    @Test
    void stampsSystemColumnsAndFoldsWithinBatchDuplicates() throws Exception {
        File db = DuckDbUtil.tempDbFile("stamp_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            // A batch delivering C1 twice (a within-batch duplicate) plus C2 once.
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES "
                    + "('C1','NA'),('C1','NA'),('C2','EU')) t(customer_id, region)");

            ConsignmentIngestStrategy.stampReferenceVersions(c, "transformed", "__ref_versioned",
                    ref(List.of("customer_id")), "batch-42", null);

            // within-batch dedup: C1 collapses to one version → two rows total
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM __ref_versioned")) {
                assertTrue(rs.next());
                assertEquals(2L, rs.getLong(1), "within-batch duplicate key folded to one version");
            }
            // one row per distinct key, each stamped upsert with this batch id
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(DISTINCT __key_hash) AS keys, "
                    + "COUNT(*) FILTER (WHERE __op='upsert') AS upserts, "
                    + "COUNT(*) FILTER (WHERE __batch_id='batch-42') AS tagged, "
                    + "COUNT(*) FILTER (WHERE __valid_from IS NOT NULL) AS stamped "
                    + "FROM __ref_versioned")) {
                assertTrue(rs.next());
                assertEquals(2L, rs.getLong("keys"), "distinct __key_hash per key");
                assertEquals(2L, rs.getLong("upserts"), "ingest path stamps __op = upsert");
                assertEquals(2L, rs.getLong("tagged"), "every row carries the batch id");
                assertEquals(2L, rs.getLong("stamped"), "every row carries a __valid_from");
            }
            // P2: a payload hash per distinct payload — C1/C2 differ, so two distinct __row_hash values
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(DISTINCT __row_hash) FROM __ref_versioned")) {
                assertTrue(rs.next());
                assertEquals(2L, rs.getLong(1), "distinct __row_hash per distinct payload");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * Reference Phase-2 P2 unchanged-row skip: a re-delivered row identical to its key's current version
     * in the existing store writes <b>no</b> new version; a changed payload and a brand-new key still do.
     */
    @Test
    void identicalRedeliveryAddsNoVersion() throws Exception {
        File db = DuckDbUtil.tempDbFile("skip_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            // batch 1 → the "existing store": C1→NA, C2→EU
            st.execute("CREATE TABLE b1 AS SELECT * FROM (VALUES ('C1','NA'),('C2','EU')) t(customer_id, region)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b1", "store", ref(List.of("customer_id")), "b1", null);

            // batch 2 re-delivers C1 unchanged, changes C2, adds C3
            st.execute("CREATE TABLE b2 AS SELECT * FROM (VALUES "
                    + "('C1','NA'),('C2','APAC'),('C3','SA')) t(customer_id, region)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b2", "appended", ref(List.of("customer_id")), "b2",
                    "(SELECT * FROM store) AS _store");

            try (ResultSet rs = st.executeQuery(
                    "SELECT customer_id FROM appended ORDER BY customer_id")) {
                java.util.List<String> kept = new java.util.ArrayList<>();
                while (rs.next()) kept.add(rs.getString(1));
                assertEquals(List.of("C2", "C3"), kept,
                        "unchanged C1 skipped; changed C2 and new C3 append a version");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void emptyKeyIsRejected() throws Exception {
        File db = DuckDbUtil.tempDbFile("stamp_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES ('C1')) t(customer_id)");
            assertThrows(IllegalStateException.class, () ->
                    ConsignmentIngestStrategy.stampReferenceVersions(c, "transformed", "__ref_versioned",
                            ref(List.of()), "batch-1", null));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── D5-ref: the delete marker column · D6-ref: the order_by tie-break ─────────────────────

    /** An upsert Reference keyed on {@code keyCols}, no delete marker, no order_by. */
    private static PipelineConfig.Reference ref(List<String> keyCols) {
        return new PipelineConfig.Reference(keyCols, PipelineConfig.Load.UPSERT, 0, null, null);
    }

    /** An upsert Reference keyed on customer_id with a delete marker and/or an order_by column. */
    private static PipelineConfig.Reference ref(PipelineConfig.Reference.Delete delete, String orderBy) {
        return new PipelineConfig.Reference(List.of("customer_id"), PipelineConfig.Load.UPSERT, 0, delete, orderBy);
    }

    private static final PipelineConfig.Reference.Delete OP_D =
            new PipelineConfig.Reference.Delete("op", List.of("D"));

    /** {@code customer_id:__op} of every row in {@code table}, ordered by key. */
    private static List<String> ops(Statement st, String table) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT customer_id, __op FROM " + table + " ORDER BY customer_id")) {
            while (rs.next()) out.add(rs.getString(1) + ":" + rs.getString(2));
        }
        return out;
    }

    private static List<String> columns(Statement st, String table) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getMetaData().getColumnName(i));
        }
        return out;
    }

    /**
     * A row carrying the marker value becomes a {@code 'delete'} tombstone — even when its payload is
     * identical to the key's current version (the unchanged-row skip must not swallow it) — and the
     * marker column itself is never persisted, nor part of the payload hash.
     */
    @Test
    void aMarkedRowWritesATombstoneAndTheMarkerColumnIsNotPersisted() throws Exception {
        File db = DuckDbUtil.tempDbFile("del_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE b1 AS SELECT * FROM (VALUES ('C1','NA'),('C2','EU')) t(customer_id, region)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b1", "store", ref(List.of("customer_id")), "b1", null);

            // C1 deleted (same payload as its current version), C2 re-delivered unchanged, C3 new
            st.execute("CREATE TABLE b2 AS SELECT * FROM (VALUES "
                    + "('C1','NA','D'),('C2','EU','U'),('C3','SA',NULL)) t(customer_id, region, op)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b2", "appended", ref(OP_D, null), "b2",
                    "(SELECT * FROM store) AS _store");

            assertEquals(List.of("C1:delete", "C3:upsert"), ops(st, "appended"),
                    "C1 tombstoned despite an identical payload; unchanged C2 skipped (marker not hashed); C3 upserted");
            assertFalse(columns(st, "appended").contains("op"), "the marker column is not persisted as data");
            assertTrue(columns(st, "appended").contains("region"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** A delete for a key with no live version appends nothing — a re-delivered delete feed is idempotent. */
    @Test
    void aDeleteOfAKeyThatIsNotLiveAppendsNothing() throws Exception {
        File db = DuckDbUtil.tempDbFile("del_absent_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            // first batch, empty store: a delete has nothing to remove
            st.execute("CREATE TABLE b1 AS SELECT * FROM (VALUES ('C1','NA','D'),('C2','EU','U')) t(customer_id, region, op)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b1", "store", ref(OP_D, null), "b1", null);
            assertEquals(List.of("C2:upsert"), ops(st, "store"));

            // C9 never existed; C1 was never live — neither appends a tombstone
            st.execute("CREATE TABLE b2 AS SELECT * FROM (VALUES ('C9','X','D'),('C1','NA','D')) t(customer_id, region, op)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b2", "appended", ref(OP_D, null), "b2",
                    "(SELECT * FROM store) AS _store");
            assertEquals(List.of(), ops(st, "appended"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** A non-VARCHAR marker matches by its text form — {@code deleted: true} with {@code values: [true]}. */
    @Test
    void aBooleanMarkerMatchesByItsTextForm() throws Exception {
        File db = DuckDbUtil.tempDbFile("del_bool_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE b1 AS SELECT * FROM (VALUES ('C1','NA'),('C2','EU')) t(customer_id, region)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b1", "store", ref(List.of("customer_id")), "b1", null);
            st.execute("CREATE TABLE b2 AS SELECT * FROM (VALUES ('C1','NA',true),('C2','APAC',false)) "
                    + "t(customer_id, region, deleted)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b2", "appended",
                    ref(new PipelineConfig.Reference.Delete("deleted", List.of("true")), null), "b2",
                    "(SELECT * FROM store) AS _store");
            assertEquals(List.of("C1:delete", "C2:upsert"), ops(st, "appended"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** D6-ref: with order_by the within-batch winner is the greatest order_by value, whatever the row order. */
    @Test
    void orderByPicksTheLatestVersionWithinABatchRegardlessOfRowOrder() throws Exception {
        File db = DuckDbUtil.tempDbFile("order_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            String[] orders = {
                    "('C1','NA',1),('C1','EU',3),('C1','APAC',2)",
                    "('C1','EU',3),('C1','APAC',2),('C1','NA',1)",
                    "('C1','APAC',2),('C1','NA',1),('C1','EU',3)",
                    "('C1','NA',NULL),('C1','EU',3)"};   // a NULL order_by never beats a value
            for (int i = 0; i < orders.length; i++) {
                st.execute("CREATE TABLE o" + i + " AS SELECT * FROM (VALUES " + orders[i] + ") t(customer_id, region, ts)");
                ConsignmentIngestStrategy.stampReferenceVersions(c, "o" + i, "v" + i, ref(null, "ts"), "b" + i, null);
                try (ResultSet rs = st.executeQuery("SELECT region, ts FROM v" + i)) {
                    assertTrue(rs.next());
                    assertEquals("EU", rs.getString(1), "latest ts wins, input order " + i);
                    assertEquals(3, rs.getInt(2), "the order_by column is ordinary payload — it IS persisted");
                    assertFalse(rs.next(), "one version per key");
                }
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Deletes take part in the ordering: the latest of an upsert/delete pair for one key decides. */
    @Test
    void deletesParticipateInTheOrdering() throws Exception {
        File db = DuckDbUtil.tempDbFile("order_del_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE b1 AS SELECT * FROM (VALUES ('C1','NA',0),('C2','EU',0)) t(customer_id, region, ts)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b1", "store", ref(List.of("customer_id")), "b1", null);

            // C1: upsert@1 then delete@2 → tombstone.  C2: delete@1 then upsert@2 → the upsert (APAC) survives.
            st.execute("CREATE TABLE b2 AS SELECT * FROM (VALUES "
                    + "('C1','SA','U',1),('C1','SA','D',2),('C2','APAC','U',2),('C2','EU','D',1)) "
                    + "t(customer_id, region, op, ts)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b2", "appended", ref(OP_D, "ts"), "b2",
                    "(SELECT * FROM store) AS _store");
            assertEquals(List.of("C1:delete", "C2:upsert"), ops(st, "appended"));
            try (ResultSet rs = st.executeQuery("SELECT region FROM appended WHERE customer_id = 'C2'")) {
                assertTrue(rs.next());
                assertEquals("APAC", rs.getString(1));
            }

            // a tie on order_by resolves to the delete — deterministic, never input-order dependent
            st.execute("CREATE TABLE b3 AS SELECT * FROM (VALUES ('C2','ZZ','U',5),('C2','ZZ','D',5)) "
                    + "t(customer_id, region, op, ts)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b3", "tie", ref(OP_D, "ts"), "b3",
                    "(SELECT * FROM store) AS _store");
            assertEquals(List.of("C2:delete"), ops(st, "tie"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
