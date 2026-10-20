package com.gamma.geolink;

import com.gamma.acquire.AcquisitionLedger;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.LedgerEntry;
import com.gamma.control.HostContext;
import com.gamma.etl.unpack.LogicalNames;
import java.nio.file.StandardCopyOption;
import com.gamma.etl.FileNames;
import com.gamma.etl.MarkerManager;
import com.gamma.etl.PipelineConfig;
import com.gamma.la.core.UploadLandingPort;
import com.gamma.spi.http.ApiContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The BRIDGE's {@link UploadLandingPort}: resolves a Collector id (or Pipeline name) over the live Pipelines and answers only
 * for one that opted in with {@code collector.accepts_upload: true} and polls a local directory (the same poll-directory
 * resolution as {@code PipelineInboxRoutes}). It also reads the Collector's own ingest footprint (marker, reject ledger,
 * quarantine folder) so the upload status read can say where a landed file is.
 */
public final class HostUploadLandingPort implements UploadLandingPort {

    /** At most this many quarantine entries are examined per lookup (depth 4), so a huge quarantine cannot stall a status read. */
    static final int QUARANTINE_WALK_CAP = 20_000;

    /** At most this many reject-ledger rows are counted per file; past it the count is this and the read says "capped". */
    public static final int REJECT_COUNT_CAP = 10_000;

    /** At most this many BYTES of a reject ledger are read per file (one enormous line cannot be pulled whole). */
    public static final int REJECT_READ_CAP_BYTES = 4 * 1024 * 1024;

    @Override
    public Optional<Target> target(ApiContext api, String collector) {
        var service = HostContext.of(api).service();
        for (var pv : service.pipelines()) {
            Optional<PipelineConfig> cfg = service.configFor(pv.name());
            if (cfg.isEmpty()) continue;
            PipelineConfig.Collector c = cfg.get().collector();
            if (!collector.equals(c.id()) && !collector.equals(pv.name())) continue;
            return asTarget(pv.name(), cfg.get());
        }
        return Optional.empty();
    }

    @Override
    public List<Target> targets(ApiContext api) {
        var service = HostContext.of(api).service();
        List<Target> out = new ArrayList<>();
        for (var pv : service.pipelines())
            service.configFor(pv.name()).flatMap(cfg -> asTarget(pv.name(), cfg)).ifPresent(out::add);
        return out;
    }

    private static Optional<Target> asTarget(String pipeline, PipelineConfig cfg) {
        PipelineConfig.Collector c = cfg.collector();
        String poll = cfg.dirs().poll();
        if (!c.acceptsUpload() || !"local".equals(c.connector()) || c.hasDataset() || c.hasConnection()
                || poll == null || poll.isBlank()) return Optional.empty();
        return Optional.of(new Target(c.id(), pipeline, Paths.get(poll).toAbsolutePath().normalize(),
                c.includes(), c.excludes()));
    }

    @Override
    public Ingest ingest(ApiContext api, Target target, String file) {
        Optional<PipelineConfig> found = HostContext.of(api).service().configFor(target.pipeline());
        Path inFile = target.inbox().resolve(file);
        if (found.isEmpty()) return new Ingest(Files.exists(inFile) ? "pending" : "consumed", null, false, null);
        PipelineConfig cfg = found.get();
        long[] rej = rejectedRows(cfg, file);
        Long rejected = rej == null ? null : rej[0];
        boolean capped = rej != null && rej[1] == 1;
        try {
            if (cfg.processing().duplicateCheckEnabled() && cfg.dirs().markers() != null
                    && Files.exists(MarkerManager.getMarkerPath(inFile.toFile(), cfg)))
                return new Ingest("ingested", rejected, capped, null);
        } catch (RuntimeException unreadable) {
            // fall through: the other footprints still answer
        }
        // Content-based duplicate mode (checksum / metadata / etag) records a fingerprint row instead of a marker, and with
        // no backup dir the file stays in the inbox: a ledger row whose recorded file mtime is not older than the file's means
        // this very content was ingested (the ledger's own lastModified, not the wall clock of the processing host).
        if (fingerprintedSinceLanding(cfg, inFile, file)) return new Ingest("ingested", rejected, capped, null);
        String reason = quarantineReason(cfg, file);
        if (reason != null) return new Ingest("quarantined", rejected, capped, reason);
        return new Ingest(Files.exists(inFile) ? "pending" : "consumed", rejected, capped, null);
    }

    private static boolean fingerprintedSinceLanding(PipelineConfig cfg, Path inFile, String file) {
        try {
            if (!cfg.collector().duplicate().contentBased()) return false;
            AcquisitionLedger ledger = AcquisitionLedgers.shared();
            String logical = FileNames.safe(cfg, LogicalNames.logicalName(file, cfg));
            String verbatim = FileNames.safe(cfg, file);
            Optional<LedgerEntry> prior = ledger.find(cfg.collector().id(), verbatim).or(() -> ledger.find(cfg.collector().id(), logical));
            if (prior.isEmpty()) return false;
            if (!Files.exists(inFile)) return true;
            return prior.get().lastModified() >= Files.getLastModifiedTime(inFile).toMillis();
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** A claim file left by a withdraw that died between its rename and its delete: {@code .<name>.withdraw-<nanos>.tmp}. */
    private static final java.util.regex.Pattern STRANDED = java.util.regex.Pattern.compile("\\.(.+)\\.withdraw-\\d+\\.tmp");

    @Override
    public <T> Optional<T> whileIdle(ApiContext api, Target target, java.util.concurrent.Callable<T> action) throws Exception {
        return HostContext.of(api).service().whileIdle(target.pipeline(), () -> {
            sweepStranded(target);
            return action.call();
        });
    }

    /**
     * Delete stranded withdraw claim files from the inbox's top level. Runs only under the run claim, where no other withdraw
     * is mid-rename (each holds the claim), so a match is always stranded. Plain regular files of that exact name shape only:
     * never a folder, a junction or a link (not followed, not deleted), never a file of any other name.
     */
    static void sweepStranded(Target target) {
        try (Stream<Path> s = Files.list(target.inbox())) {
            for (Path p : s.toList()) {
                java.util.regex.Matcher m = STRANDED.matcher(p.getFileName().toString());
                // Only a name THIS Collector's withdraw could have made: the run claim is per Pipeline, so another Pipeline
                // polling the same inbox may hold a live claim file under a name its own patterns accept.
                if (!m.matches() || !target.accepts(m.group(1))) continue;
                if (!Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    Files.deleteIfExists(p);
                } catch (IOException stuck) {
                    // housekeeping: the next withdraw or replace retries
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // housekeeping only
        }
    }

    @Override
    public Withdrawal withdraw(ApiContext api, Target target, String file) {
        Path inFile = target.inbox().resolve(file).normalize();
        if (!target.inbox().equals(inFile.getParent())) return new Withdrawal("failed", null, "the name escapes the inbox");
        try {
            // Cheap filesystem look BEFORE the run claim: a file already picked up is answered without touching the claim,
            // so repeated deletes of such a file cannot hammer it (and are not told "busy" while a run is going).
            Ingest early = ingest(api, target, file);
            if (!"pending".equals(early.state())) return new Withdrawal("picked-up", early.state(), null);
            Optional<Withdrawal> done = whileIdle(api, target, () -> {
                Ingest now = ingest(api, target, file);
                if (!"pending".equals(now.state())) return new Withdrawal("picked-up", now.state(), null);
                // Claim the file first: a name the poll never picks up (the in-flight .tmp exclusion, and it no longer
                // matches any .csv include), atomically, so no reader sees it half-deleted under its real name.
                Path claimed = target.inbox().resolve("." + file + ".withdraw-" + System.nanoTime() + ".tmp");
                try {
                    Files.move(inFile, claimed, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.NoSuchFileException gone) {
                    return new Withdrawal("picked-up", "consumed", null);
                } catch (IOException held) {
                    return new Withdrawal("busy", "pending", "the file is open elsewhere: " + held.getClass().getSimpleName());
                }
                try {
                    Files.deleteIfExists(claimed);
                } catch (IOException stuck) {
                    // claimed but undeletable: put it back under its name so nothing is lost silently
                    try { Files.move(claimed, inFile, StandardCopyOption.ATOMIC_MOVE); } catch (IOException ignored) { /* left as .tmp */ }
                    return new Withdrawal("failed", "pending", stuck.getClass().getSimpleName());
                }
                return new Withdrawal("deleted", "pending", null);
            });
            return done.orElse(new Withdrawal("busy", "pending", "a run of the Pipeline is in flight"));
        } catch (Exception unexpected) {
            return new Withdrawal("failed", null, unexpected.getClass().getSimpleName());
        }
    }

    /** {count, capped(0|1)} of rows in the per-file reject ledger ({@code <errors>/<stem>_errors.csv}, one header line), counted
     *  up to {@link #REJECT_COUNT_CAP}; null when absent. */
    private static long[] rejectedRows(PipelineConfig cfg, String file) {
        try {
            if (cfg.dirs().errors() == null) return null;
            Path ledger = Paths.get(cfg.dirs().errors()).toAbsolutePath().resolve(FileNames.errorsFileName(cfg, file));
            if (!Files.isRegularFile(ledger)) return null;
            try (java.io.InputStream in = Files.newInputStream(ledger)) {
                // Count non-blank lines by scanning bytes in blocks: at most REJECT_READ_CAP_BYTES are read, so neither a huge
                // ledger nor one enormous line is pulled into memory; the header line is not a row.
                byte[] buf = new byte[64 * 1024];
                long lines = 0, read = 0;
                boolean content = false, capped = false;
                int n;
                scan:
                while ((n = in.read(buf)) != -1) {
                    for (int i = 0; i < n; i++) {
                        byte b = buf[i];
                        if (b == '\n') {
                            if (content) lines++;
                            content = false;
                            if (lines - 1 > REJECT_COUNT_CAP) { capped = true; break scan; }
                        } else if (b != '\r' && b != ' ' && b != '\t') content = true;
                    }
                    read += n;
                    if (read >= REJECT_READ_CAP_BYTES && in.read() != -1) { capped = true; break; }
                }
                if (!capped && content) lines++;
                if (lines - 1 > REJECT_COUNT_CAP) capped = true;
                return new long[]{Math.max(0L, Math.min(lines - 1, REJECT_COUNT_CAP)), capped ? 1 : 0};
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The quarantine reason folder holding a file of this name ({@code <quarantine>/<reason>/<name>}), bounded walk. */
    private static String quarantineReason(PipelineConfig cfg, String file) {
        try {
            if (cfg.dirs().quarantine() == null) return null;
            Path q = Paths.get(cfg.dirs().quarantine()).toAbsolutePath().normalize();
            if (!Files.isDirectory(q)) return null;
            try (Stream<Path> walk = Files.walk(q, 4)) {
                return walk.limit(QUARANTINE_WALK_CAP).filter(p -> p.getFileName().toString().equals(file) && Files.isRegularFile(p))
                        .map(p -> p.getParent().getFileName().toString()).findFirst().orElse(null);
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
