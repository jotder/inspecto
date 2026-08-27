package com.gamma.job;

import com.gamma.api.PublicApi;
import com.gamma.notify.Notification;
import com.gamma.notify.NotificationAccess;
import com.gamma.ops.IncidentAccess;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.signal.Severity;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.OperationsZone;
import com.gamma.util.RunLog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;

/**
 * Fires one Run of a Job Pack's Job Type <b>without booting the engine</b> (platform-services plan
 * §7): the harness a scaffolded pack project tests against, and the scaffolder's real deliverable.
 * A pack author gets the production contract — the same registration-time {@code requires:} check,
 * the same declared-Parameter resolution, the same grant filtering, the same dry-run substitution —
 * with no host, no scheduler, no packs directory and no audit dir.
 *
 * <pre>
 * PackTestHarness h = PackTestHarness.create().loadFromClasspath();
 * PackTestHarness.Outcome run = h.run("acme.reconcile", Map.of("threshold", "10"));
 * assertEquals("SUCCESS", run.status());
 * assertEquals(1, run.notifications().size());
 * </pre>
 *
 * <p><b>Mutating services are recorded, not real.</b> {@code notifications} and {@code incidents}
 * are bound to in-memory stand-ins that honour their documented contracts — the feed's
 * dedupe-collapse and the Incident active-object convention — so a Job that relies on either
 * behaves here as it will in production. Anything else a Job Type declares — {@code schema},
 * {@code consignment-status}, {@code alerts} — must be supplied with {@link #service} <em>before</em>
 * the providers load, because {@code requires:} resolves at registration; an unbound id refuses the
 * type exactly as the engine's registry does, naming what is available. {@code alerts} is deliberately
 * not pre-bound: there is no honest default for "what breached", so a pack testing alert evaluation
 * must state what it expects.
 *
 * <p>Lives in main scope rather than a test-jar deliberately: {@code inspecto-engine} publishes no
 * test-jar (see its {@code pom.xml}), and a pack already depends on the engine to compile against
 * {@link JobTypeProvider}. It sits in {@code com.gamma.job} because reusing the real
 * {@link JobTypeRegistry}, {@link ParameterResolver} and {@link DryRunServices} — rather than
 * re-implementing their semantics — is the only way the harness can be trusted as a proxy for a
 * real Run.
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public final class PackTestHarness {

    private final PlatformServiceRegistry platform = new PlatformServiceRegistry();
    private final ExpressionRegistry expressions = ExpressionRegistry.withBuiltins();
    private final List<Notification> notifications = new ArrayList<>();
    private final List<OpenedIncident> incidents = new ArrayList<>();
    private JobTypeRegistry registry;   // built on first load — after every service() call

    private PackTestHarness() {
        platform.register("notifications", NotificationAccess.class, this::recordNotification);
        platform.register("incidents", IncidentAccess.class, this::recordIncident);
    }

    /** A harness with the recording {@code notifications} and {@code incidents} services bound. */
    public static PackTestHarness create() {
        return new PackTestHarness();
    }

    /**
     * Bind one more Platform Service — a read-only stand-in for {@code schema} /
     * {@code consignment-status}, or a stricter fake of your own. Must precede the load: a Job Type's
     * {@code requires:} is validated when its provider registers.
     */
    public <T> PackTestHarness service(String id, Class<T> type, T impl) {
        if (registry != null)
            throw new IllegalStateException("bind services before loading providers — a Job Type's "
                    + "requires: resolves at registration");
        platform.register(id, type, impl);
        return this;
    }

    /** Load every {@link JobTypeProvider} on the current classpath — what a pack's own tests want. */
    public PackTestHarness loadFromClasspath() {
        List<JobTypeProvider> found = new ArrayList<>();
        ServiceLoader.load(JobTypeProvider.class).forEach(found::add);
        if (found.isEmpty())
            throw new IllegalStateException("no JobTypeProvider on the classpath — is "
                    + "META-INF/services/com.gamma.job.JobTypeProvider present and does it name your provider?");
        return load(found.toArray(new JobTypeProvider[0]));
    }

    /**
     * Load providers directly (a unit test that doesn't want the ServiceLoader round-trip). Each is
     * registered on the strict third-party path: a {@code requires:} naming a service this harness has
     * not bound refuses the type here, naming what is available.
     */
    public PackTestHarness load(JobTypeProvider... providers) {
        if (registry == null) registry = new JobTypeRegistry(platform);
        for (JobTypeProvider p : providers) registry.registerClasspath(p);
        return this;
    }

    /** Every Job Type id this harness has registered. */
    public Set<String> types() {
        return registry == null ? Set.of() : registry.ids();
    }

    /** Fire one real Run of {@code typeId} with {@code params} as the authored {@code params:} block. */
    public Outcome run(String typeId, Map<String, String> params) {
        return fire(typeId, params, false);
    }

    /** Fire one dry Run (MNT-1): mutating services record instead of act, so nothing is stored. */
    public Outcome dryRun(String typeId, Map<String, String> params) {
        return fire(typeId, params, true);
    }

    /**
     * What one Run produced. {@code status} is the {@link JobResult} status, or {@code REJECTED} when
     * Parameter resolution failed before any user code ran — the same pre-flight the engine applies.
     */
    public record Outcome(String status, String message, List<String> log, Map<String, String> params,
                          List<Notification> notifications, List<OpenedIncident> incidents,
                          List<EmittedSignal> signals, List<String> artifacts, Set<Class<?>> granted) {

        /** Whether a log line contains {@code fragment} — the usual assertion over a Run Log. */
        public boolean logged(String fragment) {
            return log.stream().anyMatch(l -> l.contains(fragment));
        }
    }

    /** One recorded {@link IncidentAccess#openIncident} call. */
    public record OpenedIncident(String title, String message, String severity, String scope,
                                 Map<String, String> attributes, String dedupeAttribute) {}

    /** One recorded {@link SignalEmitter#emit} call. */
    public record EmittedSignal(String type, Severity severity, Map<String, Object> payload) {}

    // ── the Run ────────────────────────────────────────────────────────────────

    private Outcome fire(String typeId, Map<String, String> params, boolean dryRun) {
        if (registry == null)
            throw new IllegalStateException("load a provider first (loadFromClasspath() or load(...))");
        notifications.clear();
        incidents.clear();

        String runId = "harness-" + UUID.randomUUID();
        JobConfig cfg = new JobConfig("harness-" + typeId, typeId, null, null, true, false,
                params == null ? Map.of() : Map.copyOf(params), null, null);
        Job job = registry.create(typeId, cfg);   // unknown id throws, naming what is registered
        HarnessContext ctx = new HarnessContext(runId, dryRun);

        // The §7.2 ladder, minus the layers a harness has no host for (trigger args, signal bind:,
        // $upstream artifacts): authored params: → deduce → default, with the real resolver.
        ParameterResolver.Resolution pr = ParameterResolver.resolve(
                registry.parameters(typeId, cfg), Map.of(), Map.of(), cfg.params(), expressions,
                // Same zone JobService:250 resolves for a real run — a harness that dry-runs $today in a
                // different zone than production can report a pass for the wrong date.
                new ExpressionContext(runId, Instant.now(), "manual", OperationsZone.resolve(),
                        Optional::empty, (j, n) -> Optional.empty(), Map.of()));
        String rejection = rejection(pr);
        if (rejection != null) return outcome("REJECTED", rejection, ctx);

        ctx.params = pr.resolved();
        PlatformServices granted = platform.grant(Set.copyOf(
                registry.descriptor(typeId).map(JobTypeDescriptor::requires).orElse(List.of())));
        ctx.services = dryRun ? DryRunServices.wrap(granted, ctx.log()) : granted;

        try {
            JobResult res = job.run(ctx);
            return outcome(res.status(), res.message(), ctx);
        } catch (Exception e) {
            return outcome("FAILED", e.toString(), ctx);
        }
    }

    /** The engine's own REJECTED phrasing, so a harness failure reads like the real Run's. */
    private static String rejection(ParameterResolver.Resolution pr) {
        List<String> reasons = new ArrayList<>();
        if (!pr.missingRequired().isEmpty())
            reasons.add("missing required parameter(s): " + String.join(", ", pr.missingRequired()));
        if (!pr.invalidType().isEmpty())
            reasons.add("invalid parameter(s): " + String.join(", ", pr.invalidType()));
        if (!pr.unknownExpression().isEmpty())
            reasons.add("unknown expression(s): " + String.join(", ", pr.unknownExpression()));
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }

    private Outcome outcome(String status, String message, HarnessContext ctx) {
        return new Outcome(status, message, List.copyOf(ctx.log), ctx.params,
                List.copyOf(notifications), List.copyOf(incidents), List.copyOf(ctx.signals),
                List.copyOf(ctx.artifacts), ctx.services.granted());
    }

    // ── recording services ─────────────────────────────────────────────────────

    /** Honours the feed's dedupe-collapse contract: a repeat of an active {@code dedupeKey} is empty. */
    private Optional<Notification> recordNotification(Notification n) {
        if (n.dedupeKey() != null && notifications.stream()
                .anyMatch(prev -> n.dedupeKey().equals(prev.dedupeKey())))
            return Optional.empty();
        notifications.add(n);
        return Optional.of(n);
    }

    /** Honours the active-object convention: a second open for the same scope + dedupe value is empty. */
    private Optional<OperationalObject> recordIncident(String title, String message, String severity,
                                                      String scope, Map<String, String> attributes,
                                                      String dedupeAttribute) {
        Map<String, String> attrs = attributes == null ? Map.of() : Map.copyOf(attributes);
        String key = attrs.get(dedupeAttribute);
        boolean active = incidents.stream().anyMatch(prev -> prev.scope().equals(scope)
                && java.util.Objects.equals(key, prev.attributes().get(dedupeAttribute)));
        if (active) return Optional.empty();
        incidents.add(new OpenedIncident(title, message, severity, scope, attrs, dedupeAttribute));
        long now = System.currentTimeMillis();
        return Optional.of(new OperationalObject(UUID.randomUUID().toString(), ObjectType.INCIDENT,
                title, message, "IDENTIFIED", severity, null, null, null, scope, attrs, now, now, 0L));
    }

    // ── the context ────────────────────────────────────────────────────────────

    /** An in-memory {@link JobContext}: everything a Run writes is kept for assertions. */
    private final class HarnessContext implements JobContext {

        private final String runId;
        private final boolean dryRun;
        private final List<String> log = new ArrayList<>();
        private final List<EmittedSignal> signals = new ArrayList<>();
        private final List<String> artifacts = new ArrayList<>();
        private Map<String, String> params = Map.of();
        private PlatformServices services = PlatformServices.none();

        private HarnessContext(String runId, boolean dryRun) {
            this.runId = runId;
            this.dryRun = dryRun;
        }

        @Override public String runId()                 { return runId; }
        @Override public String spaceId()               { return "default"; }
        @Override public TriggerInfo trigger()          { return TriggerInfo.parse("manual"); }
        @Override public Map<String, String> config()   { return params; }
        @Override public Map<String, String> params()   { return params; }
        @Override public boolean dryRun()               { return dryRun; }
        @Override public PlatformServices services()    { return services; }

        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String m, Object... kv)  { append("INFO", m, kv); }
                @Override public void warn(String m, Object... kv)  { append("WARN", m, kv); }
                @Override public void error(String m, Throwable t, Object... kv) {
                    append("ERROR", t == null ? m : m + ": " + t, kv);
                }
            };
        }

        @Override public SignalEmitter signals() {
            return (type, severity, payload) -> signals.add(new EmittedSignal(type,
                    severity == null ? Severity.INFO : severity,
                    payload == null ? Map.of() : new LinkedHashMap<>(payload)));
        }

        @Override public ArtifactRecorder artifacts() {
            return new ArtifactRecorder() {
                @Override public void dataset(String name, String ref, ResultSetMeta meta, long rows,
                                              Instant watermark) {
                    artifacts.add("dataset:" + name + " (" + rows + " rows)");
                }
                @Override public void file(String name, Path path, long bytes) {
                    artifacts.add("file:" + name + " (" + bytes + " bytes)");
                }
            };
        }

        private void append(String level, String message, Object... kv) {
            StringBuilder sb = new StringBuilder(level).append(' ').append(message);
            for (int i = 0; i + 1 < kv.length; i += 2)
                sb.append(' ').append(kv[i]).append('=').append(kv[i + 1]);
            log.add(sb.toString());
        }
    }
}
