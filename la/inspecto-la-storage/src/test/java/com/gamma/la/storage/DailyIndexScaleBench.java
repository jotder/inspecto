package com.gamma.la.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * LA-DAILY-INGEST-1 follow-up (scale): the INDEX half of the daily chain, over the partitioned Parquet Dataset that
 * {@code LaDailyIngestScaleBench} (inspecto-engine) wrote through the real ingest path. What the {@code la.index.build} Job
 * runs: {@code FULL} on a first build, then {@code APPEND} when a new day's files have landed. Each phase is its own JVM so
 * the peak working set it prints belongs to that phase alone. Never runs in the default suite (not {@code *Test}, tagged,
 * gated).
 *
 * <p>Needs {@code -Dinspecto.bench.dir=<dir>} (outside the repo). Knobs {@code inspecto.bench.*}: {@code label} (the ingest
 * folder {@code ingest-<label>/database} to index), {@code phase} ({@code full} | {@code append}), {@code store} (index
 * folder tag, default {@code main}: a FULL over the whole Dataset into a second tag is the "full rebuild" comparison to an
 * {@code append} on {@code main}), {@code buildMemory} (default {@code default} = no limit set, as the Job's service builds,
 * or e.g. {@code 8GB}). Mapping as a daily-feed Index owner would set it: source MSISDN, target OTHER_PARTY, kind REC_TYPE,
 * time START_AT. Results print as {@code IDX ...} lines.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class DailyIndexScaleBench {

    private static final String DS = "daily_xdr";
    private static final IndexMapping M = new IndexMapping("MSISDN", "OTHER_PARTY", "REC_TYPE", "START_AT", null, null, List.of());

    private static String sql(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private static String relation(Path dir, List<String> files) {
        StringBuilder sb = new StringBuilder("SELECT * FROM read_parquet([");
        for (int i = 0; i < files.size(); i++) sb.append(i == 0 ? "'" : ", '").append(sql(dir.resolve(files.get(i)))).append('\'');
        return sb.append("])").toString();
    }

    private static List<IndexManifest.InputFile> stamps(Path dir, List<String> files) throws Exception {
        List<IndexManifest.InputFile> out = new ArrayList<>();
        for (String f : files)
            out.add(new IndexManifest.InputFile(f, Files.size(dir.resolve(f)), Files.getLastModifiedTime(dir.resolve(f)).toMillis()));
        return out;
    }

    private static long dirBytes(Path d) throws Exception {
        try (Stream<Path> w = Files.walk(d)) {
            return w.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }

    private static List<String> parquet(Path db) throws Exception {
        try (Stream<Path> w = Files.walk(db)) {
            return w.filter(p -> p.toString().endsWith(".parquet") && !p.toString().contains(".staging"))
                    .map(p -> db.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    /** Peak working set of THIS JVM so far (DuckDB's native memory included), MB; -1 when unavailable. */
    private static long peakWorkingSetMb() {
        try {
            Process pr = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "[math]::Round((Get-Process -Id " + ProcessHandle.current().pid() + ").PeakWorkingSet64/1MB)")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                return Long.parseLong(r.readLine().trim());
            }
        } catch (Exception e) {
            return -1;
        }
    }

    @Test
    void build() throws Exception {
        Path dir = Path.of(System.getProperty("inspecto.bench.dir")).toAbsolutePath();
        String label = System.getProperty("inspecto.bench.label");
        String phase = System.getProperty("inspecto.bench.phase", "full");
        String tag = System.getProperty("inspecto.bench.store", "main");
        String mem = System.getProperty("inspecto.bench.buildMemory", "default");
        if (label == null) throw new IllegalArgumentException("-Dinspecto.bench.label=<ingest folder label> is required");
        Path data = dir.resolve("ingest-" + label).resolve("database");
        List<String> all = parquet(data);
        IndexStore store = new IndexStore(dir.resolve("idx-" + label + "-" + tag), DS, M.hash());
        IndexBuilder.Options opt = new IndexBuilder.Options(null, "default".equals(mem) ? null : mem, null, null, null);
        Function<List<String>, String> delta = added -> relation(data, added);

        IndexBuilder.Mode mode = "append".equals(phase) ? IndexBuilder.Mode.APPEND : IndexBuilder.Mode.FULL;
        if (mode == IndexBuilder.Mode.APPEND) {
            if (store.current().isEmpty()) throw new IllegalStateException("no live index in store '" + tag + "' to append to");
            System.out.printf("IDX append onto live index %s: %d files in the Dataset, %d already indexed%n", store.current().get().getFileName(),
                    all.size(), IndexManifest.read(store.current().get()).inputFiles().size());
        } else if (store.current().isPresent()) {
            throw new IllegalStateException("store '" + tag + "' already holds an index; pick another -Dinspecto.bench.store");
        }
        System.out.printf("IDX %s label=%s store=%s files=%d (%.2f GB parquet) memory=%s  pre-run peakWS=%d MB%n", mode, label, tag, all.size(),
                all.stream().mapToLong(f -> data.resolve(f).toFile().length()).sum() / 1e9, mem, peakWorkingSetMb());
        long t = System.nanoTime();
        IndexBuilder.Result r = IndexBuilder.build(new IndexBuilder.Request(DS, M, relation(data, all), store, "fp-" + all.size(), opt,
                stamps(data, all), mode, delta, List.of(data)));
        double s = (System.nanoTime() - t) / 1e9;
        System.out.printf("IDX RESULT %s: %.1f s wall, %,d rows read, %,d edges kept, %,d nodes, %d buckets, deltas=%d, index %.2f GB on disk, "
                        + "peakWS=%d MB%n", mode, s, r.rowsInRelation(), r.edges(), r.nodes(), r.manifest().buckets(), r.manifest().deltas().size(),
                dirBytes(r.directory()) / 1e9, peakWorkingSetMb());
        System.out.println("IDX timings(ms) " + r.timingsMs());
    }
}
