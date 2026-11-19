package com.gamma.control;

import com.gamma.etl.ConsignmentEvent;
import com.gamma.event.EventLog;
import com.gamma.expectation.ExpectationRunner;
import com.gamma.onboard.ImportFollowOn;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.service.CollectorService.CommitPhase;
import com.gamma.service.SpaceContext;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The {@code follow_on} step of an import Pipeline</b> ({@code BASIC-ELT-PROFILE-1} BE-1a; BASIC-ONBOARD-1 P1, DT-15,
 * DT-16, LA-DATASET-COMPACT-2): compaction, each installed application's {@link ImportFollowOn} under its key, then the
 * Expectations that target the Pipeline - in that order, ONE implementation for both ways an import's Pipeline commits:
 * <ul>
 *   <li><b>Inside Add data's run</b> (Add data, Add a file, Run now, Watch this folder): the run claims the Dataset in
 *       {@link OnboardImport#IN_FLIGHT} and calls {@link #runAll} after its run; the outcomes land in the person's answer
 *       ({@code compaction}, e.g. {@code index}, {@code expectations}).</li>
 *   <li><b>A run that did not pass through Add data</b> (a kept-up-to-date feed's scheduled poll, a {@code POST
 *       /pipelines/{id}/run}): each step is a registered {@link CollectorService#commitStep commit step} - compaction in
 *       {@link CommitPhase#COMMIT} (every Consignment, before the Space's Jobs see it), the follow-ons and the Expectations in
 *       {@link CommitPhase#RUN} (once per run, after its last Consignment and outside ingest: a link index build waits for
 *       ingest to end, so it cannot run on the publishing thread). Each claims the Dataset while it works and leaves a
 *       Dataset someone else holds alone - Add data's own run does the work itself, so it never runs twice.</li>
 * </ul>
 * Both ways run only when the Pipeline's {@code import_steps} names {@code follow_on}; a Pipeline with no
 * {@code import_steps} (written before BASIC-ONBOARD-1 P1) runs all four steps.
 */
final class ImportCommitSteps {

    private ImportCommitSteps() {}

    private static final Logger log = LoggerFactory.getLogger(ImportCommitSteps.class);

    /** One step's work for one import, its outcome put under its summary key in {@code out}. */
    interface Work {
        void run(OnboardImport.Space space, String id, Map<String, Object> entry, Map<String, Object> out);
    }

    /** A step, under the name and order it is registered with and the phase a scheduled commit runs it in. */
    record Step(String name, int order, CommitPhase phase, Work work) {}

    /** The steps, sorted by {@code order}: the order both ways run them (a scheduled commit: COMMIT before RUN, then by order). */
    static final List<Step> STEPS = java.util.stream.Stream.of(
            // LA-DATASET-COMPACT-1 (D-7): merge the files once there are many, before a follow-on reads them
            new Step("import.compaction", 100, CommitPhase.COMMIT, (s, id, entry, out) ->
                    out.put("compaction", DatasetCompaction.afterImport(s.writeRoot(), s.dataRoot(), id, entry, s.actor()))),
            new Step("import.follow_on", 200, CommitPhase.RUN, ImportCommitSteps::followOns),
            new Step("import.expectations", 300, CommitPhase.RUN, (s, id, entry, out) -> out.put("expectations", expectations(s, id))))
            .sorted(java.util.Comparator.comparingInt(Step::order)).toList();

    /** The control planes whose Spaces' commits are looked at. */
    private static final Set<ApiContext> ATTACHED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Called once per ControlApi (from {@link OnboardRoutes#register}); registering the same names again replaces them. */
    static void register(ApiContext api) {
        ATTACHED.add(api);
        api.onClose(() -> ATTACHED.remove(api));
        for (Step step : STEPS)
            CollectorService.commitStep(step.name(), step.order(), step.phase(), (service, event) -> afterCommit(step, service, event));
    }

    /**
     * Add data's in-run path: every step, in order, for the import that {@code space}'s caller has claimed; the outcomes go
     * into {@code summary}. {@code entry} is the Dataset's registry entry as it stands after the run.
     */
    static void runAll(OnboardImport.Space space, String id, Map<String, Object> entry, Map<String, Object> summary) {
        if (!followsOn(space.writeRoot(), id)) return;
        for (Step step : STEPS) step.work().run(space, id, entry, summary);
    }

    /** Whether the import's Pipeline runs its {@code follow_on} step: its {@code import_steps} names it, or it has none. */
    static boolean followsOn(Path writeRoot, String id) {
        Path pipeline = writeRoot.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon");
        try {
            return !(ToonHelper.load(pipeline.toString()).get("import_steps") instanceof List<?> steps) || steps.contains("follow_on");
        } catch (IOException | RuntimeException unreadable) {
            return true;   // the Pipeline ran from this file a moment ago: read as an import's default, all four steps
        }
    }

    // ── a commit outside Add data ──────────────────────────────────────────────────────────────────────────────

    private static void afterCommit(Step step, CollectorService service, ConsignmentEvent event) {
        if (!"SUCCESS".equals(event.status()) || event.dryRun()) return;
        String id = event.pipeline();   // an import's Pipeline is named as its Dataset (lower case, as the event says it)
        for (ApiContext api : ATTACHED)
            for (SpaceContext ctx : HostContext.of(api).spaces().all())
                if (ctx.service() == service) afterCommit(step, api, ctx.id().value(), service, id);
    }

    private static void afterCommit(Step step, ApiContext api, String space, CollectorService service, String id) {
        String prev = MDC.get(EventLog.SPACE_MDC_KEY);
        MDC.put(EventLog.SPACE_MDC_KEY, space);   // the Space's roots, as a request of that Space reads them
        String key = null;
        try {
            Path writeRoot = api.writeRoot(), dataRoot = api.dataRoot();
            if (writeRoot == null || dataRoot == null) return;
            Map<String, Object> content = new ComponentStore(writeRoot.resolve("registry")).get("dataset", id)
                    .map(ComponentRegistry.Component::content).orElse(null);
            if (content == null || !OnboardImport.isOurs(writeRoot, id, content, service)) return;
            String claim = writeRoot + "|" + id;
            if (!OnboardImport.IN_FLIGHT.add(claim)) return;   // an import, Compact now or a delete holds it: that holder does it
            key = claim;
            if (!followsOn(writeRoot, id)) return;
            Map<String, Object> out = new LinkedHashMap<>();
            step.work().run(new OnboardImport.Space(writeRoot, dataRoot, service, "scheduler"), id, content, out);
            if (!quiet(out)) log.info("Dataset '{}' after its run ({}): {}", id, step.name(), out);
        } catch (RuntimeException e) {
            log.warn("Dataset '{}' after its run ({}) failed: {}", id, step.name(), e.toString());
        } finally {
            if (key != null) OnboardImport.IN_FLIGHT.remove(key);
            if (prev == null) MDC.remove(EventLog.SPACE_MDC_KEY);
            else MDC.put(EventLog.SPACE_MDC_KEY, prev);
        }
    }

    /** Nothing worth a log line: a compaction that was not needed, no Expectation. */
    private static boolean quiet(Map<String, Object> out) {
        for (Object o : out.values())
            if (!(o instanceof Map<?, ?> m && "NOT_NEEDED".equals(m.get("result"))) && !(o instanceof List<?> l && l.isEmpty())) return false;
        return true;
    }

    // ── the steps ──────────────────────────────────────────────────────────────────────────────────────────────

    private static void followOns(OnboardImport.Space space, String id, Map<String, Object> entry, Map<String, Object> out) {
        for (ImportFollowOn f : OnboardImport.followOns()) {
            Map<String, Object> o;
            try {
                o = f.run(new ImportFollowOn.Context(space.writeRoot(), space.dataRoot(), id, entry, space.actor()));
            } catch (RuntimeException failed) {
                o = new LinkedHashMap<>();
                o.put("result", "FAILED");
                o.put("message", "'" + f.key() + "' could not run: " + failed.getMessage());
            }
            out.put(f.key(), o);
        }
    }

    /** DT-15: every enabled Expectation whose target is this Pipeline, evaluated now. */
    private static List<Map<String, Object>> expectations(OnboardImport.Space space, String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        ComponentStore store = new ComponentStore(space.writeRoot().resolve("registry"));
        ExpectationRunner runner = new ExpectationRunner(space.writeRoot(), space.dataRoot(),
                space.service().objects().orElse(null), msg -> msg);
        for (ComponentRegistry.Component c : store.list(ExpectationRunner.TYPE)) {
            Map<String, Object> e = c.content();
            if (!"pipeline".equalsIgnoreCase(String.valueOf(e.get("targetType"))) || !id.equalsIgnoreCase(String.valueOf(e.get("target")))
                    || "false".equalsIgnoreCase(String.valueOf(e.getOrDefault("enabled", true)))) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", e.get("name"));
            try {
                Map<String, Object> next = runner.evaluateOne(e);
                Object last = next.get("lastResult");
                r.put("status", last instanceof Map<?, ?> lm ? lm.get("status") : null);
                r.put("violations", last instanceof Map<?, ?> lm ? lm.get("violations") : null);
            } catch (IOException | RuntimeException failed) {
                r.put("status", "ERROR");
                r.put("message", failed.getMessage());
            }
            out.add(r);
        }
        return out;
    }
}
