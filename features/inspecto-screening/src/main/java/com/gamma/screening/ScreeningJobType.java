package com.gamma.screening;

import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobContext;
import com.gamma.job.JobResult;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;
import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.signal.Severity;
import com.gamma.sql.SqlSandboxPolicy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code screening.run} Job Type (SCREENING-1, SCR-D7 / SCR-D8 / SCR-D11): screens every row of a Dataset —
 * a subject key column plus a name and/or an identifier column — against Entity Lists and raises a Screening Hit
 * for each match at or above the threshold that has no hit yet.
 *
 * <p>Params: {@code dataset}, {@code keyField}, {@code nameField} and/or {@code idField}, {@code lists} (comma
 * separated), {@code threshold} (default 0.85), {@code maxRows} (default 100 000, ceiling 1 000 000). More rows
 * than {@code maxRows} FAILS the Run — a silently partial screen would read as a clean one. A classified column's
 * value is stored in the hit masked (the {@code risk.score} {@link EvidenceMasker}); matching uses the raw value.
 */
public final class ScreeningJobType implements JobTypeProvider {

    static final String ID = "screening.run";
    static final int DEFAULT_MAX_ROWS = 100_000, MAX_ROWS_CEILING = 1_000_000;
    private static final Pattern SAFE_COL = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    static final JobTypeDescriptor DESCRIPTOR = new JobTypeDescriptor(ID, "Screening",
            "Screens a Dataset's names and identifiers against Entity Lists (sanctions, PEP, deny lists) and raises "
                    + "a Screening Hit for review for every new match at or above the threshold.",
            List.of(ParameterDecl.required("dataset", ParamType.STRING, "Dataset to screen"),
                    ParameterDecl.required("keyField", ParamType.STRING, "Column that identifies the subject"),
                    ParameterDecl.required("lists", ParamType.STRING, "Entity List ids, comma separated")),
            List.of("screening.hits.raised"),
            List.of());

    private final String dataDir;

    /** The {@code ServiceLoader} constructor: the Job resolves the Space's data root at run time. */
    public ScreeningJobType() { this(null); }

    ScreeningJobType(String dataDir) { this.dataDir = dataDir; }

    @Override public JobTypeDescriptor descriptor() { return DESCRIPTOR; }

    @Override public Job create(JobConfig config) { return new ScreeningJob(config, dataDir); }

    static final class ScreeningJob implements Job {
        private final JobConfig cfg;
        private final String dataDir;

        ScreeningJob(JobConfig cfg, String dataDir) {
            this.cfg = cfg;
            this.dataDir = dataDir;
        }

        @Override public String name() { return cfg.name(); }
        @Override public String type() { return ID; }

        @Override public JobResult run() {
            throw new UnsupportedOperationException(ID + " requires a JobContext");
        }

        @Override
        public JobResult run(JobContext ctx) throws Exception {
            long t0 = System.nanoTime();
            Path data = dataDir != null && !dataDir.isBlank() ? Path.of(dataDir) : SpaceConfigRoot.currentDataRoot();
            if (data == null) throw new IllegalStateException(ID + " needs a data root (space dataDir)");
            Path writeRoot = SpaceConfigRoot.requireCurrent(ID);
            Params p = Params.of(cfg.params());

            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            Map<String, Object> ds = store.get("dataset", p.dataset()).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new IllegalArgumentException(ID + ": unknown dataset '" + p.dataset() + "'"));
            String relation = DatasetRelation.relationSql(ds, data, new ViewStore(writeRoot.resolve("views")));
            Instant now = Instant.now();
            List<Screener.Prepared> lists = Screener.load(writeRoot, p.lists(), now);

            List<String> columns = p.columns();
            QueryExecutor.Result rows = read(p, relation, columns);
            if (rows.truncated() || rows.rows().size() > p.maxRows())
                throw new IllegalStateException(ID + ": dataset '" + p.dataset() + "' has more than " + p.maxRows()
                        + " rows — refusing to screen a subset; raise maxRows (up to " + MAX_ROWS_CEILING + ")");
            EvidenceMasker masker = EvidenceMasker.of(store, writeRoot, List.of(p.dataset()));

            String actor = "job:" + ID + ":" + cfg.name();
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("job", cfg.name());
            source.put("runId", ctx.runId());
            source.put("dataset", p.dataset());
            int screened = 0, matched = 0, raised = 0;
            synchronized (ScreeningHits.lock()) {
                Set<Object> known = new HashSet<>();
                for (Map<String, Object> h : ScreeningHits.list(writeRoot)) known.add(h.get("dedupeKey"));
                for (Map<String, Object> row : rows.rows()) {
                    Object key = row.get(p.keyField());
                    if (key == null) continue;
                    String name = str(row, p.nameField()), id = str(row, p.idField());
                    if (name == null && id == null) continue;
                    screened++;
                    Screener.Subject subject = new Screener.Subject(String.valueOf(key), name, id);
                    for (Screener.Match m : Screener.screen(subject, lists, p.threshold(), Screener.MAX_MATCHES, now)) {
                        matched++;
                        String dedupe = ScreeningHits.dedupeKey(m.listId(), m.entry(), subject.key());
                        if (!known.add(dedupe)) continue;
                        Screener.Subject stored = new Screener.Subject(subject.key(),
                                masked(masker, p.dataset(), p.nameField(), name), masked(masker, p.dataset(), p.idField(), id));
                        Map<String, Object> hit = ScreeningHits.raise(stored, m, p.threshold(), source, actor);
                        hit.put("dedupeKey", dedupe);   // keyed on the subject key, which is never masked
                        ScreeningHits.save(writeRoot, hit);
                        raised++;
                    }
                }
            }
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("job", cfg.name());
            attrs.put("runId", ctx.runId());
            attrs.put("dataset", p.dataset());
            attrs.put("lists", String.join(",", p.lists()));
            attrs.put("screened", screened);
            attrs.put("matched", matched);
            attrs.put("raised", raised);
            ScreeningHits.audit(actor, "job", "screening.run.hits", ID + " " + cfg.name() + ": " + raised
                    + " new hit(s) from " + matched + " match(es) over " + screened + " subject(s)", attrs);
            ctx.signals().emit("screening.hits.raised", Severity.INFO, Map.of("dataset", p.dataset(),
                    "screened", screened, "matched", matched, "raised", raised));
            ctx.log().info("screened subjects", "dataset", p.dataset(), "screened", screened, "matched", matched,
                    "raised", raised);
            return JobResult.ok(ID + ": " + screened + " subject(s) screened, " + matched + " match(es), " + raised
                    + " new hit(s)", (System.nanoTime() - t0) / 1_000_000L);
        }

        /**
         * One read of the subject columns, ordered by them (deterministic), one row past the cap. A DuckDB error can
         * quote a raw cell, so the failure carries only the error class.
         */
        private static QueryExecutor.Result read(Params p, String relation, List<String> columns) {
            MeasureCompiler.Spec spec = new MeasureCompiler.Spec(p.dataset(), List.of(), columns, Map.of(), List.of(),
                    List.of(), p.maxRows() + 1);
            MeasureCompiler.Compiled compiled = MeasureCompiler.compile(spec);
            try {
                return QueryExecutor.run(new QueryExecutor.Request(p.dataset(), relation, compiled.sql(),
                        p.maxRows() + 1, 0, List.of(), List.of(), compiled.params()), SqlSandboxPolicy.defaultPolicy());
            } catch (Exception e) {
                throw new IllegalStateException(ID + ": reading dataset '" + p.dataset() + "' failed ("
                        + e.getClass().getSimpleName() + "; details withheld because they may quote source values) - "
                        + "check the column names " + columns);
            }
        }

        private static String str(Map<String, Object> row, String col) {
            if (col == null) return null;
            Object v = row.get(col);
            if (v == null) return null;
            String s = String.valueOf(v);
            return s.isBlank() ? null : s;
        }

        private static String masked(EvidenceMasker masker, String dataset, String col, String value) {
            if (col == null || value == null) return null;
            return String.valueOf(masker.mask(dataset, col, value));
        }
    }

    /** The Job's validated params. */
    record Params(String dataset, String keyField, String nameField, String idField, List<String> lists,
                  double threshold, int maxRows) {

        static Params of(Map<String, String> raw) {
            String dataset = req(raw, "dataset");
            String keyField = col(raw, "keyField", true);
            String nameField = col(raw, "nameField", false);
            String idField = col(raw, "idField", false);
            if (nameField == null && idField == null)
                throw new IllegalArgumentException(ID + ": set 'nameField' and/or 'idField'");
            List<String> lists = new ArrayList<>();
            for (String s : req(raw, "lists").split(",")) {
                String id = s.trim();
                if (id.isEmpty()) continue;
                if (!com.gamma.entitystore.EntityListFacts.LIST_ID.matcher(id).matches())
                    throw new IllegalArgumentException(ID + ": 'lists' holds an invalid Entity List id '" + id + "'");
                lists.add(id);
            }
            if (lists.isEmpty()) throw new IllegalArgumentException(ID + ": 'lists' names no Entity List");
            double threshold;
            try {
                threshold = ScreeningRoutes.threshold(raw.get("threshold"));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(ID + ": 'threshold' must be a number in "
                        + Screener.MIN_THRESHOLD + "..1.0");
            }
            int maxRows = DEFAULT_MAX_ROWS;
            String mr = raw.get("maxRows");
            if (mr != null && !mr.isBlank()) {
                try {
                    maxRows = Integer.parseInt(mr.trim());
                } catch (NumberFormatException e) {
                    maxRows = -1;
                }
                if (maxRows < 1 || maxRows > MAX_ROWS_CEILING)
                    throw new IllegalArgumentException(ID + ": 'maxRows' must be an integer in 1.." + MAX_ROWS_CEILING);
            }
            return new Params(dataset, keyField, nameField, idField, List.copyOf(lists), threshold, maxRows);
        }

        List<String> columns() {
            List<String> out = new ArrayList<>(List.of(keyField));
            if (nameField != null && !out.contains(nameField)) out.add(nameField);
            if (idField != null && !out.contains(idField)) out.add(idField);
            return out;
        }

        private static String req(Map<String, String> raw, String k) {
            String v = raw.get(k);
            if (v == null || v.isBlank()) throw new IllegalArgumentException(ID + ": param '" + k + "' is required");
            return v.trim();
        }

        private static String col(Map<String, String> raw, String k, boolean required) {
            String v = raw.get(k);
            if (v == null || v.isBlank()) {
                if (required) throw new IllegalArgumentException(ID + ": param '" + k + "' is required");
                return null;
            }
            if (!SAFE_COL.matcher(v.trim()).matches())
                throw new IllegalArgumentException(ID + ": '" + k + "' must be a plain column name");
            return v.trim();
        }
    }
}
