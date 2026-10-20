package com.gamma.la.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LA-DAILY-INGEST-1, the live Job-versus-build race: a day that LANDS while a FULL build runs. The relation of a plain store
 * Dataset is a glob that every statement of the build re-expands, so before the pin a landing day was read by some statements
 * and not others (the verification failed the build) or by all of them while the manifest, which records the listing taken at
 * submit, did not list it (the next append read it again and counted its rows twice). The landing is driven deterministically
 * from the build's own progress callback.
 */
class IndexFullPinTest {

    private static final String DS = "ds";
    private static final IndexMapping MAPPING = new IndexMapping("s", "t", null, null, null, null, List.of());

    private static void land(Path dir, String name, String prefix) throws Exception {
        Files.createDirectories(dir);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('" + prefix + "1','" + prefix + "2'),('" + prefix + "2','" + prefix + "3'),('"
                    + prefix + "1','" + prefix + "3')) v(s,t)) TO '" + sql(dir.resolve(name)) + "' (FORMAT parquet)");
        }
    }

    private static String sql(Path p) {
        return p.toAbsolutePath().toString().replace(java.io.File.separatorChar, '/');
    }

    private static List<IndexManifest.InputFile> stamps(Path dir, String... names) throws Exception {
        java.util.ArrayList<IndexManifest.InputFile> out = new java.util.ArrayList<>();
        for (String n : names) out.add(new IndexManifest.InputFile(n, Files.size(dir.resolve(n)), Files.getLastModifiedTime(dir.resolve(n)).toMillis()));
        return out;
    }

    private static final Function<Path, String> GLOB = d -> "SELECT * FROM read_parquet('" + sql(d) + "/*.parquet')";

    private static Function<List<String>, String> list(Path d) {
        return files -> "SELECT * FROM read_parquet([" + String.join(", ", files.stream().map(f -> "'" + sql(d.resolve(f)) + "'").toList()) + "])";
    }

    private IndexBuilder.Result build(Path root, Path data, IndexBuilder.Mode mode, List<IndexManifest.InputFile> listing,
                                      Function<List<String>, String> delta, Consumer<IndexBuilder.Progress> progress) {
        return IndexBuilder.build(new IndexBuilder.Request(DS, MAPPING, GLOB.apply(data), new IndexStore(root, DS, MAPPING.hash()), "fp",
                new IndexBuilder.Options(null, null, null, null, progress), listing, mode, delta, List.of(data)));
    }

    private static long out(IndexBuilder.Result r) {
        return r.manifest().tables().get("out").rows();
    }

    /** A day lands before the build first reads: the FULL holds only the listed day and the next append adds the landed one ONCE. */
    @Test
    void aDayLandingAfterTheListingIsNotReadByTheFullAndTheAppendCountsItOnce(@TempDir Path root, @TempDir Path data) throws Exception {
        land(data, "day0.parquet", "a");
        var listing = stamps(data, "day0.parquet");
        IndexBuilder.Result full = build(root, data, IndexBuilder.Mode.FULL, listing, list(data), p -> {
            if ("estimate".equals(p.phase())) {
                try {
                    land(data, "day1.parquet", "b");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        assertEquals(3L, out(full), "the FULL holds exactly the files its manifest lists");
        assertEquals(List.of("day0.parquet"), full.manifest().inputFiles().stream().map(IndexManifest.InputFile::path).toList());

        IndexBuilder.Result appended = build(root, data, IndexBuilder.Mode.APPEND, stamps(data, "day0.parquet", "day1.parquet"), list(data), null);
        assertEquals(6L, out(appended), "the landed day is added once, not twice");
    }

    /** A day lands BETWEEN the count and the copy of a FULL: without the pin the build fails its own verification. */
    @Test
    void aDayLandingBetweenTheCountAndTheCopyDoesNotFailTheFull(@TempDir Path root, @TempDir Path data) throws Exception {
        land(data, "day0.parquet", "a");
        var listing = stamps(data, "day0.parquet");
        IndexBuilder.Result full = build(root, data, IndexBuilder.Mode.FULL, listing, list(data), p -> {
            if ("out".equals(p.phase())) {
                try {
                    land(data, "day1.parquet", "b");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        assertEquals(3L, out(full));
    }

    /**
     * The relation and the listing are two reads taken a moment apart (the scheduled run renders the relation, then the service
     * lists the inputs at submit). A day landing between them is in the manifest's listing but not in the relation: unpinned, the
     * index silently lacks a day its manifest claims, and the plan then calls it up to date.
     */
    @Test
    void aDayListedAfterTheRelationWasRenderedIsStillReadByTheFull(@TempDir Path root, @TempDir Path data) throws Exception {
        land(data, "day0.parquet", "a");
        String renderedEarly = list(data).apply(List.of("day0.parquet"));
        land(data, "day1.parquet", "b");
        var listing = stamps(data, "day0.parquet", "day1.parquet");
        IndexBuilder.Result full = IndexBuilder.build(new IndexBuilder.Request(DS, MAPPING, renderedEarly,
                new IndexStore(root, DS, MAPPING.hash()), "fp", IndexBuilder.Options.defaults(), listing, IndexBuilder.Mode.FULL,
                list(data), List.of(data)));
        assertEquals(6L, out(full), "the index holds every file its manifest lists");
    }

    /** A Dataset that cannot render a file list (no deltaSql) keeps reading its relation as given. */
    @Test
    void withoutAListRendererTheRelationIsReadAsGiven(@TempDir Path root, @TempDir Path data) throws Exception {
        land(data, "day0.parquet", "a");
        land(data, "day1.parquet", "b");
        IndexBuilder.Result full = build(root, data, IndexBuilder.Mode.FULL, stamps(data, "day0.parquet"), null, null);
        assertEquals(6L, out(full));
    }
}
