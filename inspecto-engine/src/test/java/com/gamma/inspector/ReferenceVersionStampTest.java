package com.gamma.inspector;

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
                    List.of("customer_id"), "batch-42", null);

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
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b1", "store", List.of("customer_id"), "b1", null);

            // batch 2 re-delivers C1 unchanged, changes C2, adds C3
            st.execute("CREATE TABLE b2 AS SELECT * FROM (VALUES "
                    + "('C1','NA'),('C2','APAC'),('C3','SA')) t(customer_id, region)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b2", "appended", List.of("customer_id"), "b2",
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
                            List.of(), "batch-1", null));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
