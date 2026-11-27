package com.gamma.insights;

import com.gamma.control.BiSpecRunner;
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
import com.gamma.pipeline.SharingEnvelope;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.signal.Severity;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code insight.scan} Job Type (BI-INSIGHTS-1 I-8, plan §11.9): scans one Insight Profile as of the last complete
 * bucket and appends the run's Insights to the Dataset {@code insights_<profile>}.
 *
 * <ul>
 *   <li><b>Runs as its author</b>, as {@code kpi.snapshot}: a Profile or source Dataset the author may not view refuses
 *       the run, indistinguishably from an absent one; the Profile is the saved Job's (a run may not override it).</li>
 *   <li><b>Never reveals</b>: every statement runs through {@link BiSpecRunner#runAsJob} as a reader who may not reveal —
 *       a Measure over a masked column refuses the run, a classified slice value is stored as its alias.</li>
 *   <li><b>A system Dataset</b> whose sharing envelope admits only who may read both the Profile and its Dataset,
 *       registered (or re-enveloped) before anything is written.</li>
 * </ul>
 */
public final class InsightScanJobType implements JobTypeProvider {

    public static final String TYPE = "insight.scan";
    public static final String COMPLETED = "insight.scan.completed";
    public static final String FOUND = "insight.found";
    static final String P_PROFILE = "profile";
    static final int MAX_FOUND_SIGNALS = 20;

    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor(TYPE, "Insight scan",
                "Scans an Insight Profile's Measure across its dimensions for spikes, level shifts and steady drifts, "
                        + "ranks them by impact, surprise and novelty, and stores them as the Dataset insights_<profile>; "
                        + "with the Profile's 'pairs' on, also explains the last bucket's change by pairs of dimensions.",
                List.of(ParameterDecl.required(P_PROFILE, ParamType.STRING, "The Insight Profile to scan")),
                List.of(COMPLETED, FOUND), List.of(), List.of());
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
            String id = cfg.params().get(P_PROFILE);
            if (id == null || id.isBlank()) throw new IllegalArgumentException(TYPE + " needs parameter '" + P_PROFILE + "'");
            String profileId = id.trim();
            String asked = ctx.params().get(P_PROFILE);
            if (asked != null && !asked.isBlank() && !asked.trim().equals(profileId))
                throw new SecurityException("'" + P_PROFILE + "' comes from the saved Job only; a run may not override it — refused");
            if (ctx.dryRun()) return JobResult.ok("dry run: Insights of '" + profileId + "' were NOT written", ms(t0));

            Path writeRoot = SpaceConfigRoot.requireCurrent(TYPE);
            Path data = SpaceConfigRoot.currentDataRoot();
            if (data == null) throw new IllegalStateException(TYPE + " needs a data root (space dataDir)");
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            var authority = PostgresPublishJobType.authority();
            PostgresPublishJobType.Author author = authority == null ? PostgresPublishJobType.Author.OPEN : authority.authorOf(cfg);
            Map<String, Object> content = store.get(InsightProfile.KIND, profileId).map(ComponentRegistry.Component::content).orElse(null);
            if (content == null || !PostgresPublishJobType.canView(author, content))
                throw new SecurityException("insight-profile '" + profileId + "' does not exist or the Job's author '"
                        + author.id() + "' may not view it — refused");
            InsightProfile p = InsightProfile.parse(profileId, content);
            Map<String, Object> dataset = store.get("dataset", p.dataset()).map(ComponentRegistry.Component::content).orElse(null);
            if (dataset == null || !PostgresPublishJobType.canRead(author, store, dataset))
                throw new SecurityException("dataset '" + p.dataset() + "' does not exist or the Job's author '"
                        + author.id() + "' may not read it — refused");

            Map<String, Object> envelope = envelope(store, content, dataset);
            Path dir = InsightStore.claim(data, profileId);
            String datasetId = register(store, profileId, envelope);

            LocalDate asOf = LocalDate.now(BiSpecRunner.zone(writeRoot));
            InsightScan.Buckets b = InsightScan.buckets(p.grain(), asOf, p.lookback());
            InsightScan.Fetcher fetcher = body -> BiSpecRunner.runAsJob(writeRoot, data, p.dataset(), dataset,
                    BiSpecRunner.parseForJob(writeRoot, body));
            InsightScan.Fetched f = InsightScan.fetch(p, b, fetcher);
            Instant at = Instant.now();
            // depth 2 (BI-EXPLAIN-DEPTH2-1): after the series fetch, so a masked Measure has already refused the run
            Map<String, Object> pairs = p.pairs() ? PairExplain.compute(p, b, fetcher, at) : null;
            String runId = at.toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
            Out out;
            synchronized (InsightStore.lock(dir)) {
                out = store(dir, p, f, runId, at);
                if (pairs != null) PairExplain.write(dir, pairs, at);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("job", cfg.name());
            payload.put("profile", profileId);
            payload.put("dataset", datasetId);
            payload.put("insights", out.result().insights().size());
            payload.put("new", out.fresh().size());
            payload.put("tests", out.result().tests());
            ctx.signals().emit(COMPLETED, Severity.INFO, payload);
            int sent = 0;
            for (InsightScan.Insight i : out.fresh()) {
                if (sent >= MAX_FOUND_SIGNALS || "normal".equals(i.band())) continue;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("profile", profileId);
                s.put("insight", i.id());
                s.put("type", i.type());
                s.put("dimension", i.dimension());
                s.put("slice", i.value());
                s.put("period", i.period().toString());
                s.put("score", i.score());
                s.put("band", i.band());
                s.put("sentence", i.explanation().get("sentence"));
                ctx.signals().emit(FOUND, "high".equals(i.band()) ? Severity.WARN : Severity.INFO, s);
                sent++;
            }
            StringBuilder msg = new StringBuilder(TYPE + ": " + out.result().insights().size() + " Insight(s) ("
                    + out.fresh().size() + " new) from " + out.result().tests() + " test(s) in Dataset '" + datasetId + "'");
            if (out.result().expectedDropped() > 0) msg.append("; ").append(out.result().expectedDropped()).append(" dated on an expected calendar day");
            if (out.result().suppressed() > 0) msg.append("; ").append(out.result().suppressed()).append(" marked not useful or expected");
            if (pairs != null) msg.append("; ").append(((List<?>) pairs.get("drivers")).size())
                    .append(" pair(s) of dimensions explained for ").append(pairs.get("current"))
                    .append(", ").append(((List<?>) pairs.get("refused")).size()).append(" refused");
            for (Map<String, Object> s : f.skipped()) msg.append("; skipped '").append(s.get("dimension")).append("': ").append(s.get("reason"));
            return JobResult.ok(msg.toString(), ms(t0));
        }
    }

    record Out(InsightScan.Result result, List<InsightScan.Insight> fresh) {}

    /** Scans with novelty and feedback from the store, then rewrites it with the run appended. */
    static Out store(Path dir, InsightProfile p, InsightScan.Fetched f, String runId, Instant at) throws Exception {
        List<Map<String, Object>> history = InsightStore.read(dir);
        Map<String, String> feedback = InsightStore.statuses(dir);
        // the last noveltyRuns runs, newest first → the keys each reported
        Map<String, String> runAt = new HashMap<>();
        Map<String, Set<String>> keysByRun = new HashMap<>();
        Map<String, String> firstSeen = new HashMap<>();
        for (Map<String, Object> r : history) {
            String run = String.valueOf(r.get("run_id")), key = String.valueOf(r.get("insight_key"));
            runAt.put(run, String.valueOf(r.get("run_at")));
            keysByRun.computeIfAbsent(run, k -> new HashSet<>()).add(key);
            String fs = r.get("first_seen") == null ? null : String.valueOf(r.get("first_seen"));
            if (fs != null) firstSeen.merge(key, fs, (a, c) -> Instant.parse(a).isAfter(Instant.parse(c)) ? c : a);
        }
        List<String> recent = new ArrayList<>(runAt.keySet());
        recent.sort(Comparator.comparing((String r) -> Instant.parse(runAt.get(r))).reversed());
        List<String> window = recent.subList(0, Math.min(p.noveltyRuns(), recent.size()));
        InsightScan.Result res = InsightScan.scan(p, f, 0, f.buckets().size(), key -> {
            int n = 0;
            for (String r : window) if (keysByRun.get(r).contains(key)) n++;
            return n;
        }, feedback);
        List<InsightScan.Insight> fresh = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : history) {          // re-stamp old rows with the current feedback
            String s = feedback.get(String.valueOf(r.get("insight_key")));
            if (s != null) r.put("status", s);
            rows.add(r);
        }
        for (InsightScan.Insight i : res.insights()) {
            if (!firstSeen.containsKey(i.key())) fresh.add(i);
            rows.add(row(p, i, runId, at, firstSeen.getOrDefault(i.key(), at.toString())));
        }
        InsightStore.write(dir, rows, at);
        return new Out(res, fresh);
    }

    static Map<String, Object> row(InsightProfile p, InsightScan.Insight i, String runId, Instant at, String firstSeen) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("insight_id", i.id());
        r.put("profile_id", p.id());
        r.put("run_id", runId);
        r.put("run_at", at.toString());
        r.put("insight_key", i.key());
        r.put("insight_type", i.type());
        r.put("measure", p.measureId());
        r.put("dimension", i.dimension());
        r.put("slice_value", i.value());
        r.put("period", i.period().toString());
        r.put("value", i.observed());
        r.put("expected", i.expected());
        r.put("delta", i.delta());
        r.put("impact", i.impact());
        r.put("p", i.p());
        r.put("q", i.q());
        r.put("score", i.score());
        r.put("band", i.band());
        r.put("explanation", InsightStore.json(i.explanation()));
        r.put("query", InsightStore.json(i.query()));
        r.put("status", i.status());
        r.put("first_seen", firstSeen);
        return r;
    }

    /**
     * Readable only by who may read the Profile, its Dataset and — for a Mapping — its base chain (as KPI History);
     * shared differently, or a base that does not resolve → refused.
     */
    static Map<String, Object> envelope(ComponentStore store, Map<String, Object> profile, Map<String, Object> dataset) {
        try {
            return SharingEnvelope.of(store, List.of(profile, dataset));
        } catch (SecurityException e) {
            throw new SecurityException("the Insight Profile, its Dataset and a Mapping's base are not shared alike ("
                    + e.getMessage() + "); the Insights would show one's values to another's readers — share them "
                    + "alike, or leave all but one unshared — refused");
        }
    }

    /** Registers (or re-envelopes) {@code insights_<profile>}; a Dataset of that id over another store is refused. */
    static String register(ComponentStore store, String profileId, Map<String, Object> source) throws IOException {
        String id = InsightStore.store(profileId);
        Map<String, Object> existing = store.get("dataset", id).map(ComponentRegistry.Component::content).orElse(null);
        if (existing != null && !id.equals(existing.get("physicalRef")))
            throw new IllegalStateException("dataset '" + id + "' exists and does not read the Insights store — refused");
        Map<String, Object> content = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        if (existing == null) {
            content.put("physicalRef", id);
            content.put("description", "Insights of Insight Profile '" + profileId + "', one row per Insight per run, "
                    + "written by the " + TYPE + " Job.");
            content.put("origin", "system");
            content.put("tags", List.of("insights"));
        }
        for (String k : List.of("owner", "shares")) {
            if (source.containsKey(k)) content.put(k, source.get(k));
            else content.remove(k);
        }
        if (!content.equals(existing)) store.write("dataset", id, content);
        return id;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }
}
