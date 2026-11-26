package com.gamma.control;

import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobContext;
import com.gamma.job.JobResult;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;
import com.gamma.job.PostgresPublishJobType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.KpiDefinition;
import com.gamma.query.KpiHistory;
import com.gamma.signal.Severity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code kpi.snapshot} Job Type (`BI-INSIGHTS-1` P0, I-3): stores one KPI's value per period at its own grain as
 * KPI History ({@link KpiHistory}) and registers it as the Dataset {@code kpi_history_<kpi>}.
 *
 * <ul>
 *   <li><b>Runs as its author</b>, as {@code publish.postgres}: a KPI or a source Dataset the Job's author may not view
 *       refuses the run, indistinguishably from an absent one. The KPI is the saved Job's: a run may not override it.</li>
 *   <li><b>Never reveals.</b> A Measure that returns raw values of a masked column ({@code BI-QUERY-MASKING-1}) refuses
 *       the run: a stored value outlives the reader's masking.</li>
 *   <li><b>A system Dataset, regardless of a Dataset approval policy</b> (D-BI11, operator 2026-11-24). Its sharing
 *       envelope ({@link #envelope}), re-applied before every write, admits only who may read both the KPI and its
 *       Dataset; a KPI and Dataset shared differently refuse the run.</li>
 * </ul>
 */
public final class KpiSnapshotJobType implements JobTypeProvider {

    public static final String TYPE = "kpi.snapshot";
    public static final String COMPLETED = "kpi.snapshot.completed";
    static final String P_KPI = "kpi";

    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor(TYPE, "KPI History snapshot",
                "Stores a KPI's value for every period at its own grain, three years back, as the Dataset "
                        + "kpi_history_<kpi>. The period in progress is marked incomplete and rewritten each run.",
                List.of(ParameterDecl.required(P_KPI, ParamType.STRING, "The KPI whose history is stored")),
                List.of(COMPLETED), List.of(), List.of());
    }

    @Override
    public Job create(JobConfig config) {
        return new Run(config);
    }

    private record Run(JobConfig cfg) implements Job {

        @Override public String name() { return cfg.name(); }
        @Override public String type() { return TYPE; }

        @Override public JobResult run() {
            throw new UnsupportedOperationException(TYPE + " requires a JobContext");
        }

        @Override
        public JobResult run(JobContext ctx) throws Exception {
            long t0 = System.nanoTime();
            // The KPI comes from the SAVED Job only, as la.index.build: a trigger arg, a manual trigger body or a Signal
            // binding may not point the Job, under its author's rights, at another KPI.
            String id = cfg.params().get(P_KPI);
            if (id == null || id.isBlank()) throw new IllegalArgumentException(TYPE + " needs parameter '" + P_KPI + "'");
            String kpiId = id.trim();
            String asked = ctx.params().get(P_KPI);
            if (asked != null && !asked.isBlank() && !asked.trim().equals(kpiId))
                throw new SecurityException("'" + P_KPI + "' comes from the saved Job only; a run may not override it — refused");
            if (ctx.dryRun())
                return JobResult.ok("dry run: KPI History of '" + kpiId + "' was NOT written", ms(t0));

            Path writeRoot = SpaceConfigRoot.requireCurrent(TYPE);
            Path data = SpaceConfigRoot.currentDataRoot();
            if (data == null) throw new IllegalStateException(TYPE + " needs a data root (space dataDir)");
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            ViewStore views = new ViewStore(writeRoot.resolve("views"));

            var authority = PostgresPublishJobType.authority();
            PostgresPublishJobType.Author author = authority == null ? PostgresPublishJobType.Author.OPEN : authority.authorOf(cfg);
            Map<String, Object> kpiContent = store.get("kpi", kpiId).map(ComponentRegistry.Component::content).orElse(null);
            if (kpiContent == null || !PostgresPublishJobType.canView(author, kpiContent))
                throw new SecurityException("kpi '" + kpiId + "' does not exist or the Job's author '" + author.id()
                        + "' may not view it — refused");
            KpiDefinition kpi = KpiDefinition.fromMap(kpiId, kpiContent, TimezoneSettings.forRoot(writeRoot).effective());
            Map<String, Object> dataset = store.get("dataset", kpi.dataset()).map(ComponentRegistry.Component::content).orElse(null);
            if (dataset == null || !PostgresPublishJobType.canRead(author, store, dataset))
                throw new SecurityException("dataset '" + kpi.dataset() + "' does not exist or the Job's author '"
                        + author.id() + "' may not read it — refused");
            BiResultMasking.of(writeRoot, store, views, kpi.dataset(), dataset, false).vetMeasure(kpi.measure());

            // Registered (or re-enveloped) BEFORE new values are written: a refusal writes nothing, and a narrowed sharing
            // envelope is in force before the values it protects.
            Map<String, Object> envelope = envelope(store, kpiContent, dataset);
            KpiHistory.claim(data, kpiId);   // a store another owner made refuses before anything is registered
            String datasetId = register(store, kpiId, envelope);
            String relationSql = DatasetRelation.relationSql(dataset, data, views);
            KpiHistory.Summary s = KpiHistory.snapshot(data, kpi, relationSql, LocalDate.now(kpi.zone()), Instant.now());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("job", cfg.name());
            payload.put("kpi", kpiId);
            payload.put("dataset", datasetId);
            payload.put("evaluated", s.evaluated());
            payload.put("rows", s.rows());
            ctx.signals().emit(COMPLETED, Severity.INFO, payload);
            return JobResult.ok(TYPE + ": " + s.evaluated() + " period(s) evaluated, " + s.rows()
                    + " in Dataset '" + datasetId + "'", ms(t0));
        }
    }

    /**
     * The sharing envelope ({@code owner}, {@code shares}) of the history: readable only by who may read BOTH the KPI and
     * its source Dataset. An unshared component (no {@code shares}) is open to everyone, so the other one's envelope is
     * the history's; two shared ones must be shared alike, since an envelope cannot state an intersection — otherwise
     * the run is refused rather than showing one component's readers the other's values.
     */
    static Map<String, Object> envelope(ComponentStore store, Map<String, Object> kpi, Map<String, Object> dataset) {
        try {
            return com.gamma.pipeline.SharingEnvelope.of(store, List.of(kpi, dataset));
        } catch (SecurityException e) {
            throw new SecurityException("the KPI and its Dataset are shared differently; the history would show one's "
                    + "values to the other's readers — share them alike, or leave one unshared — refused");
        }
    }

    /**
     * Register (or re-envelope) the history Dataset with {@code envelope} ({@link #envelope}). A Dataset of that id that
     * reads anything but the history store is refused, never overwritten.
     */
    static String register(ComponentStore store, String kpiId, Map<String, Object> source) throws IOException {
        String id = KpiHistory.store(kpiId);
        Map<String, Object> existing = store.get("dataset", id).map(ComponentRegistry.Component::content).orElse(null);
        if (existing != null && !id.equals(existing.get("physicalRef")))
            throw new IllegalStateException("dataset '" + id + "' exists and does not read the KPI History store — refused");
        Map<String, Object> content = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        if (existing == null) {
            content.put("physicalRef", id);
            content.put("description", "KPI History of '" + kpiId + "': its value per period, written by the "
                    + TYPE + " Job.");
            content.put("origin", "system");
            content.put("tags", List.of("kpi-history"));
        }
        for (String k : List.of("owner", "shares")) {
            if (source.containsKey(k)) content.put(k, source.get(k));
            else content.remove(k);
        }
        if (!content.equals(existing)) store.write("dataset", id, content);
        return id;
    }

    private static final List<String> STORE_KEYS = List.of("physicalRef", "store", "output_store", "path", "sourceName");

    /**
     * The {@code kpi_history_} store prefix is the Job's: a Dataset or sink naming such a store is refused, except the
     * history Dataset itself ({@code id = physicalRef = kpi_history_<kpi>}) over a saved KPI.
     */
    static void requireNotReserved(Path writeRoot, String type, String id, Map<String, Object> content) {
        List<String> named = com.gamma.alert.ScoreOutputDirs.namedStores(id, content, STORE_KEYS);
        if (!com.gamma.alert.ScoreOutputDirs.anyReserved(named, KpiHistory.PREFIX)) return;
        if ("dataset".equals(type) && id != null && id.equals(content.get("physicalRef")) && named.size() == 2
                && id.startsWith(KpiHistory.PREFIX) && writeRoot != null
                && new ComponentStore(writeRoot.resolve("registry")).exists("kpi", id.substring(KpiHistory.PREFIX.length())))
            return;
        throw new IllegalArgumentException(type + " '" + id + "' names a store under the reserved prefix '"
                + KpiHistory.PREFIX + "' (KPI History); only the Dataset with id = physicalRef = " + KpiHistory.PREFIX
                + "<kpi> over a saved KPI is allowed");
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }
}
