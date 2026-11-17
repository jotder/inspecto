package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.ConsignmentSource;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.FailureText;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.SecretScrubber;
import com.gamma.job.DbJobRunStore;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.pipeline.exec.StepReceipt;
import com.gamma.service.CollectorService;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code GET /consignments/{consignmentId}} — one Consignment across every lane (X5 L4, plan
 * {@code docs/superpower/2026-11-17-x5-stepinfo-envelope-plan.md} §3.7, D8 = a). It joins what five stores know
 * about one id: the ingest ledger row (status, member files, park detail), its Step Receipts
 * ({@code consignment_steps}), its registered outputs ({@code consignment_outputs}), the job runs that read it and
 * the run that wrote it (the run store's X2 sources), and its Signals ({@code correlationId} = the id).
 *
 * <p><b>Absent is not empty.</b> A store that is switched off cannot say, so its key is ABSENT and
 * {@code known.<store>} is {@code false} — never a {@code []} a screen would read as "nothing happened". With
 * receipts off ({@code -Dconsignment.steps=false}) {@code steps} and {@code lanes} are absent and
 * {@code known.steps=false}. 404 when no store knows the id — never an empty document.
 *
 * <p><b>The {@code run_id} join.</b> A job run row does not name its own Consignment, so {@code downstream[]} (the
 * runs that read this one) and {@code upstream[]} (what this one's own run read) are both reached through
 * {@link DbConsignmentOutputStore#consignmentsOfRuns} (one pair of queries for every reader) and the receipts' /
 * outputs' {@code run_id}: ingest → job → back is one hop each way.
 *
 * <p><b>Bounded.</b> Every collection is cut in its read ({@code LIMIT}) and reports its true total beside it; the
 * ledger is looked up by id (the hint Pipeline first, each Pipeline once), never loaded whole by a DB-backed store.
 *
 * <p><b>Masking.</b> Signal messages are served as {@code GET /signals} serves them (scrubbed of secrets when they
 * were emitted) and scrubbed again; a receipt's {@code diag.msg} is a raw exception message on the job lane, so its
 * quoted literals and echoed lines are replaced as {@link FailureText} replaces them on the ingest lane, then
 * secrets scrubbed. Member files are the ledger's own rows, as {@code GET /runs/{name}/files} serves them.
 *
 * <p>An open read under Space admission, like its siblings {@code /runs/{name}/batches}, {@code /files},
 * {@code /outputs} and {@code /errors} (operator decision on the L4 review, 2026-11-17).
 */
final class ConsignmentRoutes implements RouteModule {

    /** Bounds on the collections the read returns; each reports its true total beside it. */
    static final int MAX_OUTPUTS = 200;
    static final int MAX_STEPS = 500;
    static final int MAX_MEMBERS = 200;
    static final int MAX_SIGNALS = 50;
    static final int MAX_HOPS = 50;
    /** How many of this Consignment's own job runs (newest) are examined for upstream links and status. */
    static final int MAX_OWN_RUNS = 20;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    @Override
    public void register(ApiContext api) {
        api.get("/consignments/([^/]+)", (e, m) -> trail(api, ApiContext.name(m)));
    }

    /** The ingest ledger row of one Consignment and the Pipeline it belongs to. */
    private record Ledger(PipelineConfig cfg, Map<String, String> row) {}

    private static Object trail(ApiContext api, String id) {
        if (id == null || id.isBlank())
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "consignmentId is required");
        CollectorService svc = HostContext.of(api).service();
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        boolean stepsKnown = store != null && ConsignmentOutputStores.stepsEnabled();
        Optional<DbJobRunStore> runs = svc.jobService().flatMap(JobService::runStore);

        List<StepReceipt> steps = stepsKnown ? store.steps(id, MAX_STEPS) : List.of();
        List<ConsignmentOutput> outputs = store == null ? List.of() : store.outputs(id, MAX_OUTPUTS);
        List<Map<String, Object>> readers = runs.map(r -> r.runsDerivedFrom(id, MAX_HOPS)).orElse(List.of());
        String hint = !steps.isEmpty() ? steps.get(0).pipeline() : outputs.isEmpty() ? null : outputs.get(0).producer();
        Ledger ledger = ledger(svc, id, hint);
        if (ledger == null && steps.isEmpty() && outputs.isEmpty() && readers.isEmpty())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no Consignment '" + id + "'");

        List<String> ownRuns = ownRuns(steps, outputs);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consignmentId", id);
        out.put("pipeline", ledger != null ? ledger.cfg().identity().pipelineName() : hint);
        out.put("status", ledger != null ? ledger.row().get("status") : jobStatus(svc, runs, ownRuns));
        if (stepsKnown) out.put("lanes", steps.stream().map(StepReceipt::lane).distinct().toList());
        if (ledger != null) {
            List<Map<String, String>> members = svc.statusStore().consignmentFiles(ledger.cfg(), id, MAX_MEMBERS);
            out.put("members", members);
            out.put("membersTotal", members.size() < MAX_MEMBERS ? members.size() : memberCount(ledger.row(), members.size()));
        }
        if (stepsKnown) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (StepReceipt r : steps) rows.add(stepView(r));
            out.put("steps", rows);
            out.put("stepsTotal", steps.size() < MAX_STEPS ? steps.size() : store.stepCount(id));
        }
        if (store != null) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ConsignmentOutput o : outputs) rows.add(RunRoutes.outputRow(o));
            out.put("outputs", rows);
            out.put("outputsTotal", outputs.size() < MAX_OUTPUTS ? outputs.size() : store.outputCount(id));
        }
        if (runs.isPresent()) {
            List<Map<String, Object>> up = upstream(runs.get(), ownRuns, id);
            out.put("upstream", up.size() > MAX_HOPS ? up.subList(0, MAX_HOPS) : up);
            out.put("upstreamTotal", up.size());
            out.put("downstream", downstream(store, readers));
            out.put("downstreamTotal", readers.size() < MAX_HOPS ? readers.size() : runs.get().countRunsDerivedFrom(id));
        }
        Map<String, Object> parked = parked(ledger);
        if (parked != null) out.put("parked", parked);
        out.put("signals", signals(svc, id));
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("steps", stepsKnown);
        known.put("runs", runs.isPresent());
        known.put("outputs", store != null);
        out.put("known", known);
        return out;
    }

    /**
     * The ledger row holding {@code id}: the {@code hint} Pipeline first, then each other registered Pipeline once,
     * each asked by id ({@link com.gamma.etl.StatusStore#consignment} — a query on a DB-backed store).
     */
    private static Ledger ledger(CollectorService svc, String id, String hint) {
        Optional<PipelineConfig> hinted = hint == null ? Optional.empty() : svc.configFor(hint);
        if (hinted.isPresent()) {
            Optional<Map<String, String>> row = svc.statusStore().consignment(hinted.get(), id);
            if (row.isPresent()) return new Ledger(hinted.get(), row.get());
        }
        String skip = hinted.map(c -> c.identity().pipelineName()).orElse(null);
        for (PipelineConfig cfg : svc.loadedPipelines()) {
            if (cfg.identity().pipelineName().equals(skip)) continue;
            Optional<Map<String, String>> row = svc.statusStore().consignment(cfg, id);
            if (row.isPresent()) return new Ledger(cfg, row.get());
        }
        return null;
    }

    /** This Consignment's own job runs, oldest first: the job lane's receipts (in flush order), then the registry's
     *  {@code run_id}s (oldest written first); the newest {@value #MAX_OWN_RUNS} are kept. */
    private static List<String> ownRuns(List<StepReceipt> steps, List<ConsignmentOutput> outputs) {
        Set<String> runs = new LinkedHashSet<>();
        for (StepReceipt r : steps)
            if (StepReceipt.LANE_JOB.equals(r.lane()) && r.runId() != null && !r.runId().isBlank()) runs.add(r.runId());
        for (int i = outputs.size() - 1; i >= 0; i--) {   // outputs read newest first
            String run = outputs.get(i).runId();
            if (run != null && !run.isBlank()) runs.add(run);
        }
        List<String> all = new ArrayList<>(runs);
        return all.size() > MAX_OWN_RUNS ? all.subList(all.size() - MAX_OWN_RUNS, all.size()) : all;
    }

    /** {@code member_count} of the ledger row when the member rows were cut, else what was read. */
    private static long memberCount(Map<String, String> row, int read) {
        try {
            return Math.max(read, Long.parseLong(row.getOrDefault("member_count", "").trim()));
        } catch (NumberFormatException unknown) {
            return read;
        }
    }

    /** A job-lane Consignment's status: its newest run's, from the live-run registry, else from the durable run
     *  store (after a restart); {@code null} when neither knows. */
    private static String jobStatus(CollectorService svc, Optional<DbJobRunStore> runs, List<String> ownRuns) {
        Optional<JobService> jobs = svc.jobService();
        for (int i = ownRuns.size() - 1; i >= 0; i--) {
            String runId = ownRuns.get(i);
            Optional<JobRun> live = jobs.flatMap(j -> j.runById(runId));
            if (live.isPresent()) return live.get().status();
            Optional<Map<String, Object>> stored = runs.flatMap(r -> r.run(runId));
            if (stored.isPresent() && stored.get().get("status") != null) return String.valueOf(stored.get().get("status"));
        }
        return null;
    }

    /** One receipt as its stored JSON shape, with {@code diag.msg} masked (see the class Javadoc). */
    private static Map<String, Object> stepView(StepReceipt r) {
        Map<String, Object> view;
        try {
            view = ApiContext.JSON.readValue(r.toJson(), MAP);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unreadable Step Receipt", e);
        }
        if (view.get("diag") instanceof Map<?, ?> diag && diag.get("msg") instanceof String msg) {
            @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) diag;
            d.put("msg", mask(msg));
        }
        return view;
    }

    /** Free text a value may stand in: quoted literals and echoed lines replaced, then secrets scrubbed. No Space salt
     *  is read here (reading it may create it): a replaced value reads {@code <redacted>}, never the value. */
    static String mask(String text) {
        return SecretScrubber.scrub(FailureText.scrub(text, null));
    }

    /** What this Consignment's own job run(s) read (X2), each once, never itself — uncut; the caller caps it. */
    private static List<Map<String, Object>> upstream(DbJobRunStore runs, List<String> ownRuns, String id) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (int i = ownRuns.size() - 1; i >= 0; i--) {   // newest run first
            String runId = ownRuns.get(i);
            for (ConsignmentSource s : runs.sources(runId)) {
                if (id.equals(s.consignmentId()) || byId.containsKey(s.consignmentId())) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("consignmentId", s.consignmentId());
                m.put("pipeline", s.pipeline());
                m.put("tableName", s.tableName());
                m.put("runId", runId);
                byId.put(s.consignmentId(), m);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** The runs that read this Consignment (X2, already cut to {@value #MAX_HOPS}), each with ITS OWN Consignment id
     *  through the {@code run_id} join — {@code null} when that run left no receipt and no output, or the registry is
     *  off. One pair of queries for all of them. */
    private static List<Map<String, Object>> downstream(DbConsignmentOutputStore store, List<Map<String, Object>> readers) {
        List<String> runIds = new ArrayList<>();
        for (Map<String, Object> r : readers) if (r.get("runId") != null) runIds.add(String.valueOf(r.get("runId")));
        Map<String, String> own = store == null ? Map.of() : store.consignmentsOfRuns(runIds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : readers) {
            String runId = r.get("runId") == null ? null : String.valueOf(r.get("runId"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", runId);
            m.put("job", r.get("job"));
            m.put("status", r.get("status"));
            m.put("consignmentId", runId == null ? null : own.get(runId));
            out.add(m);
        }
        return out;
    }

    /** {@code {at, tables}} of a PARKED ingest Consignment (its manifest), as {@code /runs/{name}/batches} reads it. */
    private static Map<String, Object> parked(Ledger ledger) {
        if (ledger == null || !"PARKED".equals(ledger.row().get("status"))) return null;
        Map<String, Object> row = RunRoutes.withParkDetail(ledger.cfg(), List.of(ledger.row())).get(0);
        if (!row.containsKey("parkedAt") && !row.containsKey("parkedTables")) return null;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("at", row.get("parkedAt"));
        p.put("tables", row.get("parkedTables"));
        return p;
    }

    /** The newest {@value #MAX_SIGNALS} Signals correlated to this id, as {@code {type, at, severity, message}}. */
    private static List<Map<String, Object>> signals(CollectorService svc, String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Signal s : Signals.query(svc.events(), null, null, null, null, id, MAX_SIGNALS)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", s.type());
            m.put("at", s.at() == null ? null : s.at().toString());
            m.put("severity", s.severity() == null ? null : s.severity().name().toLowerCase(java.util.Locale.ROOT));
            m.put("message", s.message() == null ? null : SecretScrubber.scrub(s.message()));
            out.add(m);
        }
        return out;
    }
}
