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
 * receipts off ({@code -Dconsignment.steps=false}) {@code steps} is absent and {@code known.steps=false}. 404 when no
 * store knows the id — never an empty document.
 *
 * <p><b>The {@code run_id} join.</b> A job run row does not name its own Consignment, so {@code downstream[]} (the
 * runs that read this one) and {@code upstream[]} (what this one's own run read) are both reached through
 * {@link DbConsignmentOutputStore#consignmentsOfRun} and the receipts' / outputs' {@code run_id}: ingest → job →
 * back is one hop each way.
 *
 * <p><b>Masking.</b> Signal messages are served as {@code GET /signals} serves them (scrubbed of secrets when they
 * were emitted) and scrubbed again; a receipt's {@code diag.msg} is a raw exception message on the job lane, so its
 * quoted literals and echoed lines are replaced as {@link FailureText} replaces them on the ingest lane, then
 * secrets scrubbed. Member files are the ledger's own rows, as {@code GET /runs/{name}/files} serves them.
 *
 * <p>Gated on {@code canOperateRuns}, the run-operations capability of {@code /runs/{name}/drain} and
 * {@code /reprocess}: a trail carries diagnostics and the cross-lane graph of one Consignment.
 */
final class ConsignmentRoutes implements RouteModule {

    /** Bounds on the collections the read returns; each reports its true total beside it. */
    static final int MAX_OUTPUTS = 200;
    static final int MAX_STEPS = 500;
    static final int MAX_MEMBERS = 200;
    static final int MAX_SIGNALS = 50;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    @Override
    public void register(ApiContext api) {
        api.get("/consignments/([^/]+)", ApiContext.withCapability("canOperateRuns", (e, m) ->
                trail(api, ApiContext.name(m))));
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

        List<StepReceipt> steps = stepsKnown ? store.steps(id) : List.of();
        List<ConsignmentOutput> outputs = store == null ? List.of() : store.outputs(id);
        List<Map<String, Object>> readers = runs.map(r -> r.runsDerivedFrom(id)).orElse(List.of());
        String hint = !steps.isEmpty() ? steps.get(0).pipeline() : outputs.isEmpty() ? null : outputs.get(0).producer();
        Ledger ledger = ledger(svc, id, hint);
        if (ledger == null && steps.isEmpty() && outputs.isEmpty() && readers.isEmpty())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no Consignment '" + id + "'");

        // This Consignment's own job runs: the job lane's receipts first, then the registry's run_id.
        Set<String> ownRuns = new LinkedHashSet<>();
        for (StepReceipt r : steps)
            if (StepReceipt.LANE_JOB.equals(r.lane()) && r.runId() != null && !r.runId().isBlank()) ownRuns.add(r.runId());
        for (ConsignmentOutput o : outputs) if (o.runId() != null && !o.runId().isBlank()) ownRuns.add(o.runId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consignmentId", id);
        out.put("pipeline", ledger != null ? ledger.cfg().identity().pipelineName() : hint);
        out.put("status", ledger != null ? ledger.row().get("status") : jobStatus(svc, ownRuns));
        out.put("lanes", steps.stream().map(StepReceipt::lane).distinct().toList());
        if (ledger != null) {
            List<Map<String, String>> members = svc.statusStore().files(ledger.cfg()).stream()
                    .filter(f -> id.equals(f.get("consignment_id"))).toList();
            out.put("members", members.size() > MAX_MEMBERS ? members.subList(0, MAX_MEMBERS) : members);
            out.put("membersTotal", members.size());
        }
        if (stepsKnown) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (StepReceipt r : steps.size() > MAX_STEPS ? steps.subList(0, MAX_STEPS) : steps) rows.add(stepView(r));
            out.put("steps", rows);
            out.put("stepsTotal", steps.size());
        }
        if (store != null) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ConsignmentOutput o : outputs.size() > MAX_OUTPUTS ? outputs.subList(0, MAX_OUTPUTS) : outputs)
                rows.add(RunRoutes.outputRow(o));
            out.put("outputs", rows);
            out.put("outputsTotal", outputs.size());
        }
        if (runs.isPresent()) {
            out.put("upstream", upstream(runs.get(), ownRuns, id));
            out.put("downstream", downstream(store, readers));
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

    /** The first registered Pipeline whose ingest ledger holds {@code id}, the {@code hint} Pipeline tried first. */
    private static Ledger ledger(CollectorService svc, String id, String hint) {
        List<PipelineConfig> configs = new ArrayList<>(svc.loadedPipelines());
        if (hint != null) svc.configFor(hint).ifPresent(cfg -> configs.add(0, cfg));
        for (PipelineConfig cfg : configs) {
            for (Map<String, String> row : svc.statusStore().batches(cfg))
                if (id.equals(row.get("consignment_id"))) return new Ledger(cfg, row);
        }
        return null;
    }

    /** A job-lane Consignment's status: its newest run the live-run registry still holds, else {@code null}. */
    private static String jobStatus(CollectorService svc, Set<String> ownRuns) {
        Optional<JobService> jobs = svc.jobService();
        if (jobs.isEmpty()) return null;
        List<String> newestFirst = new ArrayList<>(ownRuns);
        java.util.Collections.reverse(newestFirst);
        for (String runId : newestFirst) {
            Optional<JobRun> run = jobs.get().runById(runId);
            if (run.isPresent()) return run.get().status();
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

    /** What this Consignment's own job run(s) read (X2), each once, never itself. */
    private static List<Map<String, Object>> upstream(DbJobRunStore runs, Set<String> ownRuns, String id) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (String runId : ownRuns) {
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

    /** The runs that read this Consignment (X2), each with ITS OWN Consignment id through the {@code run_id} join —
     *  {@code null} when that run left no receipt and no output (or the registry is off). */
    private static List<Map<String, Object>> downstream(DbConsignmentOutputStore store, List<Map<String, Object>> readers) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : readers) {
            String runId = r.get("runId") == null ? null : String.valueOf(r.get("runId"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", runId);
            m.put("job", r.get("job"));
            m.put("status", r.get("status"));
            List<String> own = store == null ? List.of() : store.consignmentsOfRun(runId);
            m.put("consignmentId", own.isEmpty() ? null : own.get(0));
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
