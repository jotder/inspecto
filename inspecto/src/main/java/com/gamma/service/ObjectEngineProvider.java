package com.gamma.service;

import com.gamma.api.PublicApi;
import com.gamma.objects.ObjectAccess;

import java.nio.file.Path;
import java.util.List;

/**
 * Builds a Space's operational-object subsystem — the {@code ServiceLoader} seam behind EDITIONS
 * {@code CP-11} (EDG-01 cell 7, 2026-09-08).
 *
 * <p>The implementation lives in the optional {@code inspecto-ops} module. Absent it, no provider is
 * discovered, {@link CollectorService} holds no engine, and every operational-object surface answers 503
 * — while events are still recorded and the audit trail is untouched.
 *
 * <h3>Why this interface is declared HERE and not beside {@link ObjectAccess}</h3>
 * {@code ObjectAccess} belongs in {@code inspecto-engine}, expressed in engine types. This provider cannot:
 * opening the four stores needs {@link SpaceRoot} and {@link OperationalDb}, which live in <b>this</b>
 * module, <em>above</em> the engine. An engine-declared provider could not name them, and that dependency
 * must not be inverted. So this follows the {@code com.gamma.control.RouteModule} precedent — declared in
 * the host, implemented by an optional module that already depends on it — rather than the
 * {@code MaintenanceTaskProvider} one.
 *
 * <h3>What moves into the implementation</h3>
 * Both halves of the construction that mandatory core used to perform itself:
 * <ul>
 *   <li>{@code ServiceStores}' four {@code open*Store(SpaceRoot)} methods — the object, link, note and
 *       tag-assignment stores, each honouring {@code -Dobjects.backend} and its own {@code SpaceRoot} URL.
 *       ⚠ They are deliberately <b>separate DuckDB files</b>: a file-based DuckDB holds a single-writer
 *       lock, so one file cannot serve all four. Each also degrades to in-memory when the DB will not
 *       open, because the Alert Center must never block startup — keep that behaviour.</li>
 *   <li>{@code ServiceBootstrap}'s six ops config loaders — {@code *_queue}, {@code *_escalation},
 *       {@code *_tag}, {@code *_tagrule}, {@code *_caserule} and {@code *_workflow}. Each parsed into an
 *       ops-owned type and registered on the service, so none of it is expressible through
 *       {@code ObjectAccess}. ⛔ {@code *_rca} is NOT among them: {@code RcaTemplate} is core vocabulary
 *       and stays in {@code ServiceBootstrap}.</li>
 * </ul>
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public interface ObjectEngineProvider {

    /**
     * Open this Space's operational-object engine — the four stores.
     *
     * <p>⚠ Two phases on purpose, mirroring what core did before the extraction: the stores are opened
     * during {@code CollectorService} construction, while the config documents are registered afterwards
     * by {@code ServiceBootstrap} (see {@link ObjectEngine#loadConfigs}). Folding both into one call would
     * have forced the config paths into the {@code CollectorService} constructor, which does not take
     * them — a signature change to every caller for no behavioural gain.
     *
     * <p>⚠ {@code dataDir} is here for one reason worth stating: the module's {@code objects.analytics}
     * Job Type writes Parquet rollups under {@code <dataDir>/ops_analytics/}, and a
     * {@code ServiceLoader}-discovered {@code JobTypeProvider} receives only a {@code JobConfig} —
     * which carries no data root. Rather than widen that SPI for one Job Type, the engine is handed the
     * root it needs and the module's Job Types read it back from the engine they belong to.
     *
     * @param root    the Space whose per-store DB URLs and directories to use
     * @param dataDir the data root, for the module's analytics Job Type
     */
    ObjectEngine open(SpaceRoot root, String dataDir);

    /**
     * A live engine: the seam core talks through, plus the two things {@link ObjectAccess}'s method set
     * deliberately does not cover.
     */
    interface ObjectEngine extends AutoCloseable {

        /** The seam. Core holds this and names no type from the module. */
        ObjectAccess access();

        /**
         * Scan {@code configPaths} for the six ops config suffixes and register each on the engine —
         * {@code *_queue}, {@code *_escalation}, {@code *_tag}, {@code *_tagrule}, {@code *_caserule},
         * {@code *_workflow}. Replaces the six {@code com.gamma.ops.*}-typed loaders that used to live in
         * {@code ServiceBootstrap}.
         *
         * <p>⛔ {@code *_rca} is NOT among them — {@code RcaTemplate} is core vocabulary and its loader
         * stays in {@code ServiceBootstrap}. ⚠ At most one escalation policy applies (first valid wins),
         * and a {@code *_workflow} override is last-file-wins per type; both behaviours move with the scan.
         */
        void loadConfigs(List<Path> configPaths);

        /**
         * How many legacy {@code attributes.tags} entries were adopted into assignment edges at boot
         * (BACKLOG D7 phase 2). Core logs it, so it survives the extraction as a plain count rather than
         * as a reason to widen {@link ObjectAccess}.
         */
        int adoptedTagAssignments();

        /**
         * Run the Incident SLA sweep (breach detection + escalation) for {@code now}.
         *
         * <p>⚠ On the engine, not on {@link ObjectAccess}: core only <b>schedules</b> it (a
         * {@code -Dobjects.sla.sweep.seconds} timer), and the sweep itself is entirely operational-object
         * housekeeping. Putting it on the seam would have implied every {@code ObjectAccess} consumer
         * could drive it.
         */
        void sweepIncidentSla(long now);

        /**
         * The engine's stores as browsable ones, for the DB-browser surface. Empty for an in-memory
         * backend. {@code BrowsableStore} is a core type, so this crosses the boundary cleanly.
         */
        java.util.List<com.gamma.util.BrowsableStore> browsableStores();

        /**
         * Close all four stores. ⚠ Core closes them explicitly today, one log line each, so the engine
         * owns that now — an {@link ObjectAccess} with no close would have leaked four DuckDB
         * connections per Space.
         */
        @Override
        void close();
    }
}
