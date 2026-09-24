package com.gamma.inspector;

import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.CsvIngester;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Record-level replay (EXECUTION-RESIDUALS X4, first slice): re-ingest the <b>rejected records</b> of ONE file,
 * taken from its reject sidecar, as a new Consignment — the file's good records, which already landed under
 * eject-and-continue, are not touched.
 *
 * <ul>
 *   <li><b>Input</b> — the sidecar's {@code raw_line} column, verbatim, written as a UTF-8 file of bare data
 *       lines into the poll root under {@code <stem>__replay_<sha8>.<ext>} and parsed with
 *       {@link PipelineConfig#forRecordReplay()} (no header / pre-header / junk / footer framing).</li>
 *   <li><b>Lane</b> — the normal flat ingest over exactly that one file ({@link CollectorProcessor#ingestCandidates}),
 *       so it gets the whole commit tail: outputs, manifest, backup, marker, audit, provenance, terminal event.
 *       Discovery is bypassed, so the replay name need not match {@code file_pattern}; the schema is selected by
 *       the ORIGINAL file's name.</li>
 *   <li><b>Attribution</b> — a durable replay record {@code <status_dir>/replays/<sidecar-sha256>.json} naming the
 *       original file, its sidecar, the new Consignment's {@code batchId}, and {@code lines}: record {@code k} of
 *       the replay input is the original file's line {@code lines[k-1]}.</li>
 *   <li><b>Idempotence</b> — that record is CLAIMED with an atomic create before anything is written, keyed on
 *       the sidecar's content hash, so a second replay of the same sidecar is refused. A replay whose
 *       Consignment did not complete (FAILED, or thrown) removes its input and releases the claim; any other
 *       end keeps it — records still rejected are in the replay input's OWN sidecar, replayable in turn.</li>
 * </ul>
 *
 * <p>Refusals follow {@link DrainCommand}: {@link NoSuchFileException} = no sidecar (404),
 * {@link IllegalArgumentException} = not replayable (422 / 403 for a path), {@link IllegalStateException} =
 * already replayed (409). The caller holds the pipeline's run claim, so no poll of it runs concurrently.
 */
public final class RecordReplay {

    private static final Logger log = LoggerFactory.getLogger(RecordReplay.class);

    /** Outcome of one replay. {@code status} is the Consignment's terminal status. */
    public record Result(String file, String replayFile, String batchId, String status, int records,
                         long outputRows, long errorRows, String error, String recordPath) {}

    private RecordReplay() {}

    public static Result replay(PipelineConfig cfg, String file, Consumer<ConsignmentEvent> onCommit)
            throws Exception {
        if (file == null || file.isBlank() || file.contains("/") || file.contains("\\") || file.contains(".."))
            throw new IllegalArgumentException("file must be a bare file name, not a path: " + file);
        if (cfg.schemas().ingesterClass() != null || cfg.fixedWidth() != null || cfg.json() != null
                || cfg.textRegex() != null || cfg.xlsx() != null || cfg.parquet() != null)
            throw new IllegalArgumentException("record replay covers delimited-CSV pipelines only");
        String manifests = cfg.dirs().manifestsDir();
        if (manifests == null || manifests.isBlank())
            throw new IllegalArgumentException("record replay needs dirs.status_dir (its replay record lives there)");

        Path sidecar = locateSidecar(cfg, CsvIngester.stripExtensions(file) + "_errors.csv");
        if (sidecar == null) throw new NoSuchFileException("no reject sidecar recorded for '" + file + "'");

        List<Map<String, String>> rows = new ArrayList<>();
        com.gamma.util.Csv.readInto(sidecar, rows);
        if (rows.isEmpty()) throw new IllegalArgumentException("the reject sidecar for '" + file + "' holds no records");
        List<String> lines = new ArrayList<>();
        List<Long> lineNumbers = new ArrayList<>();
        for (Map<String, String> r : rows) {
            String raw = r.get("raw_line");
            if (raw == null || raw.isEmpty())
                throw new IllegalArgumentException("sidecar record at line " + r.get("line_number")
                        + " carries no raw_line — nothing to replay");
            lines.add(raw);
            lineNumbers.add(Long.parseLong(r.get("line_number").trim()));
        }
        Integer cap = cfg.csv().rejects().limit();
        if (cap != null && cap > 0 && rows.size() >= cap)
            throw new IllegalArgumentException("the sidecar holds " + rows.size() + " records = rejects_limit "
                    + cap + ", so it may be truncated; replaying it would silently drop the rest");

        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sidecar)));
        Path claim = Paths.get(manifests).toAbsolutePath().resolveSibling("replays").resolve(hash + ".json");
        Files.createDirectories(claim.getParent());
        try {
            Files.createFile(claim);
        } catch (FileAlreadyExistsException twice) {
            throw new IllegalStateException("'" + file + "' was already replayed from this sidecar (record "
                    + claim + ") — replaying it again would land its records twice");
        }

        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        String replayName = replayName(file, hash);
        Path input = poll.resolve(replayName);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("originalFile", file);
        record.put("sidecar", sidecar.toString());
        record.put("sidecarSha256", hash);
        record.put("replayFile", replayName);
        record.put("replayedAt", Instant.now().toString());
        record.put("lines", lineNumbers);

        ConsignmentEvent[] seen = new ConsignmentEvent[1];
        Throwable thrown = null;
        try {
            Files.createDirectories(poll);
            Path tmp = poll.resolve(replayName + ".writing");
            Files.writeString(tmp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, input, StandardCopyOption.ATOMIC_MOVE);

            SchemaSelector.Selection selection = (cfg.schemas().selector() != null)
                    ? cfg.schemas().selector().select(new File(file))      // the ORIGINAL file's schema
                    : new SchemaSelector.Selection(cfg.schemas().single(), null);
            Consumer<ConsignmentEvent> capture = ev -> seen[0] = ev;
            CollectorProcessor.ingestCandidates(cfg.forRecordReplay(), List.of(input.toFile()), f -> selection,
                    onCommit == null ? capture : capture.andThen(onCommit), false);
        } catch (Exception e) {
            thrown = e;
        }

        ConsignmentEvent ev = seen[0];
        if (thrown != null || ev == null || "FAILED".equals(ev.status())) {
            // Nothing committed: remove the input (else a later poll could land it outside this record) and
            // release the claim so the operator can replay again once the cause is fixed.
            Files.deleteIfExists(input);
            Files.deleteIfExists(claim);
            String why = thrown != null ? String.valueOf(thrown.getMessage())
                    : ev == null ? "the replay Consignment reported no terminal status" : ev.error();
            log.warn("[REPLAY] {} — replay of {} record(s) did not complete; claim released: {}", file, lines.size(), why);
            return new Result(file, replayName, ev == null ? null : ev.batchId(), "FAILED", lines.size(),
                    0, 0, why, null);
        }

        record.put("batchId", ev.batchId());
        record.put("status", ev.status());
        record.put("outputRows", ev.outputRows());
        record.put("errorRows", ev.errorRows());
        Path tmp = claim.resolveSibling(claim.getFileName() + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(record),
                StandardCharsets.UTF_8);
        Files.move(tmp, claim, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("[REPLAY] {} — {} record(s) replayed as {} ({}): {} landed, {} still rejected",
                file, lines.size(), ev.batchId(), ev.status(), ev.outputRows(), ev.errorRows());
        return new Result(file, replayName, ev.batchId(), ev.status(), lines.size(), ev.outputRows(),
                ev.errorRows(), ev.error(), claim.toString());
    }

    /** {@code feed.csv.gz} → {@code feed__replay_<sha8>.csv}: a plain file (the sidecar holds decoded text). */
    static String replayName(String file, String hash) {
        String plain = file.replaceAll("(?i)\\.(gz|bz2|zip|z)$", "");
        int dot = plain.lastIndexOf('.');
        String ext = dot > 0 ? plain.substring(dot) : "";
        return CsvIngester.stripExtensions(file) + "__replay_" + hash.substring(0, 8) + ext;
    }

    /**
     * The sidecar's two homes: {@code dirs.errors} while the file was accepted with rejects, else beside the
     * file in the quarantine tree (a file rejected whole). Same order and bound as {@code GET /runs/{n}/errors}.
     */
    private static Path locateSidecar(PipelineConfig cfg, String wanted) throws IOException {
        if (cfg.dirs().errors() != null) {
            Path direct = Paths.get(cfg.dirs().errors()).toAbsolutePath().normalize().resolve(wanted);
            if (Files.isRegularFile(direct)) return direct;
        }
        if (cfg.dirs().quarantine() == null) return null;
        Path qRoot = Paths.get(cfg.dirs().quarantine()).toAbsolutePath().normalize();
        if (!Files.isDirectory(qRoot)) return null;
        try (Stream<Path> walk = Files.walk(qRoot, 6)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(wanted))
                    .filter(p -> p.normalize().startsWith(qRoot))
                    .findFirst().orElse(null);
        }
    }
}
