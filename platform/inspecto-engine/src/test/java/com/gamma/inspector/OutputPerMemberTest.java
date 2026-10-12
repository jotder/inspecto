package com.gamma.inspector;

import com.gamma.etl.Consignment;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INGEST-MULTIFILE-BATCH-REPLAN-DUP-1 stage 2: {@code processing.output_per_member}. On, every member of a batch
 * writes its OWN output file named by the member alone ({@code <stem>__<origin hash>_out}), so a re-planned batch
 * (a different member set, hence a different batch id) rewrites the same files instead of adding a second copy.
 * Off, the naming is today's: a multi-member batch is named by its batch id, a lone survivor by its stem.
 */
class OutputPerMemberTest {

    private static final String ON = "  output_per_member: true";

    private static PipelineConfig cfg(Path dir, String section) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, section).toString());
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        return cfg;
    }

    private static File inbox(PipelineConfig cfg, String rel) throws Exception {
        Path f = Path.of(cfg.dirs().poll()).resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        return f.toFile();
    }

    /** The batch's members, srcId = position, in the order given. */
    private static List<Consignment.Member> members(PipelineConfig cfg, File... files) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        java.util.ArrayList<Consignment.Member> ms = new java.util.ArrayList<>();
        for (int i = 0; i < files.length; i++) ms.add(new Consignment.Member(files[i], i, files[i].length(), sel));
        return ms;
    }

    /** One row per member (name = the member's file name), all in one partition; writes through the real seam. */
    private static void write(PipelineConfig cfg, String batchId, List<Consignment.Member> ms) throws Exception {
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("per_member_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            StringBuilder values = new StringBuilder();
            Map<Integer, String> srcIdToFile = new LinkedHashMap<>();
            for (Consignment.Member m : ms) {
                if (values.length() > 0) values.append(", ");
                values.append("('").append(m.file().getParentFile().getName()).append('/').append(m.file().getName())
                        .append("', 1.0, '2026', '07', '01', ").append(m.srcId()).append(')');
                srcIdToFile.put(m.srcId(), m.file().getName());
            }
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " + values
                        + ") v(name, cost, year, month, day, __src_id)");
            }
            Consignment batch = new Consignment(batchId, "stg", null, ms);
            ConsignmentIngestStrategy.Written w = ConsignmentIngestStrategy.writeAndTraceEach(
                    conn, "transformed", List.of("year", "month", "day"), cfg, cfg.dirs().database(),
                    ms, batch, srcIdToFile, "", null);
            // the lineage of each output file names exactly one source file when written per member
            if (cfg.outputPerMember() && ms.size() > 1)
                for (var o : w.outputs())
                    assertEquals(1, w.lineage().stream().filter(l -> l.outputFile().equals(o.outputFile())).count(),
                            "one srcId per output file: " + o.outputFile());
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static TreeSet<String> names(PipelineConfig cfg) throws Exception {
        Path db = Path.of(cfg.dirs().database());
        TreeSet<String> out = new TreeSet<>();
        if (!Files.exists(db)) return out;
        try (Stream<Path> s = Files.walk(db)) {
            s.filter(Files::isRegularFile).forEach(p -> out.add(p.getFileName().toString()));
        }
        return out;
    }

    private static long rows(PipelineConfig cfg) throws Exception {
        try (Stream<Path> walk = Files.walk(Path.of(cfg.dirs().database()))) {
            long total = 0;
            for (Path p : walk.filter(Files::isRegularFile).toList())
                total += Files.readAllLines(p).size() - 1;   // CSV output: one header line per file
            return total;
        }
    }

    @Test
    void twoMembersWriteTwoFilesWithStableMemberNames(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, ON);
        File a = inbox(cfg, "a.csv"), b = inbox(cfg, "b.csv");
        write(cfg, "batch_1", members(cfg, a, b));
        TreeSet<String> first = names(cfg);
        assertEquals(2, first.size(), "one file per member: " + first);
        assertTrue(first.stream().anyMatch(n -> n.startsWith("a__")), first.toString());
        assertTrue(first.stream().anyMatch(n -> n.startsWith("b__")), first.toString());
        assertTrue(first.stream().noneMatch(n -> n.contains("batch_1")), "never the batch id: " + first);

        // a survivor re-plans {b} alone under a different batch id (and a different srcId): b's SAME file is rewritten
        write(cfg, "batch_2", members(cfg, b));
        assertEquals(first, names(cfg), "a re-plan with a different member set rewrites the member's own file");
        assertEquals(2, rows(cfg), "no second copy of any member");

        // and a plan of both again under a third id still lands on the same two files
        write(cfg, "batch_3", members(cfg, a, b));
        assertEquals(first, names(cfg));
        assertEquals(2, rows(cfg));
    }

    @Test
    void sameStemInAnotherDirectoryDoesNotCollide(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, ON);
        File ax = inbox(cfg, "a/x.csv"), bx = inbox(cfg, "b/x.csv");
        write(cfg, "batch_1", members(cfg, ax, bx));
        assertEquals(2, names(cfg).size(), "same stem, different origin = two files: " + names(cfg));
        assertEquals(2, rows(cfg));

        // the cross-batch residual: b/x.csv again in a LATER batch rewrites its own file and never a/x's
        write(cfg, "batch_2", members(cfg, bx));
        assertEquals(2, names(cfg).size());
        assertEquals(2, rows(cfg), "a/x.csv's output survives a later batch holding b/x.csv");
        // and the hash is of the path, never the raw path
        assertTrue(names(cfg).stream().allMatch(n -> n.matches("x__[0-9a-f]{8}_out.*")), names(cfg).toString());
    }

    @Test
    void offNamingIsUnchanged(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, "");
        assertFalse(cfg.outputPerMember());
        File a = inbox(cfg, "a.csv"), b = inbox(cfg, "b.csv");
        write(cfg, "batch_1", members(cfg, a, b));
        assertEquals(1, names(cfg).size(), "multi-member batch: one file named by the batch id: " + names(cfg));
        assertTrue(names(cfg).first().startsWith("batch_1_out"), names(cfg).toString());

        write(cfg, "batch_2", members(cfg, a));
        assertTrue(names(cfg).contains("a_out.csv"), "a lone survivor keeps its plain stem: " + names(cfg));
        assertTrue(names(cfg).stream().noneMatch(n -> n.contains("__")), names(cfg).toString());
    }
}
