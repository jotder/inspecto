package com.gamma.control;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.AcceptedConfigKeys;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.etl.PipelineConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE authoring gate: the one list of checks every config save path runs — {@code POST /config/write},
 * {@code POST /config/patch}, {@code PUT /pipelines/{name}/graph}, {@code POST /pipelines/import} — and
 * that {@code POST /validate} reports for a draft (`PROCESSOR-RELEASE-READINESS-1` G3, 2026-09-23).
 *
 * <p>🔴 <b>Why one function and not a list per route.</b> Until this class each route carried its own
 * hand-kept copy of the same {@code findings.addAll(...)} sequence, and the copies had drifted: the graph
 * editor's save — whose comment claimed "the same gate {@code /config/write} runs" — ran neither the
 * unknown-Connection check nor the unknown-key census, bundle import ran neither of those nor the two
 * TypeFlow checks, and {@code /validate} ran four of the eleven. One config got different verdicts from
 * different doors. A check added here reaches every door at once, and {@code ControlApiSaveGateParityTest}
 * drives one fault through every door to hold that.
 *
 * <p>Route-specific gates stay on their routes, because they are not about the config's content: the
 * schema drift and BACKWARD-compatibility gates need the file being replaced, and the graph route's
 * lowering refusals happen before there is a config to judge.
 *
 * <p>⚠ Returns findings; the caller decides. A save refuses on any ERROR, {@code /validate} reports.
 */
final class SaveGate {

    private SaveGate() {}

    /**
     * Whether a reference to something that must already exist in this Space — a Connection — is refused
     * when it does not, or reported so the author can supply it before activation.
     */
    enum Referents {
        /** Every authoring save: a dangling reference throws on every run, so the save refuses it. */
        MUST_EXIST,
        /**
         * Bundle import only — a recorded decision, not a gap: a pipeline bundle never carries its
         * Connections (secrets never travel), the import always lands inactive, and refusing would make
         * promotion into a fresh Space impossible. A MISSING referent becomes a
         * {@link FindingCodes#WARN_UNRESOLVED_CONNECTION} (the collector's comes from
         * {@code PipelineBundleRoutes.classifyRequirements}, which also classifies the manifest's
         * requirements); a referent that exists but is the WRONG kind is still refused.
         */
        MAY_ARRIVE_LATER
    }

    /**
     * Every content finding for {@code draft}.
     *
     * @param type      the config type; the caller has already 404'd an unknown one
     * @param writeRoot the Space config root — the base a {@code job}'s relative paths resolve against
     * @param configDir the directory the config lives in (or will land in), so a config-relative
     *                  reference resolves the way the loader resolves it; {@code null} only where there is
     *                  no prospective home, which checks the working-directory form alone
     */
    static List<Finding> check(ApiContext api, String type, Map<String, Object> draft, Path writeRoot,
                               Path configDir, Referents referents) {
        List<Finding> f = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.forType(type), draft));
        f.addAll(safety(type, draft, writeRoot, configDir));   // the hard-fail safety check (R6)
        // WARNING only: the schema file may be created after the save, or belong to another host.
        f.addAll(ConfigRoutes.schemaFileFindings(type, draft, Severity.WARNING, configDir));
        // Arming (ERROR when active, WARNING on an inactive draft): an active config that cannot arm
        // registers and is then silently skipped every cycle.
        f.addAll(ConfigRoutes.armedWithoutSchemaFindings(type, draft));
        f.addAll(ConfigRoutes.routeArmingFindings(type, draft));
        f.addAll(ConfigRoutes.stepDisableFindings(type, draft));
        f.addAll(ConfigRoutes.dedupWindowFindings(type, draft));
        f.addAll(ConfigRoutes.sinkLakeCollisionFindings(type, draft));
        // Referential: a collector bound to a Connection this Space does not have throws once per poll.
        if (referents == Referents.MUST_EXIST) f.addAll(ConfigRoutes.unknownConnectionFindings(type, draft, api));
        f.addAll(webhookFindings(type, draft, api, referents));
        // A block no component reads is a SILENT LOSS (`DUCKLE-C3-DEAD-PROPERTY-1`).
        f.addAll(AcceptedConfigKeys.unknownKeyFindings(type, draft, Severity.ERROR));
        // TYPEFLOW-CONSUMERS-1 (a): need the declared columns, hence the config's own directory.
        f.addAll(ConfigRoutes.routeColumnFindings(type, draft, configDir));
        f.addAll(ConfigRoutes.summarizeMeasureFindings(type, draft, configDir));
        return f;
    }

    /**
     * The base {@link ConfigSafetyValidator} resolves a config's relative values against — <b>and it
     * means two different things depending on the kind</b>, which is the whole of
     * {@code JOB-PATH-PATCH-ROUTE-WRONG-BASE-1}.
     *
     * <ul>
     *   <li><b>pipeline / schema</b> — the config file's <em>own directory</em>, so a config
     *       <em>reference</em> ({@code schema_file}, {@code grammar}) resolves the way the loader
     *       resolves it: beside the config.</li>
     *   <li><b>job</b> — the <b>Space config root</b>. A job's relative path resolves against it and
     *       nothing else ({@code JOB-DIR-CWD-CONTAINMENT-1}, operator 2026-09-16), and
     *       {@link com.gamma.config.safety.PathJail#resolveJobPath} is the single rule the run-time
     *       tasks call too.</li>
     * </ul>
     *
     * <p>🔴 Passing a job its file's directory judged it from {@code <space>/config/jobs} whenever the
     * caller supplied {@code subdir:"jobs"} — the only shape that can address a job {@code POST /jobs}
     * wrote. Driven 2026-09-16: {@code backup_dir:"../../outside/backups"} was <b>refused 422</b> by
     * {@code PUT /jobs} and <b>written</b> by {@code /config/patch}, one directory level apart.
     *
     * <p>⚠ {@code writeRoot} <b>is</b> that root, not an approximation of it: {@code ControlApi.writeRoot()}
     * returns the bound space's {@code root().config()}, which is precisely what {@code SpaceBootstrap}
     * registers into {@code SpaceConfigRoot} for {@code JobRoutes} to read back. For a job it is the base
     * of every key EXCEPT the pipeline runner's two ({@code JOB-PATH-SINGLE-TENANT-GATE-BASE-1}):
     * {@code SpaceConfigRoot.jobPathBase} hands those the config READ root.
     */
    private static List<Finding> safety(String type, Map<String, Object> draft, Path writeRoot, Path configDir) {
        if ("job".equalsIgnoreCase(type))
            return ConfigSafetyValidator.checkJob(draft, SafetyPolicy.defaultPolicy(),
                    k -> com.gamma.pipeline.SpaceConfigRoot.jobPathBase(k, writeRoot));
        return ConfigSafetyValidator.check(type, draft, SafetyPolicy.defaultPolicy(), configDir);
    }

    /** Whether {@code findings} carries an ERROR — the one verdict every save path refuses on. */
    static boolean refuses(List<Finding> findings) {
        return findings.stream().anyMatch(x -> x.severity() == Severity.ERROR);
    }

    /**
     * The {@code webhook:} block, judged at save as {@code WebhookSink.plan} judges it at run time: it must
     * parse (an authored {@code url:}/{@code token:} or an unknown key is refused by the parser, and the
     * pipeline would then not load at all), and its {@code connection} must name a Connection this Space
     * holds whose connector is {@code https}. Refused regardless of {@code active}, like the collector's
     * Connection: the author is present now, and at the first run nobody is.
     *
     * <p>⚠ Checked against the live {@code ConnectionProfileRegistry} ({@code api.service().connections()}),
     * the source the collector check uses; the run resolves through {@code ConnectionRegistry}, which every
     * Connection write updates in the same request. The one blind spot is the collector check's: a
     * {@code *_connection.toon} copied onto disk with no restart since.
     *
     * <p>Only the Connection's existence and kind are checked here — not its host, tunnel or proxy, which
     * are the Connection's own validation, run when it is written.
     */
    static List<Finding> webhookFindings(String type, Map<String, Object> draft, ApiContext api, Referents referents) {
        if (!"pipeline".equals(type) || draft.get("webhook") == null) return List.of();
        if (!(draft.get("webhook") instanceof Map<?, ?> block))
            return List.of(new Finding(Severity.ERROR, "webhook",
                    "webhook: must be a map ({connection, batch_size, retry})", FindingCodes.ERR_WEBHOOK_INVALID,
                    "author the target as an https Connection and name it in webhook.connection"));
        PipelineConfig.Webhook w;
        try {
            w = PipelineConfig.Webhook.fromMap(block);
        } catch (IllegalArgumentException refused) {
            return List.of(new Finding(Severity.ERROR, "webhook", refused.getMessage(),
                    FindingCodes.ERR_WEBHOOK_INVALID,
                    "the endpoint and its token belong on an https Connection; keep only connection, "
                            + "batch_size and retry here"));
        }
        if (api == null) return List.of();
        ConnectionProfile p = api.service().connections().get(w.connection());
        if (p == null) {
            if (referents == Referents.MAY_ARRIVE_LATER)
                return List.of(new Finding(Severity.WARNING, "webhook.connection",
                        "unknown connection '" + w.connection() + "' — this space has no such connection "
                                + "profile, so the imported pipeline's webhook cannot send",
                        FindingCodes.WARN_UNRESOLVED_CONNECTION,
                        "register the https connection before activating the pipeline"));
            return List.of(new Finding(Severity.ERROR, "webhook.connection",
                    "unknown connection '" + w.connection() + "' — no such connection profile in this space",
                    FindingCodes.ERR_WEBHOOK_CONNECTION_UNKNOWN,
                    "create the https connection first, or name one that exists"));
        }
        if (!com.gamma.pipeline.exec.WebhookSink.CONNECTOR.equals(p.connector()))
            return List.of(new Finding(Severity.ERROR, "webhook.connection",
                    "connection '" + p.id() + "' is a '" + p.connector() + "' connection — a webhook target "
                            + "must be an '" + com.gamma.pipeline.exec.WebhookSink.CONNECTOR + "' connection",
                    FindingCodes.ERR_WEBHOOK_CONNECTION_NOT_HTTPS,
                    "name an https connection, or create one for this endpoint"));
        return List.of();
    }
}
