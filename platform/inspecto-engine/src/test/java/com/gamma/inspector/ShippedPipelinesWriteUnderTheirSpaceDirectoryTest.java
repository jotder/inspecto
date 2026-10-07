package com.gamma.inspector;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A shipped Pipeline's DATA lands under its own Space directory, wherever the process was launched from
 * ({@code DATA-DIRS-RESOLVE-AGAINST-CWD-1}, operator decision 2026-09-23: a relative data path resolves under
 * {@code spaces/<id>/}, so configs say {@code data/orders/…}).
 *
 * <p>The defect: every shipped config spelled its data paths from the server root
 * ({@code dirs.poll: spaces/demo/data/inbox/orders}) and they resolved against the process working directory.
 * A bundle launched from {@code inspecto-deploy/} therefore wrote its status/data dirs under the LAUNCH dir,
 * and a test loading a shipped config wrote stray {@code spaces/}, {@code out/}, {@code templates/} trees under
 * the module dir — which fooled repo-root walkers like {@code MappingMigrationTest}.
 *
 * <p>⚠ No CWD trickery is needed: surefire's working directory is the MODULE dir ({@code inspecto-engine/}),
 * never the Space's. Each Space is copied VERBATIM into a temp {@code spaces/} tree — nothing is rewritten, which
 * is the point: the copy is only correct if a relative data path means "under my Space".
 */
class ShippedPipelinesWriteUnderTheirSpaceDirectoryTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    /** Runtime state (gitignored) — not shipped. {@code _templates} IS shipped and must resolve the same way. */
    private static final Set<String> NOT_SHIPPED_SPACES = Set.of("_shared", "uat");

    @Test
    void theWorkingDirectoryIsNotASpace() {
        // The premise: from a Space dir as CWD the old reading and the new one coincide, and this passes vacuously.
        assertFalse(Files.isDirectory(Path.of("data")), "the test JVM's CWD must not hold a data/ tree");
        assertFalse(Files.isDirectory(Path.of("spaces")), "the test JVM's CWD must not hold a spaces/ tree");
    }

    /** The orders demo, RUN end to end from a relocated copy: inbox, output, status all inside that copy. */
    @Test
    void theOrdersDemoIngestsIntoItsOwnSpace(@TempDir Path tmp) throws Exception {
        Path space = copySpace("demo", tmp.resolve("spaces"));
        Path data = space.resolve("data");
        PipelineConfig cfg = PipelineConfig.load(space.resolve("config/orders/orders_pipeline.toon").toString());

        Path inbox = Path.of(cfg.dirs().poll());
        assertEquals(data.resolve("inbox/orders"), inbox, "dirs.poll: data/inbox/orders means THIS Space's inbox");
        Files.createDirectories(inbox);
        try (Stream<Path> samples = Files.list(REPO.resolve("spaces/demo/data/samples/orders"))) {
            for (Path f : samples.filter(p -> p.toString().endsWith(".csv")).toList())
                Files.copy(f, inbox.resolve(f.getFileName()));
        }

        CollectorProcessor.run(cfg);

        Path db = data.resolve("orders/database");
        assertTrue(rows(db) > 0, "the run wrote its Parquet under the Space's data/orders/database");
        assertTrue(Files.isDirectory(data.resolve("orders/status")), "and its status dir beside it");
        assertFalse(Files.exists(Path.of("data")), "nothing may land under the working directory");
    }

    /** Every shipped Pipeline, Enrichment and local Connection: each data path resolves under its Space dir. */
    @Test
    void everyShippedDataPathResolvesUnderItsSpace(@TempDir Path tmp) throws Exception {
        List<String> failures = new ArrayList<>();
        int pipelines = 0, enrichments = 0, connections = 0;
        try (Stream<Path> spaces = Files.list(REPO.resolve("spaces"))) {
            for (Path src : spaces.filter(Files::isDirectory).toList()) {
                String name = src.getFileName().toString();
                if (NOT_SHIPPED_SPACES.contains(name) || !Files.isDirectory(src.resolve("config"))) continue;
                Path space = copySpace(name, tmp.resolve("spaces"));
                try (Stream<Path> all = Files.walk(space.resolve("config"))) {
                    for (Path f : all.filter(Files::isRegularFile).toList()) {
                        String file = f.getFileName().toString();
                        try {
                            if (file.endsWith("_pipeline.toon") && !template(f)) {
                                pipelines++;
                                PipelineConfig c = PipelineConfig.load(f.toString());
                                PipelineConfig.Dirs d = c.dirs();
                                for (String p : new String[]{d.poll(), d.database(), d.backup(), d.temp(), d.errors(),
                                        d.quarantine(), d.markers(), d.logDir(), d.statusFilePath()})
                                    under(space, p, space.relativize(f) + " dirs", failures);
                                for (PipelineConfig.Sink s : c.sinks())
                                    under(space, s.database(), space.relativize(f) + " sinks[].database", failures);
                            } else if (file.endsWith("_enrich.toon")) {
                                enrichments++;
                                EnrichmentConfig e = EnrichmentConfig.load(f.toString());
                                under(space, e.input().database(), space.relativize(f) + " input.database", failures);
                                under(space, e.output().database(), space.relativize(f) + " output.database", failures);
                                for (EnrichmentConfig.Reference r : e.references())
                                    if (r.path() != null)
                                        under(space, r.path(), space.relativize(f) + " references.path", failures);
                            } else if (file.endsWith("_connection.toon")) {
                                ConnectionProfile p = ConnectionProfile.load(f);
                                String authored = Files.readString(f);
                                if (!"local".equals(p.connector()) || p.basePath() == null
                                        || authored.contains("base_path: /"))
                                    continue;   // a remote path, or an absolute one the author chose
                                connections++;
                                under(space, p.basePath(), space.relativize(f) + " base_path", failures);
                            }
                        } catch (Exception | Error e) {
                            failures.add(space.relativize(f) + ": " + e);
                        }
                    }
                }
            }
        }
        assertTrue(pipelines >= 30 && enrichments >= 1 && connections >= 1, "the walk found only " + pipelines
                + " pipelines, " + enrichments + " enrichments, " + connections + " local connections");
        assertTrue(failures.isEmpty(), failures.size() + " data path(s) do not resolve under their Space:\n"
                + String.join("\n", failures));
        assertFalse(Files.exists(Path.of("data")), "loading may not create anything under the working directory");
    }

    private static void under(Path space, String value, String what, List<String> failures) {
        if (value == null) return;
        Path p = Path.of(value);
        if (!p.isAbsolute() || !p.normalize().startsWith(space.resolve("data")))
            failures.add(what + ": '" + value + "' is not under " + space.resolve("data"));
    }

    /** A template pipeline ({@code template: true}) is not loadable by design. */
    private static boolean template(Path f) throws Exception {
        return Files.readAllLines(f).stream().anyMatch(l -> l.strip().equals("template: true"));
    }

    /** Copy a shipped Space's {@code config/} tree verbatim — no path rewriting of any kind. */
    private static Path copySpace(String name, Path into) throws Exception {
        Path src = REPO.resolve("spaces").resolve(name);
        Path dest = into.resolve(name);
        try (Stream<Path> all = Files.walk(src.resolve("config"))) {
            for (Path f : all.filter(Files::isRegularFile).toList()) {
                Path to = dest.resolve(src.relativize(f));
                Files.createDirectories(to.getParent());
                Files.copy(f, to);
            }
        }
        return dest;
    }

    private static long rows(Path root) throws Exception {
        assertTrue(Files.isDirectory(root), "no output written under " + root);
        String glob = root.toString().replace('\\', '/') + "/**/*.parquet";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM read_parquet('" + glob + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
