package com.gamma.job;

import com.gamma.config.safety.PathJail;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfig.ArrivalLoad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * <b>The arrival mode, run at commit</b> ({@code output.load}, {@code BASIC-ELT-PROFILE-1} O6, operator 2026-11-20): after
 * each SUCCESS Consignment of a Pipeline whose store loads by {@code replace} or {@code period}, the
 * {@link ArrivalReplacer} removes what the arrival replaces - every other file of the store, or the rows on the days of
 * {@code output.period_column} it covers - through its journal, swap and heal. Registered as the {@code output.load}
 * COMMIT step of the {@code CollectorService} (so before the Space's Jobs and the import's compaction see the commit).
 *
 * <p>Two ways a run says how its rows arrive:
 * <ul>
 *   <li><b>The Pipeline's config</b> ({@link PipelineConfig#arrivalLoad()}): every Consignment, each one final at once
 *       ({@link ArrivalReplacer#drop}). A replacement that fails puts the older files back and KEEPS the Consignment's
 *       rows ({@link ArrivalReplacer#abandon}: the engine committed them; never deleted behind a SUCCESS ledger row), and
 *       says so in the log at ERROR: those rows were appended. A replacement a crash interrupted is finished
 *       ({@link ArrivalReplacer#heal}) at the store's next commit, whatever its mode.</li>
 *   <li><b>A run-scoped choice</b> ({@link #override}), which wins over the config for the whole run while it is open:
 *       a person's per-upload choice (Add a file) or a caller's per-run one (a Postgres snapshot's Run now, O7). It applies
 *       to ONE Consignment - the one that holds the named file, or the run's first when none is named - and every other
 *       Consignment of the run appends; the files the run committed before it are never removed. Its replacement is left
 *       for the caller to decide ({@link RunLoad#close} makes it final, {@link RunLoad#restore} undoes it), so a run
 *       its caller then refuses loses nothing.</li>
 * </ul>
 * A store is keyed by its absolute path, so two Spaces with a same-named Pipeline never meet here. The caller holds the
 * store's Dataset claim (a run claim, or Add data's).
 */
public final class ArrivalLoadStep {

    private static final Logger log = LoggerFactory.getLogger(ArrivalLoadStep.class);

    private static final Map<Path, RunLoad> OVERRIDES = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    private ArrivalLoadStep() {}

    /**
     * While open, the runs into {@code store} load as {@code load} instead of their config: the Consignment that holds
     * {@code file} (an inbox file name; {@code null}: the run's first SUCCESS Consignment) by that mode, every other one
     * by appending. One open choice per store.
     *
     * @throws IllegalStateException when another run-scoped choice is open for {@code store}
     */
    public static RunLoad override(Path store, ArrivalLoad load, String file) {
        RunLoad o = new RunLoad(key(store), load == null ? ArrivalLoad.APPEND : load, file);
        if (OVERRIDES.putIfAbsent(o.store, o) != null)
            throw new IllegalStateException("another run already chose how rows arrive in " + o.store.getFileName());
        return o;
    }

    /**
     * The commit step: {@code e} committed for {@code cfg}; {@code files} names the inbox files the Consignment took (read
     * only when a run-scoped choice names one). Never throws.
     */
    public static void commit(PipelineConfig cfg, ConsignmentEvent e, Supplier<Collection<String>> files) {
        if (!"SUCCESS".equalsIgnoreCase(e.status()) || e.dryRun()) return;
        String db = cfg.dirs().database();
        if (db == null || db.isBlank() || PathJail.isUri(db.trim())) return;
        Path store = key(Path.of(db));
        List<Path> outputs = new ArrayList<>();
        for (String f : e.outputFiles()) {
            Path p = Path.of(f).toAbsolutePath().normalize();
            if (p.startsWith(store)) outputs.add(p);
        }
        RunLoad o = OVERRIDES.get(store);
        if (o != null) {
            o.commit(outputs, files);
            return;
        }
        // a replacement a crash interrupted is finished at the store's next commit, whatever its mode now (a run-scoped
        // choice's own pending replacement is left to its caller, above)
        if (Files.exists(ArrivalReplacer.journalFile(store)))
            try {
                ArrivalReplacer.heal(store);
            } catch (IOException | RuntimeException notHealed) {
                log.error("Pipeline '{}': the interrupted replacement in its store could not be finished ({})", e.pipeline(),
                        notHealed.toString());
            }
        ArrivalLoad load = cfg.arrivalLoad();
        if (load.mode() == ArrivalLoad.Mode.APPEND) return;
        String refused = cfg.arrivalLoadRefusal();
        if (refused != null) {   // refused at save and at load: only a config that bypassed both comes here
            log.warn("Pipeline '{}': {}; its rows were appended", e.pipeline(), refused);
            return;
        }
        ArrivalReplacer r = ArrivalReplacer.forStore(store, stamp());
        try {
            replace(r, load, outputs, outputs);
            r.drop();
        } catch (IOException | RuntimeException failed) {
            // the engine committed the Consignment: its rows stay (appended, a day may hold them twice), never deleted
            log.error("Pipeline '{}': output.load {} of Consignment {} failed ({}); its rows were APPENDED, the older rows it "
                    + "should have replaced are still there", e.pipeline(), load.mode().name().toLowerCase(java.util.Locale.ROOT),
                    e.consignmentId(), failed.toString());
            try {
                r.abandon();
            } catch (IOException | RuntimeException notUndone) {
                log.error("Pipeline '{}': the failed output.load of Consignment {} could not be undone ({}); the store's next "
                        + "commit heals it", e.pipeline(), e.consignmentId(), notUndone.toString());
            }
        }
    }

    private static ArrivalReplacer.Result replace(ArrivalReplacer r, ArrivalLoad load, List<Path> covering, List<Path> fresh)
            throws IOException {
        return load.mode() == ArrivalLoad.Mode.PERIOD ? r.replacePeriod(covering, fresh, load.periodColumn())
                : r.replaceEverything(covering, fresh);
    }

    private static Path key(Path store) {
        return store.toAbsolutePath().normalize();
    }

    /** An aside folder's stamp: letters and digits, unique in this JVM. */
    private static String stamp() {
        return Long.toString(System.currentTimeMillis(), 36) + "n" + SEQ.incrementAndGet();
    }

    /** A run-scoped arrival mode ({@link #override}); closing it makes its replacement final. */
    public static final class RunLoad implements AutoCloseable {
        private final Path store;
        private final ArrivalLoad load;
        private final String file;
        /** The outputs the run committed so far: never removed by its replacement. */
        private final List<Path> fresh = new ArrayList<>();
        private boolean used;
        private ArrivalReplacer replacer;
        private ArrivalReplacer.Result result;
        private Exception failure;

        private RunLoad(Path store, ArrivalLoad load, String file) {
            this.store = store;
            this.load = load;
            this.file = file;
        }

        private synchronized void commit(List<Path> outputs, Supplier<Collection<String>> files) {
            if (!used && load.mode() != ArrivalLoad.Mode.APPEND && (file == null || holds(files.get()))) {
                used = true;
                replacer = ArrivalReplacer.forStore(store, stamp());
                List<Path> keep = new ArrayList<>(fresh);
                keep.addAll(outputs);
                try {
                    result = replace(replacer, load, outputs, keep);
                } catch (IOException | RuntimeException failed) {
                    failure = failed;   // the caller learns it from failure() and undoes the run with restore()
                }
            }
            fresh.addAll(outputs);
        }

        private boolean holds(Collection<String> names) {
            String want = Path.of(file).getFileName().toString();
            for (String n : names)
                if (n != null && !n.isBlank() && Path.of(n).getFileName().toString().equals(want)) return true;
            return false;
        }

        /** What the replacement did; {@code null} when no Consignment of the run was replaced (yet). */
        public synchronized ArrivalReplacer.Result result() {
            return result;
        }

        /** Why the replacement failed; {@code null} when it did not. The caller undoes the run ({@link #restore}). */
        public synchronized Exception failure() {
            return failure;
        }

        /** Undo the replacement: the originals back, the replacing Consignment's own rows gone. A no-op when none ran. */
        public synchronized void restore() throws IOException {
            if (replacer != null) replacer.restore();
        }

        /** The run is over: its replacement is final (a no-op after {@link #restore}), and the store's config rules again. */
        @Override
        public void close() {
            OVERRIDES.remove(store, this);
            synchronized (this) {
                if (replacer != null) replacer.drop();
            }
        }
    }
}
