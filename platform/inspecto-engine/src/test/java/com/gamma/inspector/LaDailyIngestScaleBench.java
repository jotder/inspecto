package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

/**
 * LA-DAILY-INGEST-1 follow-up (scale): the INGEST half of the daily chain at 10^7+ rows. Runs the real
 * {@link ConsignmentIngestor#process} (commit, backup, markers, day manifest) over the shipped {@code la-daily-feed} Space
 * Template's Pipeline and schema (CSV, 10 columns, {@code partitionKey: EVENT_DATE}), one Consignment per part file as the
 * engine runs a poll cycle with {@code threads: 1}. The partitioned Parquet Dataset it writes is what
 * {@code DailyIndexScaleBench} (inspecto-la-storage) then indexes. Never runs in the default suite (not {@code *Test},
 * tagged, gated).
 *
 * <p>Needs {@code -Dinspecto.bench.dir=<dir>} (outside the repo). Knobs {@code inspecto.bench.*}: {@code rows} (rows per part,
 * 10000000), {@code parts} (part files of the day, 1), {@code day} (UTC date, {@code 2026-09-01}), {@code label} (output
 * folder {@code ingest-<label>}, default {@code <rows*parts>}), {@code keepCsv} (false: the day CSV is deleted once
 * ingested, to bound disk). Results print as {@code INGEST ...} lines.
 *
 * <p>Corpus: the D-S5 skew (MSISDN ~ nodes * u^3, OTHER_PARTY ~ nodes * u^2, nodes = rows / 5 over the WHOLE feed so later
 * days re-use earlier entities), deterministic.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class LaDailyIngestScaleBench {

    private static long num(String k, long d) {
        return Long.parseLong(System.getProperty("inspecto.bench." + k, Long.toString(d)));
    }

    private static String fwd(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("spaces/_templates/la-daily-feed"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("repo root not found");
        return p;
    }

    /** Peak working set of THIS JVM so far (DuckDB's native memory included), MB, via PowerShell; -1 when unavailable. */
    static long peakWorkingSetMb() {
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

    private static long dirBytes(Path d) throws Exception {
        if (!Files.exists(d)) return 0;
        try (Stream<Path> w = Files.walk(d)) {
            return w.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }

    private static PipelineConfig pipeline(Path work) throws Exception {
        Path tpl = repoRoot().resolve("spaces/_templates/la-daily-feed/config/daily_xdr");
        Files.createDirectories(work);
        Path schema = work.resolve("daily_xdr_schema.toon");
        Files.copy(tpl.resolve("daily_xdr_schema.toon"), schema, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String text = Files.readString(tpl.resolve("daily_xdr_pipeline.toon"), StandardCharsets.UTF_8)
                .replace("data/inbox/daily_xdr", fwd(work) + "/inbox")
                .replaceAll("(?m)^(  (?:database|backup|temp|errors|quarantine|markers|status_dir|log_dir):\\s+)data/daily_xdr/",
                        "$1" + fwd(work) + "/")
                .replace("schema_file: daily_xdr_schema.toon", "schema_file: " + fwd(schema));
        Path p = work.resolve("daily_xdr_pipeline.toon");
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }

    @Test
    void ingestADay() throws Exception {
        Path dir = Path.of(System.getProperty("inspecto.bench.dir")).toAbsolutePath();
        long rows = num("rows", 10_000_000), parts = num("parts", 1);
        String day = System.getProperty("inspecto.bench.day", "2026-09-01");
        String label = System.getProperty("inspecto.bench.label", Long.toString(rows * parts));
        long nodes = Math.max(1000, num("nodes", rows * parts / 5));
        long seedOffset = num("seedOffset", 0);
        Path work = dir.resolve("ingest-" + label);
        PipelineConfig cfg = pipeline(work);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        String ymd = day.replace("-", "");

        System.out.printf("INGEST day=%s parts=%d x %,d rows (= %,d), nodes=%,d  pre-run: peakWS=%d MB%n", day, parts, rows,
                rows * parts, nodes, peakWorkingSetMb());
        double genTotal = 0, ingTotal = 0;
        long wholeStart = System.nanoTime();
        for (long k = 0; k < parts; k++) {
            String name = "XDR_" + ymd + (parts > 1 ? "_part" + String.format("%04d", k + 1) : "") + ".csv";
            File f = inbox.resolve(name).toFile();
            long t = System.nanoTime();
            long off = seedOffset + k * rows;
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                st.execute("SET memory_limit = '4GB'");
                st.execute("COPY (SELECT '" + ymd + "-' || lpad(CAST(i AS VARCHAR), 9, '0') AS REC_SEQ,"
                        + " CASE (hash(i * 3) % 10) WHEN 0 THEN 'SMS' WHEN 1 THEN 'DATA' ELSE 'VOICE' END AS REC_TYPE,"
                        + " '99' || lpad(CAST(CAST(floor(" + nodes + " * pow((hash(i * 2) % 1000000) / 1e6, 3)) AS BIGINT) AS VARCHAR), 10, '0') AS MSISDN,"
                        + " '99' || lpad(CAST(CAST(floor(" + nodes + " * pow((hash(i * 2 + 1) % 1000000) / 1e6, 2)) AS BIGINT) AS VARCHAR), 10, '0') AS OTHER_PARTY,"
                        + " '00101' || lpad(CAST(hash(i * 5) % 5000000 AS VARCHAR), 10, '0') AS IMSI,"
                        + " '35' || lpad(CAST(hash(i * 7) % 8000000 AS VARCHAR), 13, '0') AS IMEI,"
                        + " strftime(TIMESTAMP '" + day + "' + to_seconds(i % 86400), '%Y-%m-%d %H:%M:%S') AS START_AT,"
                        + " '" + day + "' AS EVENT_DATE, CAST(hash(i * 11) % 3600 AS INTEGER) AS DURATION_SEC,"
                        + " CASE (hash(i * 13) % 3) WHEN 0 THEN 'MOC' WHEN 1 THEN 'MTC' ELSE 'NA' END AS DIRECTION"
                        + " FROM range(" + off + ", " + (off + rows) + ") r(i)) TO '" + fwd(f.toPath()) + "' (FORMAT csv, HEADER)");
            }
            double gen = (System.nanoTime() - t) / 1e9;
            genTotal += gen;
            long genPeak = peakWorkingSetMb();       // cumulative: the ingest figure below only counts as its own when it exceeds this

            SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
            Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_b_" + label + "_" + k, "bench", null,
                    List.of(new Consignment.Member(f, 0, f.length(), sel)));
            long csvBytes = f.length();
            t = System.nanoTime();
            ConsignmentIngestor.process(batch, cfg, new ConsignmentAuditWriter(
                    cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));
            double ing = (System.nanoTime() - t) / 1e9;
            ingTotal += ing;
            System.out.printf("INGEST part %d/%d %s: csv %.2f GB generated in %.0f s; INGEST %.1f s = %,.0f rows/s; peakWS=%d MB (after generation, before ingest: %d MB)%n",
                    k + 1, parts, name, csvBytes / 1e9, gen, ing, rows / ing, peakWorkingSetMb(), genPeak);
            if (!Boolean.getBoolean("inspecto.bench.keepCsv")) {
                // the ingest moves the file into backup/; drop it (the corpus is deterministic) so disk stays bounded
                try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().backup()))) {
                    w.filter(p -> p.getFileName().toString().equals(name)).forEach(p -> p.toFile().delete());
                }
                f.delete();
            }
        }
        Path db = Path.of(cfg.dirs().database());
        System.out.printf("INGEST DONE day=%s rows=%,d: ingest wall %.1f s (%,.0f rows/s), generation %.0f s, dataset %.2f GB parquet in %s, "
                        + "peakWS=%d MB%n", day, rows * parts, ingTotal, rows * parts / ingTotal, genTotal, dirBytes(db) / 1e9, db,
                peakWorkingSetMb());
    }
}
