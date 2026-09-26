package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.objects.ObjectAccess;
import com.gamma.objects.ObjectType;
import com.gamma.etl.StatusStore;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The alert execution engine (v4.1, B5) — evaluates operator-saved Alert Rules ({@code alert-rule} components)
 * against the batches ledger and records breaches. This is the runtime half of the agent's
 * draft-only {@code diagnose-and-alert} skill: the agent proposes, a human saves the reviewed
 * {@code .toon}, and THIS deterministic service (no model, lean core) executes it.
 *
 * <h3>Evaluation model</h3>
 * Event-driven: every terminal {@link ConsignmentEvent} (SUCCESS and FAILED both fire — error rates need
 * both) triggers evaluation of the rules scoped to that pipeline (plus unscoped rules) over the
 * ledger window. A manual sweep ({@link #evaluateAll}) backs {@code POST /alerts/evaluate}.
 *
 * <h3>Re-fire suppression</h3>
 * A rule that stays breached would otherwise fire on every batch; a per-rule+pipeline cooldown of
 * the rule's window length (10 minutes for batch-count windows, minimum 1 minute) suppresses
 * duplicates while the condition persists.
 *
 * <p>Fired alerts live in a bounded in-memory ring (newest first, like the diagnosis store);
 * process lifetime, capacity {@value #DEFAULT_CAPACITY}.
 */
public final class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    public static final int DEFAULT_CAPACITY = 1024;
    private static final DateTimeFormatter LEDGER_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Armed rules; swapped atomically by the authoring mutators ({@link #upsert}/{@link #remove}). */
    private volatile List<AlertRule> rules;
    private final ConfigSource configs;
    private final StatusStore status;
    /** Object store for persisting fired alerts as managed objects (Phase 2); {@code null} = events-only. */
    /** The {@code LinkRelationship} name for an escalation edge — the enum itself lives in the module. */
    private static final String ESCALATED_FROM = "ESCALATED_FROM";

    private final ObjectAccess objects;
    /** The {@code incidents} Platform Service view over {@link #objects} (S1-4) — high-severity
     *  promotion opens through the same interface a granted Run uses; {@code null} = events-only. */
    private final com.gamma.objects.IncidentAccess incidents;
    private final Deque<Alert> fired = new ArrayDeque<>();
    private final int capacity;
    private final Map<String, Long> lastFired = new ConcurrentHashMap<>();
    /** BI-5: evaluates {@code (dataset, measure)} → current scalar value; {@code null} disables measure rules. */
    private volatile java.util.function.BiFunction<String, String, java.util.OptionalDouble> measureProbe;
    /** LA-23: an Investigation rule → its Measure over the Working Set ({@link InvestigationMeasureProbe});
     *  {@code null} (no {@code inspecto-geo-link} module) disables Investigation rules. */
    private volatile java.util.function.Function<AlertRule, java.util.OptionalDouble> investigationProbe;
    /** DUCKLE-C1: {@code dataset id} → epoch millis of its last publication; {@code null} disables
     *  freshness rules (which is NOT the same as reporting them fresh — see {@link #evaluateFreshness}). */
    private volatile java.util.function.Function<String, java.util.OptionalLong> freshnessProbe;
    /**
     * DUCKLE-C1: when each currently-stale {@code rule|dataset} first went stale, so {@code stale_since}
     * survives across evaluations and the fresh→stale EDGE is distinguishable from "still stale".
     *
     * <p>⛔ In memory ON PURPOSE, and it must stay that way. {@code stale-tiles.ts:1-34} carries a
     * standing objection to persisting a stale flag anywhere: staleness is a FUNCTION of the clock and
     * the last publication, so a stored flag can only ever disagree with them. This map is a re-fire
     * edge detector with the same lifetime as {@link #lastFired}, not a record of anything.
     */
    private final Map<String, Long> staleSince = new ConcurrentHashMap<>();
    /** See {@link #onRulesChanged}. */
    private volatile Runnable rulesChanged = () -> {};

    public AlertService(List<AlertRule> rules, ConfigSource configs, StatusStore status) {
        this(rules, configs, status, (ObjectAccess) null);
    }

    /**
     * Phase 2: also persist each fired alert as an {@link ObjectType#ALERT}
     * a managed ALERT object through the {@link ObjectAccess} seam. A {@code null} {@code objects}
     * keeps the prior events-only behaviour (the lean path and unit tests).
     */
    public AlertService(List<AlertRule> rules, ConfigSource configs, StatusStore status,
                        ObjectAccess objects) {
        this(rules, configs, status, objects, DEFAULT_CAPACITY);
    }

    AlertService(List<AlertRule> rules, ConfigSource configs, StatusStore status, int capacity) {
        this(rules, configs, status, null, capacity);
    }

    AlertService(List<AlertRule> rules, ConfigSource configs, StatusStore status,
                 ObjectAccess objects, int capacity) {
        this.rules = List.copyOf(rules);
        this.configs = configs;
        this.status = status;
        this.objects = objects;
        this.incidents = objects == null ? null : com.gamma.objects.IncidentAccess.over(() -> objects);
        this.capacity = Math.max(1, capacity);
    }

    /** Wire the BI-5 measure evaluator (BiFunction so this engine stays decoupled from the query layer). */
    public void measureProbe(java.util.function.BiFunction<String, String, java.util.OptionalDouble> probe) {
        this.measureProbe = probe;
    }

    /** Wire the LA-23 Investigation-rule evaluator (a Function so this engine never names the optional module). */
    public void investigationProbe(java.util.function.Function<AlertRule, java.util.OptionalDouble> probe) {
        this.investigationProbe = probe;
    }

    /**
     * Wire the DUCKLE-C1 freshness clock: {@code dataset id} → epoch millis of that Dataset's last
     * publication, empty when it has never published. A {@link java.util.function.Function} for the
     * same reason {@link #measureProbe} is a BiFunction — this engine does not name the event layer.
     */
    public void freshnessProbe(java.util.function.Function<String, java.util.OptionalLong> probe) {
        this.freshnessProbe = probe;
    }

    /** The loaded rules, JSON-ready — backs {@code GET /alerts/rules}. */
    public List<Map<String, Object>> rules() {
        return rules.stream().map(AlertRule::toMap).toList();
    }

    /** True when a rule with this name is armed (the create-conflict / update-exists check). */
    public boolean has(String name) {
        return rules.stream().anyMatch(r -> r.name().equals(name));
    }

    /**
     * Arm a rule at runtime (authoring: {@code POST}/{@code PUT /alerts/rules}), replacing any existing
     * rule of the same name. The name is the identity, so an upsert is add-or-replace. The next batch
     * event (or {@code POST /alerts/evaluate}) evaluates it. Serialised against {@link #evaluate}.
     */
    public void upsert(AlertRule rule) {
        synchronized (this) {
            List<AlertRule> next = new ArrayList<>(rules.size() + 1);
            for (AlertRule r : rules) if (!r.name().equals(rule.name())) next.add(r);
            next.add(rule);
            this.rules = List.copyOf(next);
        }
        rulesChanged.run();
    }

    /** Disarm a rule by name ({@code DELETE /alerts/rules/{name}}); {@code true} if one was armed. */
    public boolean remove(String name) {
        boolean removed;
        synchronized (this) {
            List<AlertRule> next = rules.stream().filter(r -> !r.name().equals(name)).toList();
            removed = next.size() != rules.size();
            this.rules = List.copyOf(next);
        }
        if (removed) rulesChanged.run();
        return removed;
    }

    /**
     * Called after every {@link #upsert}/{@link #remove} — the host re-derives the DUCKLE-C1 freshness
     * sweep from it. ⚠ Run OUTSIDE this engine's monitor, so a listener that takes the job scheduler's
     * lock can never order itself against an evaluation holding this one.
     */
    public void onRulesChanged(Runnable listener) {
        this.rulesChanged = listener == null ? () -> {} : listener;
    }

    /** True while at least one armed rule checks Dataset freshness ({@code maximumAge}). */
    public boolean hasFreshnessRule() {
        return rules.stream().anyMatch(AlertRule::isFreshnessRule);
    }

    /** Recent fired alerts, newest first, JSON-ready — backs {@code GET /alerts}. */
    public synchronized List<Map<String, Object>> recent(int limit) {
        return fired.stream().limit(Math.max(0, limit)).map(Alert::toMap).toList();
    }

    /** Bus subscriber: a terminal batch re-evaluates the rules scoped to its pipeline. */
    public void onEvent(ConsignmentEvent event) {
        // PIPELINE-DRYRUN-1 step 5 — evaluation is not a read: a breach fires an Alert, advances its
        // cooldown and may open an Incident. DryRunServices makes exactly this call for AlertAccess, so
        // this consumer REFUSES loudly for the same reason.
        if (event.dryRun()) {
            log.info("dry run: would evaluate '{}'s Alert Rules after simulated consignment {} — "
                    + "nothing was checked", event.pipeline(), event.batchId());
            return;
        }
        try {
            evaluate(event.pipeline(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            // Alerting must never disturb ingest: log and move on.
            log.warn("alert evaluation failed after batch {}: {}", event.batchId(), e.getMessage());
        }
    }

    /** Evaluate every rule against every (matching) pipeline — backs {@code POST /alerts/evaluate}. */
    public List<Map<String, Object>> evaluateAll() {
        return evaluateRules().stream().map(Alert::toMap).toList();
    }

    /**
     * The typed form of {@link #evaluateAll()} — what the {@code alerts} Platform Service hands a Job
     * ({@link AlertAccess}). {@code evaluateAll}'s map shape stays the JSON projection the route needs;
     * a plugin binds to {@link Alert} instead of to string keys.
     *
     * <p>⚠ Evaluating <b>mutates</b>: a breached rule fires an Alert, advances that rule's cooldown and
     * (at error/critical) promotes to an Incident. There is no preview form — see {@link AlertAccess}'s
     * dry-run contract.
     */
    public List<Alert> evaluateRules() {
        return evaluate(null, System.currentTimeMillis());
    }

    /**
     * DUCKLE-C1 — the <b>freshness-only</b> sweep: evaluate just the {@code maximumAge} rules and
     * nothing else.
     *
     * <h3>Why this exists instead of a dedicated once-a-minute thread</h3>
     * The duckle source asked for freshness to be evaluated "once a minute on its own thread, not the
     * scheduler's". In this codebase that constraint is already met without a thread: the
     * {@code alert.evaluate} Job is the cadence seam, its cron reaches one minute
     * ({@code * * * * *}), and the Job body runs on {@code JobService}'s virtual-thread executor — the
     * two {@code inspecto-scheduler} threads only TRIGGER, so a long sweep never blocks the timer. A
     * dedicated thread would duplicate a cadence that already exists, which is worse than none.
     *
     * <p>What the minute cadence genuinely cost was not threading but <b>scope</b>:
     * {@link #evaluateRules()} also runs the measure pass and the full per-pipeline ledger pass — one
     * {@code status.batches(cfg)} read for EVERY pipeline — so putting it on a one-minute timer to
     * check a Dataset's age re-read every pipeline's ledger 60× an hour. This entry point removes that
     * cost rather than moving it to another thread.
     *
     * <p>⚠ It is deliberately <b>not</b> a preview: like the full sweep it fires, advances cooldowns
     * and emits the all-clear. It is narrower, not gentler.
     */
    public synchronized List<Alert> evaluateFreshnessRules() {
        long nowMs = System.currentTimeMillis();
        List<Alert> out = new ArrayList<>();
        for (AlertRule rule : rules) {
            if (!rule.isFreshnessRule()) continue;
            evaluateFreshness(rule, nowMs, out);
        }
        return out;
    }

    /**
     * Evaluate rules; {@code pipelineFilter} (a pipeline's display or normalized name) restricts to
     * one pipeline's ledger, {@code null} sweeps all. Returns the alerts fired by this pass.
     */
    synchronized List<Alert> evaluate(String pipelineFilter, long nowMs) {
        List<Alert> out = new ArrayList<>();

        // Measure rules (BI-5) are dataset-scoped, not pipeline-scoped: any sweep (a terminal batch may
        // have changed the data, or the manual POST /alerts/evaluate) re-reads the current value; the
        // cooldown suppresses repeats. Skipped silently when no probe is wired (lean/unit paths).
        for (AlertRule rule : rules) {
            if (!rule.isFreshnessRule()) continue;
            evaluateFreshness(rule, nowMs, out);
        }

        for (AlertRule rule : rules) {
            if (!rule.isMeasureRule()) continue;
            var probe = measureProbe;
            if (probe == null) continue;
            java.util.OptionalDouble value = probe.apply(rule.dataset(), rule.measure());
            if (value.isEmpty() || !rule.breached(value.getAsDouble())) continue;
            fire(rule, rule.dataset(), rule.dataset(), value.getAsDouble(), nowMs, out);
        }

        // Investigation rules (LA-23) are scoped to their Investigation: the scope — and so the cooldown key, the
        // Signal's correlation id and the Incident dedupe scope — is the Investigation id. The probe answers empty
        // for anything it cannot vouch for (no owner binding, unknown Investigation), which never fires.
        for (AlertRule rule : rules) {
            if (!rule.isInvestigationRule()) continue;
            var probe = investigationProbe;
            if (probe == null) continue;
            java.util.OptionalDouble value = probe.apply(rule);
            if (value.isEmpty() || !rule.breached(value.getAsDouble())) continue;
            fire(rule, rule.investigation(), rule.investigation(), value.getAsDouble(), nowMs, out);
        }

        for (PipelineConfig cfg : configs.pipelines()) {
            String display = cfg.identity().name();
            String id = cfg.identity().pipelineName();
            if (pipelineFilter != null && !matches(pipelineFilter, display, id)) continue;

            List<Map<String, String>> ledger = null;
            for (AlertRule rule : rules) {
                if (rule.isMeasureRule()) continue;
                // ⛔ Freshness rules are evaluated by their OWN pass above (evaluateFreshness) and must
                // never reach the ledger path: they carry no `window:` by construction (AlertRule refuses
                // one), so inWindow -> batchWindow would NPE on a null window. isMeasureRule() does NOT
                // cover them -- it was deliberately narrowed to `dataset != null && maximumAge == null`
                // because BOTH shapes use `dataset:`, so this skip has to be stated separately.
                if (rule.isFreshnessRule()) continue;
                if (rule.isInvestigationRule()) continue;   // its own pass above; no window, so inWindow would NPE
                if (rule.onPipeline() != null && !matches(rule.onPipeline(), display, id)) continue;
                if (ledger == null) ledger = status.batches(cfg);   // one read per pipeline pass
                List<Map<String, String>> rows = inWindow(rule, ledger, nowMs);
                if (rule.when() != null) rows = filterByWhen(rule.when(), rows);
                if (rows.isEmpty()) continue;
                double value = metricValue(rule.metric(), rows);
                if (!rule.breached(value)) continue;
                fire(rule, display, id, value, nowMs, out);
            }
        }
        return out;
    }

    /**
     * DUCKLE-C1 - evaluate one Dataset freshness rule ON THE CLOCK.
     *
     * <p>This is the whole point of the row: every other rule kind in this service is driven by
     * something HAPPENING (a terminal batch, a measure over data a run just wrote), so a Dataset that
     * simply <em>stops</em> being published - its pipeline disarmed, its schedule removed, its upstream
     * silent - produces no event and therefore no alert. Freshness is the one check whose trigger is the
     * passage of time, so it is evaluated on every sweep whether or not anything ran.
     *
     * <h3>The three states, and why {@code unknown} is not {@code fresh}</h3>
     * <ul>
     *   <li><b>unknown</b> - no probe wired, or the Dataset has never published. Nothing fires and
     *       nothing clears. Do NOT "simplify" this to fresh: a Dataset with no publication history
     *       reading green forever is the exact failure a freshness rule exists to catch. It is not
     *       stale either - there is no {@code stale_since} to name - so it returns silently rather
     *       than guessing.</li>
     *   <li><b>stale</b> - {@code now - lastPublication > maximumAge}. Fires, cooldown-guarded like
     *       every other breach, and stamps {@code stale_since} at the first sweep that saw it.</li>
     *   <li><b>fresh</b> - within {@code maximumAge}. Clears, but only on the stale-to-fresh EDGE.</li>
     * </ul>
     *
     * <p><b>A failed or partial run does not count as a refresh, and that is not enforced here.</b>
     * It is enforced at the emission point: {@code DATASET-PUBLISH-ON-FAILURE-1} (2026-09-15) moved
     * {@code dataset.write} out of {@code persistSummaries} to the end of the whole chain precisely so
     * a write a later step could still abandon is never announced. So this check INHERITS that
     * guarantee from the Signal it reads; it does not re-derive it, and a second implementation here
     * would be a second answer to one question.
     */
    private void evaluateFreshness(AlertRule rule, long nowMs, List<Alert> out) {
        var probe = freshnessProbe;
        if (probe == null) return;                       // unknown: no clock wired (lean / unit paths)
        java.util.OptionalLong last = probe.apply(rule.dataset());
        if (last.isEmpty()) return;                      // unknown: never published - never "fresh"

        String key = rule.name() + "|" + rule.dataset();
        long ageMs = Math.max(0, nowMs - last.getAsLong());
        if (ageMs > rule.maximumAgeDuration().toMillis()) {
            staleSince.putIfAbsent(key, nowMs);          // carries across evaluations; first sweep wins
            // The reported VALUE is the age in seconds, so the alert feed shows how far past the limit
            // it is rather than a bare boolean.
            fire(rule, rule.dataset(), rule.dataset(), ageMs / 1000.0, nowMs, out);
        } else if (staleSince.remove(key) != null) {
            clear(rule, rule.dataset(), key, ageMs, nowMs);
        }
    }

    /**
     * The <b>all-clear</b> (DUCKLE-C1) - a condition that had fired has recovered.
     *
     * <p>Before this, {@code AlertService} was <b>fire-only</b>: there was no recovery path in any
     * form, and the cooldown only suppressed re-fires. That is why a clock-based freshness rule could
     * not be built as a variation on the {@code maximumAge} comparison alone - without a recovery edge,
     * a Dataset that came back would leave its operator staring at a breach that had already healed.
     *
     * <p><b>The all-clear is never held by a cooldown.</b> The cooldown exists to stop a persisting
     * breach shouting on every sweep; suppressing the recovery would mean the opposite - the alarm was
     * delivered and the reassurance was dropped. It also CLEARS the firing key, so a Dataset that goes
     * stale again alerts immediately instead of waiting out the cooldown of a breach that is over.
     *
     * <p>The managed ALERT object opened by {@link #persistAlertObject} is <b>not</b> resolved here,
     * deliberately: {@code ObjectAccess} exposes {@code open}, the {@code hasActive*} checks and
     * {@code link}, but no transition - there is no seam through which this service can move an object
     * to a terminal state. Adding one is a design pass on that interface, not a detail of this row. The
     * all-clear is therefore delivered as an Event + Signal, which is what the notification layer
     * routes on; the object is left for an operator to resolve.
     */
    private void clear(AlertRule rule, String scope, String cooldownKey, long ageMs, long nowMs) {
        lastFired.remove(cooldownKey);   // not merely ignored - the next breach must fire at once
        String message = String.format(Locale.ROOT,
                "CLEARED: dataset %s published %ds ago, within its %s freshness limit",
                scope, ageMs / 1000, rule.maximumAge());
        log.info("[ALERT-CLEARED] {}", message);
        Event cleared = Event.builder(EventType.ALERT_CLEARED)
                .level(EventLevel.INFO)
                .source(AlertService.class.getName())
                .pipeline(scope)
                .message(message)
                .attr("rule", rule.name())
                .attr("dataset", rule.dataset())
                .attr("maximumAge", rule.maximumAge())
                .attr("severity", rule.severity())
                .build();
        EventLog.current().emit(cleared);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rule", rule.name());
            payload.put("dataset", rule.dataset());
            payload.put("ageSeconds", ageMs / 1000);
            payload.put("maximumAge", rule.maximumAge());
            // INFO, not the rule's severity: a recovery is never itself critical, and emitting it at
            // CRITICAL would page the on-call to tell them everything is fine.
            Signal s = new Signal(null, "alert-rule.cleared", Instant.ofEpochMilli(nowMs),
                    Severity.INFO, Ref.of("alert-rule", rule.name()), Ref.of("dataset", rule.dataset()),
                    // The same correlation key the fired signal uses, so triage pairs the two.
                    "alert:" + rule.name() + "|" + scope,
                    null, null, null, message, payload, 1);
            EventLog.current().emit(s.toEvent());
        } catch (RuntimeException e) {
            log.warn("could not emit alert-rule.cleared signal for {}: {}", rule.name(), e.getMessage());
        }
    }

    /** Fire one breached rule for a scope (a pipeline, or a measure rule's dataset), cooldown-guarded. */
    private void fire(AlertRule rule, String display, String cooldownScope, double value, long nowMs,
                      List<Alert> out) {
        String key = rule.name() + "|" + cooldownScope;
        Long last = lastFired.get(key);
        if (last != null && nowMs - last < cooldownMs(rule)) return;   // still in cooldown
        lastFired.put(key, nowMs);
        Alert alert = Alert.of(rule, display, value, nowMs);
        fired.addFirst(alert);
        while (fired.size() > capacity) fired.removeLast();
        out.add(alert);
        log.warn("[ALERT] {}", alert.message());
        // Phase-1↔2 tie: a fired alert is also a structured operational event, so the Event
        // Viewer shows it inline with the batch facts that triggered it (correlate via pipeline).
        // Built explicitly so the persisted alert object (Phase 2) can link back to its id.
        Event firedEvent = Event.builder(EventType.ALERT_FIRED)
                .level(EventLevel.WARN)
                .source(AlertService.class.getName())
                .pipeline(display)
                .message(alert.message())
                .attr("rule", rule.name())
                .attr("metric", alert.metric())
                .attr("value", value)
                .attr("severity", rule.severity())
                .build();
        EventLog.current().emit(firedEvent);
        emitFiredSignal(rule, display, cooldownScope, alert, value, nowMs);
        persistAlertObject(rule, alert, display, value, firedEvent.eventId());
    }

    /**
     * Emit the canonical {@code alert-rule.fired} Signal alongside the legacy {@code ALERT_FIRED}
     * event (AGT-5 P1 D4 — additive; the legacy event stays for the Event Viewer / notifications).
     * Correlated by rule+scope so the triage layer dedupes re-fires of the same breach. Never
     * disturbs evaluation — a signal hiccup is logged and swallowed.
     */
    private void emitFiredSignal(AlertRule rule, String display, String cooldownScope, Alert alert,
                                 double value, long nowMs) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rule", rule.name());
            payload.put("metric", alert.metric());
            payload.put("value", value);
            payload.put("severity", rule.severity());
            Signal s = new Signal(null, "alert-rule.fired", Instant.ofEpochMilli(nowMs),
                    Severity.parse(rule.severity()), Ref.of("alert-rule", rule.name()),
                    Ref.of("pipeline", display), "alert:" + rule.name() + "|" + cooldownScope,
                    // causation = null DELIBERATELY: a rule fires on a METRIC THRESHOLD, not on a
                    // signal, so there is no signal directly responsible — this is a causation ROOT.
                    // ⛔ Do not "fix" this by inventing a parent; a wrong cause is worse than none.
                    null, null, null, alert.message(), payload, 1);
            EventLog.current().emit(s.toEvent());
        } catch (RuntimeException e) {
            log.warn("could not emit alert-rule.fired signal for {}: {}", rule.name(), e.getMessage());
        }
    }

    /**
     * Phase 2: promote a fired alert to a managed {@link ObjectType#ALERT}
     * a managed ALERT object, linked to the firing event via the {@code causedByEvent}
     * attribute. No-op when no object store is wired (events-only). A still-active (non-terminal) object
     * for the same rule+pipeline suppresses a duplicate — the cooldown throttles re-fires within a
     * window; this guards across windows so an operator handling one breach isn't handed a clone.
     * Never disturbs evaluation: a persistence failure is logged and swallowed.
     */
    private void persistAlertObject(AlertRule rule, Alert alert, String pipeline, double value,
                                    String eventId) {
        if (objects == null) return;
        try {
            // ⚠ The seam's compound-key form (EDG-01 cell 7). This used to filter active() by the
            // "rule" attribute in-process; hasActiveMatching does the same match on the module's side,
            // which is what lets core stop naming OperationalObject.
            if (objects.hasActiveMatching(ObjectType.ALERT, pipeline, Map.of("rule", rule.name()))) return;
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("rule", rule.name());
            if (rule.metric() != null) attrs.put("metric", rule.metric());
            if (rule.dataset() != null) attrs.put("dataset", rule.dataset());
            if (rule.investigation() != null) attrs.put("investigation", rule.investigation());
            if (rule.relation() != null) attrs.put("relation", rule.relation());
            if (rule.measure() != null) attrs.put("measure", rule.measure());
            attrs.put("comparator", rule.comparator());
            attrs.put("threshold", String.valueOf(rule.threshold()));
            if (rule.window() != null) attrs.put("window", rule.window());
            attrs.put("value", String.valueOf(value));
            if (eventId != null) attrs.put("causedByEvent", eventId);
            String alertObjectId = objects.open(ObjectType.ALERT,
                    Alert.title(rule, pipeline), alert.message(), rule.severity(), pipeline, attrs);
            promoteToIncident(rule, alert, pipeline, attrs, alertObjectId);
        } catch (RuntimeException e) {
            log.warn("could not persist alert object for rule {}: {}", rule.name(), e.getMessage());
        }
    }

    /**
     * Auto-promote a <em>high-severity</em> (critical / error) alert breach to a managed
     * {@link ObjectType#INCIDENT} so it enters the triage workflow, not only the alert feed — the same
     * signal→Incident wiring {@code ExpectationRoutes} already does for violated Expectations. Lower
     * severities stay alerts. Deduped across windows exactly like the ALERT object above (one active
     * INCIDENT per rule+pipeline), carrying the same breach attributes. Best-effort within the enclosing
     * try — a promotion failure never disturbs evaluation.
     *
     * <p>The opened Incident is correlated to the ALERT that raised it with an
     * {@code ESCALATED_FROM} edge ({@code Incident ESCALATED_FROM Alert}), so the
     * correlation is traversable in the object graph rather than only implied by matching attributes —
     * matching what the operator-facing {@code POST /objects} create path has always required. ⚠ No edge
     * is added when the promotion is <em>suppressed</em> as a duplicate: a re-fire whose earlier ALERT was
     * resolved but whose INCIDENT is still being handled opens a fresh ALERT that stays unlinked. Wiring
     * that case needs "link to the active Incident instead", which the {@code IncidentAccess} contract
     * cannot express today (it reports suppressed and dry-run alike as an empty result).
     */
    private void promoteToIncident(AlertRule rule, Alert alert, String pipeline, Map<String, String> attrs,
                                   String alertObjectId) {
        if (!isHighSeverity(rule.severity())) return;
        // S1-4: promote through the incidents Platform Service — the same interface a granted Run
        // uses. The service enforces the active-object convention (one active INCIDENT per
        // rule+pipeline) via the "rule" dedupe attribute already present in attrs.
        incidents.openIncident(Alert.title(rule, pipeline), alert.message(),
                        rule.severity(), pipeline, new LinkedHashMap<>(attrs), "rule")
                // Machine actor, mirroring the Case Rules auto-linker's `case-rule:<name>` convention.
                // ⚠ `incidentId` is the id itself since EDG-01 cell 7 — this was the only reader of the
                // opened object, and it only ever wanted .id().
                // ⚠ "ESCALATED_FROM" as a String: LinkRelationship is domain vocabulary and stays in the
                // optional module, so the seam names the relationship rather than importing the enum.
                .ifPresent(incidentId -> objects.link(incidentId, alertObjectId,
                        ESCALATED_FROM, "alert-rule:" + rule.name()));
    }

    /** Whether a rule severity warrants an Incident (critical / error) rather than staying an alert. */
    private static boolean isHighSeverity(String severity) {
        return severity != null
                && (severity.equalsIgnoreCase("critical") || severity.equalsIgnoreCase("error"));
    }

    // ── metric math over ledger rows ─────────────────────────────────────────────────────

    private static double metricValue(String metric, List<Map<String, String>> rows) {
        return switch (metric) {
            case "failed_batches" -> rows.stream()
                    .filter(r -> "FAILED".equalsIgnoreCase(r.getOrDefault("status", ""))).count();
            // \u2705 The measure was ALREADY called rejected_files; D2 (2026-09-22) renamed the column to
            // match, so the two finally agree. The rows half has its own column now (rejected_rows).
            case "rejected_files" -> rows.stream().mapToLong(r -> asLong(r.get("rejected_files"))).sum();
            case "duration_ms" -> rows.stream().mapToLong(r -> asLong(r.get("duration_ms")))
                    .average().orElse(0);
            case "error_rate" -> {
                long in = rows.stream().mapToLong(r -> asLong(r.get("total_input_rows"))).sum();
                long outRows = rows.stream().mapToLong(r -> asLong(r.get("total_output_rows"))).sum();
                yield in <= 0 ? 0.0 : 1.0 - ((double) Math.min(outRows, in) / in);
            }
            default -> 0.0;
        };
    }

    /**
     * Restrict {@code rows} to the ones matching the rule's {@code when} condition tree, via
     * {@link com.gamma.query.ConditionTree#filter} — the same evaluator Decision Rules use, so a
     * ledger row's fields ({@code status}, {@code duration_ms}, {@code rejected_count}, …) are
     * scoped identically to how the authoring UI would preview them.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> filterByWhen(Object when, List<Map<String, String>> rows) {
        List<Map<String, Object>> asObj = (List<Map<String, Object>>) (List<?>) rows;
        List<Map<String, Object>> matched = com.gamma.query.ConditionTree.filter(when, asObj);
        return (List<Map<String, String>>) (List<?>) matched;
    }

    /** The ledger rows the rule's window selects: last-N batches, or rows newer than now - span. */
    private static List<Map<String, String>> inWindow(AlertRule rule, List<Map<String, String>> ledger,
                                                      long nowMs) {
        if (rule.batchWindow()) {
            int n = rule.windowBatches();
            return ledger.size() <= n ? ledger : ledger.subList(ledger.size() - n, ledger.size());
        }
        Duration span = rule.windowDuration();
        // ⛔ Stays systemDefault(), to match the writer — DECIDED 2026-08-15, do not "finish" the
        // -Dops.timezone sweep here. ConsignmentIngestor stamps the batches ledger's start_time/end_time with
        // LocalDateTime.now() into a zone-NAIVE string; this cutoff is the read half of that pair. Moving
        // this side alone offsets every alert window by the gap between ops zone and host zone.
        LocalDateTime cutoff = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs),
                java.time.ZoneId.systemDefault()).minus(span);
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> r : ledger) {
            LocalDateTime ts = parseTs(r.get("end_time"), r.get("start_time"));
            if (ts != null && !ts.isBefore(cutoff)) out.add(r);
        }
        return out;
    }

    /** Lenient ledger timestamp parse ({@code yyyy-MM-dd HH:mm:ss}, T-separator tolerated). */
    private static LocalDateTime parseTs(String... candidates) {
        for (String s : candidates) {
            if (s == null || s.length() < 19) continue;
            try {
                return LocalDateTime.parse(s.substring(0, 19).replace('T', ' '), LEDGER_TS);
            } catch (RuntimeException ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    private static long cooldownMs(AlertRule rule) {
        // Measure rules (no window) re-fire at most every 10 minutes while breached, like batch windows.
        long ms = (rule.window() == null || rule.batchWindow()) ? Duration.ofMinutes(10).toMillis()
                : rule.windowDuration().toMillis();
        return Math.max(ms, Duration.ofMinutes(1).toMillis());
    }

    private static boolean matches(String wanted, String display, String id) {
        String w = wanted.trim().toLowerCase(Locale.ROOT);
        return w.equals(display.toLowerCase(Locale.ROOT)) || w.equals(id.toLowerCase(Locale.ROOT))
                || w.replace(' ', '_').equals(id.toLowerCase(Locale.ROOT));
    }

    private static long asLong(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return (long) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
