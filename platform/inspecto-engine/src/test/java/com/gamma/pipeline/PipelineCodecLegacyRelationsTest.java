package com.gamma.pipeline;

import com.gamma.pipeline.exec.BranchCommitCoordinator;
import com.gamma.pipeline.exec.BranchCommitLog;
import com.gamma.pipeline.exec.PipelineExecutor;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X5-a read alias, end to end, over a graph authored before 4.0.0 ({@code src/test/resources/legacy_relations_graph.toon}):
 * its {@code unmatched} and {@code dropped} edges, a {@code success} edge and a cross-pipeline {@code on_commit} edge.
 * It loads (the bare reject spellings read as {@code reject:<reason>}, the two retired outcome edges are dropped),
 * validates with one {@link PipelineValidator#RETIRED_RELATION} warning per dropped edge and no error, runs (the
 * filter's rejected row reaches the sink its old {@code dropped} edge named), and saves with the new names only.
 */
class PipelineCodecLegacyRelationsTest {

    private static final Path FIXTURE = Path.of("src/test/resources/legacy_relations_graph.toon");

    @Test
    void aPre400GraphLoadsValidatesWithAWarningRunsAndSavesWithTheNewNames(@TempDir Path dir) throws Exception {
        PipelineStore store = new PipelineStore(dir.resolve("pipelines"));
        Files.createDirectories(store.root());
        Files.copy(FIXTURE, store.root().resolve("legacy_relations.toon"));

        // ── loads ──
        PipelineCodec.Decoded decoded = PipelineCodec.decode(ToonHelper.load(store.root().resolve("legacy_relations.toon").toString()));
        PipelineGraph g = decoded.graph();
        assertEquals(List.of("parse|data|flt", "parse|reject:unmatched|quarantine", "flt|data|out", "flt|reject:dropped|rejects"),
                g.edges().stream().map(e -> e.from() + "|" + e.rel() + "|" + e.to()).toList());
        assertEquals(g, store.get("legacy_relations").orElseThrow(), "the store's load path decodes the same graph");

        // ── validates with a warning ──
        assertEquals(2, decoded.warnings().size(), "" + decoded.warnings());
        for (PipelineValidator.Issue w : decoded.warnings()) {
            assertEquals(PipelineValidator.Severity.WARNING, w.severity());
            assertEquals(PipelineValidator.RETIRED_RELATION, w.code());
        }
        assertTrue(decoded.warnings().get(0).message().contains("'success'"), decoded.warnings().get(0).message());
        assertTrue(decoded.warnings().get(1).message().contains("'on_commit'"), decoded.warnings().get(1).message());
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(r.ok(), () -> "the legacy graph must validate: " + r.issues());

        // ── runs ──
        File db = DuckDbUtil.tempDbFile("legacy_rel_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE parsed AS SELECT * FROM (VALUES (1,150),(2,50),(3,200)) t(id,amt)");
            }
            List<String> written = new ArrayList<>();
            PipelineExecutor.ExecResult res = PipelineExecutor.execute(conn, g, "parse", "parsed", "b-legacy",
                    new BranchCommitCoordinator(new BranchCommitLog(dir.resolve("branch_commit.csv").toString())),
                    (sink, table) -> written.add(sink.id()), () -> {});
            assertEquals(List.of(1, 3), ids(conn, res.sinkInputs().get("out")));
            assertEquals(List.of(2), ids(conn, res.sinkInputs().get("rejects")),
                    "the row the filter rejected reaches the sink the old 'dropped' edge named");
            assertTrue(written.containsAll(List.of("out", "rejects")), "" + written);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }

        // ── saves with the new names ──
        store.write("legacy_relations", g);
        String saved = Files.readString(store.root().resolve("legacy_relations.toon"));
        for (String retired : List.of("success", "on_commit", ",dropped,", ",unmatched,"))
            assertFalse(saved.contains(retired), "'" + retired + "' is gone from the saved file:\n" + saved);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) ToonHelper
                .load(store.root().resolve("legacy_relations.toon").toString()).get("edges");
        assertEquals(List.of("data", "reject:unmatched", "data", "reject:dropped"),
                edges.stream().map(e -> String.valueOf(e.get("rel"))).toList());
        PipelineCodec.Decoded again = PipelineCodec.decode(ToonHelper.load(store.root().resolve("legacy_relations.toon").toString()));
        assertEquals(g, again.graph(), "the saved file reads back as the same graph");
        assertTrue(again.warnings().isEmpty(), "a saved graph carries no retired edge any more");
    }

    @Test
    void canonicalMapsTheFourLegacyRejectSpellingsAndPassesEverythingElseThrough() {
        assertEquals("reject:dropped", PipelineRel.canonical("dropped"));
        assertEquals("reject:invalid", PipelineRel.canonical("invalid"));
        assertEquals("reject:duplicate", PipelineRel.canonical("duplicate"));
        assertEquals("reject:unmatched", PipelineRel.canonical("unmatched"));
        for (String same : List.of("data", "gap", "route:emea", "reject:dropped", "reject:bad_score", "success", "Dropped"))
            assertEquals(same, PipelineRel.canonical(same), same);
        assertNull(PipelineRel.canonical(null));

        assertEquals("dropped", PipelineRel.reason(PipelineRel.DROPPED));
        assertEquals("bad_score", PipelineRel.reason(PipelineRel.reject("bad_score")));
        assertNull(PipelineRel.reason(PipelineRel.DATA));
        assertNull(PipelineRel.reason("reject:"), "an empty reason is not a reject outlet");
        assertNull(PipelineRel.reason("dropped"), "a legacy spelling is not canonical — map it first");
    }

    @Test
    void edgeFromMapReadsTheLegacySpellingCanonically() {
        assertEquals(new PipelineEdge("p", "reject:unmatched", "q"),
                PipelineCodec.edgeFromMap(Map.of("from", "p", "rel", "unmatched", "to", "q")));
    }

    private static List<Integer> ids(Connection conn, String table) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM \"" + table + "\" ORDER BY id")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }
}
