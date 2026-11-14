package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.EditionFeatures;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.exec.RowShaper;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MASKING-UNION-RESIDUALS-1 at RUN: {@code transform.mask} masks by a masked Entity Type's class ({@code WALLET}, the built-in
 * {@code wallet} type), on the Dataset's own column and through a sibling Dataset's lineage, and refuses a class that masks
 * nothing in the Space. Here, not in the engine, because the Space's Entity Types reach the engine only through the
 * {@code MaskedClasses} provider this module ships.
 */
class MaskStepTypedClassTest {

    static final String WALLET = "WAL-7731-0042", PURSE = "PRS-5518-0090";

    @TempDir Path tmp;
    private File db;
    private Connection conn;
    private String priorRoot;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("mstc_");
        conn = DuckDbUtil.openConnection(db);
        priorRoot = System.getProperty("assist.write.root");
        Path root = Files.createDirectories(tmp.resolve("space").resolve("config"));
        System.setProperty("assist.write.root", root.toString());
        EditionFeatures.overrideForTest(Set.of(EditionFeatures.PII_MASK));
        Path dir = Files.createDirectories(root.resolve("registry").resolve("datasets"));
        // payments classes wallet WALLET; its sibling over the same store classes purse WALLET - lineage alone types purse
        Files.writeString(dir.resolve("payments.toon"), ConfigCodec.toToon(Map.of("name", "payments", "physicalRef", "pay_store",
                "columns", List.of(Map.of("name", "wallet", "classification", "WALLET"), Map.of("name", "id")))));
        Files.writeString(dir.resolve("payments_alt.toon"), ConfigCodec.toToon(Map.of("name", "payments_alt", "physicalRef", "pay_store",
                "columns", List.of(Map.of("name", "purse", "classification", "WALLET")))));
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES (1,'" + WALLET + "','" + PURSE + "')) t(id,wallet,purse)");
        }
    }

    @AfterEach
    void close() throws Exception {
        EditionFeatures.overrideForTest(null);
        if (priorRoot == null) System.clearProperty("assist.write.root");
        else System.setProperty("assist.write.root", priorRoot);
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    private String run(Map<String, Object> cfg) throws Exception {
        return RowShaper.shape(conn, PipelineNode.of("m", "transform.mask", cfg), "src", "m").get(0).table();
    }

    @Test
    void aMaskedTypesClassMasksItsColumnsOwnAndInherited() throws Exception {
        String out = run(Map.of("classifications", List.of("WALLET"), "dataset", "payments"));
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, wallet, purse FROM \"" + out + "\"")) {
            assertTrue(rs.next());
            assertEquals("1", rs.getString(1));
            assertEquals("****", rs.getString(2), "its own WALLET column");
            assertEquals("****", rs.getString(3), "a sibling Dataset classes purse WALLET: lineage");
        }
    }

    @Test
    void aClassThatMasksNothingInTheSpaceIsRefusedAtRun() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run(Map.of("classifications", List.of("HANDSET"), "dataset", "payments")));
        assertTrue(e.getMessage().contains("'HANDSET' masks nothing in this Space"), e.getMessage());
    }
}
