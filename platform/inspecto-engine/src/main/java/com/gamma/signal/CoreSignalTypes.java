package com.gamma.signal;

import com.gamma.signal.SignalField.Kind;

import java.util.ArrayList;
import java.util.List;

/**
 * The Signal Types core posts (SIGNAL-BOARD-1 §11.1, SB-1a): the {@link SignalType} constants, the types the engine
 * and the control plane post outside a Job (string literals at the posting line, or a {@code TYPE} constant of the
 * posting class), and those of the core Job Types. Fields are read from each posting site; a field a site writes
 * only under a condition is not {@code always}. A module's types are declared by the module's own provider (D-SB6).
 * The framework-stamped {@code chainDepth} is not a field ({@link SignalTypeCatalog#STAMPED}).
 */
public final class CoreSignalTypes implements SignalTypeProvider {

    private static final String SINCE = "4.0.0";

    @Override public String source() { return "core"; }

    @Override
    public List<SignalTypeDecl> signalTypes() {
        List<SignalTypeDecl> t = new ArrayList<>();

        // ── Job service: Run lifecycle, chaining, Job Packs ─────────────────────────────────────────────
        t.add(decl(SignalType.JOB_RUN_STARTED, "Job Run started", "engine",
                a("job", Kind.STRING, "The Job's name"), a("run", Kind.STRING, "The Run id"),
                a("trigger", Kind.STRING, "What started the Run, e.g. cron, manual, signal:<type>")));
        t.add(decl(SignalType.JOB_RUN_COMPLETED, "Job Run completed", "engine",
                a("job", Kind.STRING, "The Job's name"), a("run", Kind.STRING, "The Run id"),
                a("outcome", Kind.STRING, "The Run status: SUCCESS, FAILED, CANCELLED, ..."),
                a("durationMs", Kind.INTEGER, "How long the Run took")));
        t.add(decl(SignalType.JOB_RUN_FAILED, "Job Run failed", "engine",
                a("job", Kind.STRING, "The Job's name"), a("run", Kind.STRING, "The Run id"),
                a("outcome", Kind.STRING, "The Run status"), a("message", Kind.STRING, "The failure message")));
        t.add(decl(SignalType.JOB_RUN_REJECTED, "Job Run refused before it started", "engine",
                a("job", Kind.STRING, "The Job's name"), a("run", Kind.STRING, "The Run id"),
                o("reason", Kind.STRING, "Why the Run was refused"),
                o("missing", Kind.LIST, "Required parameters with no value"),
                o("invalidType", Kind.LIST, "Parameters whose value has the wrong type"),
                o("unknownExpression", Kind.LIST, "Parameters naming an unknown expression")));
        t.add(decl(SignalType.JOB_CHAIN_CUT, "Signal chain cut", "engine",
                a("job", Kind.STRING, "The Job that was not fired"), a("signalType", Kind.STRING, "The Signal it would have reacted to"),
                a("maxChainDepth", Kind.INTEGER, "The configured chain-depth limit")));
        t.add(decl(SignalType.JOB_SIGNAL_REFUSED, "Signal reaction refused: too many queued Runs", "engine",
                a("job", Kind.STRING, "The Job that was not fired"),
                a("refusedSignalId", Kind.STRING, "The Signal it would have reacted to"),
                a("refusedType", Kind.STRING, "That Signal's type"),
                a("maxPending", Kind.INTEGER, "The bound on queued Signal Runs per Job")));
        t.add(decl(SignalType.JOB_SIGNAL_MISSED, "Signals missed while down, too old to replay at boot", "engine",
                a("job", Kind.STRING, "The Job that did not react"),
                a("onSignal", Kind.STRING, "The Signal Type the Job listens to"),
                a("count", Kind.INTEGER, "How many Signals were not replayed"),
                a("oldestAt", Kind.STRING, "When the oldest of them was posted"),
                a("newestAt", Kind.STRING, "When the newest of them was posted"),
                a("signalIds", Kind.LIST, "Their Signal ids, at most 100"),
                a("maxAgeHours", Kind.INTEGER, "The Space's catch-up age limit")));
        t.add(decl(SignalType.PIPELINE_COMMIT, "Consignment committed (mirror of the commit event)", "engine",
                a("dryRun", Kind.BOOLEAN, "Whether the Consignment was a dry run"),
                a("pipeline", Kind.STRING, "The Pipeline"), a("batchId", Kind.STRING, "The Consignment id"),
                a("status", Kind.STRING, "The commit status"), a("rows", Kind.INTEGER, "Output rows"),
                a("ms", Kind.INTEGER, "Duration in milliseconds"), a("parts", Kind.LIST, "Partitions written")));
        SignalField[] pack = {a("id", Kind.STRING, "The pack id"), a("version", Kind.STRING, "The pack version"),
                a("file", Kind.STRING, "The pack jar file name"), a("hash", Kind.STRING, "The jar's hash"),
                a("types", Kind.LIST, "The Job Types the pack brings"), a("state", Kind.STRING, "The pack state")};
        t.add(decl(SignalType.JOB_PACK_LOADED, "Job Pack loaded", "engine", pack));
        t.add(decl(SignalType.JOB_PACK_UNLOADED, "Job Pack unloaded", "engine", pack));
        t.add(decl(SignalType.JOB_PACK_REJECTED, "Job Pack refused", "engine",
                a("file", Kind.STRING, "The pack jar file name"), a("hash", Kind.STRING, "The jar's hash"),
                a("cause", Kind.STRING, "Why it was refused")));

        // ── core Job Types ──────────────────────────────────────────────────────────────────────────────
        SignalField[] completeness = {
                a("pipeline", Kind.STRING, "The Pipeline whose day was judged"),
                a("recordDay", Kind.STRING, "The day judged (yyyy-MM-dd)"),
                a("status", Kind.STRING, "Volume status: OK, BREACH, NO_BASELINE, NO_OBSERVATION"),
                o("files", Kind.INTEGER, "Files registered for the day"), o("rows", Kind.INTEGER, "Rows registered for the day"),
                o("unknownDayFiles", Kind.INTEGER, "Files with no record day"),
                o("unknownDayRows", Kind.INTEGER, "Rows with no record day"),
                a("baselineDays", Kind.INTEGER, "Days in the baseline"),
                o("baselineRows", Kind.INTEGER, "The baseline's rows, when there is a baseline"),
                a("deviation", Kind.NUMBER, "Deviation from the baseline; null when undefined"),
                o("fileTemplate", Kind.STRING, "The file-name template the gaps were read against"),
                o("seqScope", Kind.STRING, "The sequence scope, for a template with a sequence"),
                o("observedFiles", Kind.INTEGER, "Files seen for the day"),
                o("missingFiles", Kind.INTEGER, "Files missing from the sequence"),
                o("missingFilesSource", Kind.STRING, "Where the file count came from"),
                o("emptyBuckets", Kind.INTEGER, "Buckets with no file"),
                o("unmatchedFiles", Kind.INTEGER, "Files that did not match the template"),
                a("unknownStreakDays", Kind.INTEGER, "Consecutive days with nothing registered")};
        t.add(decl(SignalType.KPI_COMPLETENESS_EVALUATED, "Completeness evaluated", "job-type:kpi.completeness", completeness));
        t.add(decl(SignalType.KPI_COMPLETENESS_BREACHED, "Completeness breached", "job-type:kpi.completeness", completeness));
        t.add(decl(SignalType.KPI_COMPLETENESS_UNKNOWN_STREAK, "Days with nothing registered", "job-type:kpi.completeness", completeness));
        t.add(decl(SignalType.ALERT_EVALUATE_COMPLETED, "Alert Rules evaluated", "job-type:alert.evaluate",
                a("job", Kind.STRING, "The Job's name"), a("fired", Kind.INTEGER, "Alert Rules that fired"),
                a("rules", Kind.LIST, "The names of the rules that fired"),
                o("scopedTo", Kind.STRING, "The one rule evaluated, when the Run named one"),
                o("scope", Kind.STRING, "freshness, when only freshness rules were evaluated")));
        t.add(decl(SignalType.LA_DETECT_COMPLETED, "Investigation Alert Rules evaluated", "job-type:la.detect",
                a("job", Kind.STRING, "The Job's name"), a("fired", Kind.INTEGER, "Rules that fired"),
                a("rules", Kind.LIST, "The names of the rules that fired")));
        t.add(decl(SignalType.LA_INDEX_BUILD_COMPLETED, "Link index built", "job-type:la.index.build",
                a("job", Kind.STRING, "The Job's name"), a("dataset", Kind.STRING, "The Dataset indexed"),
                a("result", Kind.STRING, "The build result"), o("mode", Kind.STRING, "full or delta, when a build ran"),
                o("code", Kind.STRING, "The refusal code, when refused"), a("edges", Kind.INTEGER, "Edges in the index"),
                a("nodes", Kind.INTEGER, "Nodes in the index"), a("deltas", Kind.INTEGER, "Deltas applied")));
        t.add(decl(SignalType.MAIL_SENT, "Mail sent", "job-type:mail.send",
                a("job", Kind.STRING, "The Job's name"), a("run", Kind.STRING, "The Run id"),
                a("recipients", Kind.INTEGER, "To and Cc recipients"), a("sent", Kind.BOOLEAN, "Whether the mail went out")));
        t.add(decl(SignalType.PUBLISH_POSTGRES_COMPLETED, "Published to Postgres", "job-type:publish.postgres",
                a("job", Kind.STRING, "The Job's name"), a("schema", Kind.STRING, "The target schema"),
                a("tables", Kind.STRING, "The tables written, comma-separated"), a("rows", Kind.INTEGER, "Rows written")));
        t.add(decl(SignalType.PUBLISH_POSTGRES_REFUSED, "Postgres publish refused", "job-type:publish.postgres",
                a("job", Kind.STRING, "The Job's name"), a("reason", Kind.STRING, "Why it was refused")));
        t.add(decl(SignalType.REPORT_ATTACH_REFUSED, "Report attachment refused", "job-type:report",
                a("job", Kind.STRING, "The Job's name"), a("reason", Kind.STRING, "Why it was refused"),
                a("fingerprint", Kind.STRING, "The destination's fingerprint")));
        t.add(decl(SignalType.SAMPLE_HELLO_COMPLETED, "Sample Job completed", "job-type:sample.hello",
                a("job", Kind.STRING, "The Job's name"), a("run", Kind.STRING, "The Run id"),
                a("notified", Kind.BOOLEAN, "Whether a Notification was raised")));
        t.add(decl(SignalType.JOB_DATASET_PRODUCED, "Dataset produced by a Job", "job-type:sql.template",
                a("dataset", Kind.STRING, "The Dataset written"), a("rows", Kind.INTEGER, "Rows written")));
        SignalField[] findings = {a("count", Kind.INTEGER, "How many findings"), a("findings", Kind.LIST, "The findings")};
        t.add(decl(SignalType.MAINTENANCE_FILEREPO_FINDINGS, "File repository findings", "job-type:maintenance", findings));
        t.add(decl(SignalType.MAINTENANCE_METADATA_FINDINGS, "Metadata findings", "job-type:maintenance", findings));
        t.add(decl(SignalType.MAINTENANCE_SCHEDULER_FINDINGS, "Scheduler findings", "job-type:maintenance", findings));
        t.add(decl(SignalType.MAINTENANCE_STORAGE_THRESHOLD, "Storage above its threshold", "job-type:maintenance",
                a("dir", Kind.STRING, "The directory measured"), a("totalBytes", Kind.INTEGER, "Bytes used"),
                a("warnBytes", Kind.INTEGER, "The threshold")));
        t.add(decl(SignalType.MAINTENANCE_STORAGE_TREND, "Storage will reach its threshold", "job-type:maintenance",
                a("currentBytes", Kind.INTEGER, "Bytes used now"), a("bytesPerDay", Kind.INTEGER, "Growth per day"),
                a("etaDays", Kind.NUMBER, "Days until the threshold"), a("warnBytes", Kind.INTEGER, "The threshold"),
                a("topAxis", Kind.STRING, "The fastest-growing axis")));
        t.add(decl("space.comparison.completed", "Spaces compared", "job-type:space.comparison",
                a("spaces", Kind.LIST, "The Spaces asked for"), a("windowDays", Kind.INTEGER, "The window"),
                a("comparable", Kind.LIST, "Spaces with enough history"), a("notComparable", Kind.LIST, "Spaces without"),
                a("axes", Kind.LIST, "One row per storage axis")));
        t.add(decl("kpi.snapshot.completed", "KPI snapshot taken", "job-type:kpi.snapshot",
                a("job", Kind.STRING, "The Job's name"), a("kpi", Kind.STRING, "The KPI"),
                a("dataset", Kind.STRING, "The KPI history Dataset"), a("evaluated", Kind.INTEGER, "Periods evaluated"),
                a("rows", Kind.INTEGER, "Rows written")));
        t.add(decl("expectation.evaluate.completed", "Expectations evaluated", "job-type:expectation.evaluate",
                a("job", Kind.STRING, "The Job's name"), a("evaluated", Kind.INTEGER, "Expectations evaluated"),
                a("failed", Kind.INTEGER, "Expectations that failed"), a("expectations", Kind.LIST, "The names that failed")));
        SignalField[] spaceTest = {a("job", Kind.STRING, "The Job's name"), a("total", Kind.INTEGER, "Cases run"),
                a("failed", Kind.INTEGER, "Cases that failed"), a("cases", Kind.LIST, "The names that failed")};
        t.add(decl("space.test.completed", "Space tests run", "job-type:space.test", spaceTest));
        t.add(decl("space.test.failed", "Space tests failed", "job-type:space.test", spaceTest));

        // Cross-module contracts: core matches them (CollectorService releases deferred Alert Rules); modules post.
        t.add(decl(SignalType.RISK_SCORE_PRODUCED, "Risk Scores produced", "job-type:risk.score",
                a("model", Kind.STRING, "The Risk Score model"), a("dataset", Kind.STRING, "The scores Dataset"),
                a("entities", Kind.INTEGER, "Entities scored"), a("high", Kind.INTEGER, "Entities in the high band")));
        t.add(decl(SignalType.ANOMALY_SCORE_PRODUCED, "Anomaly Scores produced", "job-type:anomaly.score",
                a("model", Kind.STRING, "The Anomaly Model"), a("run", Kind.STRING, "The Run id"),
                a("scored", Kind.INTEGER, "Entities scored"), a("elevated", Kind.INTEGER, "Entities in the elevated band"),
                a("high", Kind.INTEGER, "Entities in the high band"),
                a("insufficient", Kind.INTEGER, "Entities with too little history"),
                a("excluded", Kind.INTEGER, "Entities excluded")));
        t.add(decl(SignalType.CHURN_SCORE_PRODUCED, "Churn Scores produced", "job-type:predict.churn",
                a("model", Kind.STRING, "The Churn Model"), a("run", Kind.STRING, "The Run id"),
                a("scored", Kind.INTEGER, "Entities scored"), a("elevated", Kind.INTEGER, "Entities in the elevated band"),
                a("high", Kind.INTEGER, "Entities in the high band"),
                a("insufficient", Kind.INTEGER, "Entities with too little history")));

        // ── engine, outside a Job ───────────────────────────────────────────────────────────────────────
        SignalField[] batch = {
                a("dryRun", Kind.BOOLEAN, "Whether the Consignment was a dry run"), a("status", Kind.STRING, "The Consignment status"),
                a("outputRows", Kind.INTEGER, "Output rows"), a("durationMs", Kind.INTEGER, "Duration in milliseconds"),
                a("rejectedCount", Kind.INTEGER, "Rejected rows"), a("partitions", Kind.LIST, "Partitions written"),
                o("error", Kind.STRING, "The error, when there was one"), o("offendingFile", Kind.STRING, "The file at fault"),
                a("errorRows", Kind.INTEGER, "Rows in error")};
        t.add(decl("pipeline.batch.committed", "Consignment committed", "engine", batch));
        t.add(decl("pipeline.batch.parked", "Consignment parked at a disabled Step", "engine", batch));
        t.add(decl("pipeline.batch.failed", "Consignment failed", "engine",
                a("dryRun", Kind.BOOLEAN, "Whether the Consignment was a dry run"), a("status", Kind.STRING, "The Consignment status"),
                o("outputRows", Kind.INTEGER, "Output rows (ingest lane)"),
                a("durationMs", Kind.INTEGER, "Duration in milliseconds"),
                o("rejectedCount", Kind.INTEGER, "Rejected rows (ingest lane)"),
                o("partitions", Kind.LIST, "Partitions written (ingest lane)"),
                o("error", Kind.STRING, "The error, when known"), o("offendingFile", Kind.STRING, "The file at fault"),
                o("errorRows", Kind.INTEGER, "Rows in error (ingest lane)"),
                o("failedStep", Kind.STRING, "The Step that failed, when a Step owns the failure"),
                o("code", Kind.STRING, "The failing Step's code")));
        t.add(decl("pipeline.batch.retry_exhausted", "Consignment gave up after its commit retries", "engine",
                a("attempts", Kind.INTEGER, "Commit attempts made"), a("files", Kind.LIST, "The files quarantined"),
                o("error", Kind.STRING, "The last error")));
        t.add(decl("pipeline.step.abandoned", "Step thread abandoned; its kind is disabled", "engine",
                a("node", Kind.STRING, "The Step node id"), a("type", Kind.STRING, "The Step kind"),
                a("deadline", Kind.STRING, "The deadline it ignored"), o("pipeline", Kind.STRING, "The Pipeline")));
        t.add(decl("dataset.write", "Dataset written", "engine",
                a("dataset", Kind.STRING, "The Dataset"), a("rows", Kind.INTEGER, "Rows written"),
                a("at", Kind.STRING, "When (ISO-8601)"), o("producer", Kind.STRING, "Who wrote it, as kind:id"),
                o("pipeline", Kind.STRING, "The Pipeline that owns the Dataset")));
        t.add(decl("audit.write_failed", "Audit row could not be written", "engine",
                a("audit", Kind.STRING, "The audit store"), a("subject", Kind.STRING, "What the row was about"),
                a("error", Kind.STRING, "The error")));
        t.add(decl("collector.delivery.anomaly", "Delivery anomaly", "engine",
                a("pipeline", Kind.STRING, "The Pipeline"), a("batchId", Kind.STRING, "The Consignment id"),
                a("kind", Kind.STRING, "The anomaly kind"), a("day", Kind.STRING, "The file's day"),
                a("file", Kind.STRING, "The file"), o("previousFile", Kind.STRING, "The file before it"),
                a("detail", Kind.STRING, "What was seen")));
        t.add(decl("collector.file.problem", "File problem", "engine",
                a("pipeline", Kind.STRING, "The Pipeline"), a("batchId", Kind.STRING, "The Consignment id"),
                a("kind", Kind.STRING, "The problem kind"), a("day", Kind.STRING, "The file's day"),
                a("file", Kind.STRING, "The file"), a("status", Kind.STRING, "The file status"),
                a("parsedRows", Kind.INTEGER, "Rows parsed"), a("errorRows", Kind.INTEGER, "Rows in error")));
        t.add(decl("quality.schema_drift", "Schema drift", "engine",
                a("pipeline", Kind.STRING, "The Pipeline"), a("batchId", Kind.STRING, "The Consignment id"),
                a("files", Kind.LIST, "One entry per drifting file"), a("count", Kind.INTEGER, "Drifting files")));
        t.add(decl("alert-rule.fired", "Alert Rule fired", "engine",
                a("rule", Kind.STRING, "The Alert Rule"), a("metric", Kind.STRING, "The watched metric"),
                a("value", Kind.NUMBER, "The value that crossed the threshold"), a("severity", Kind.STRING, "The rule's severity"),
                a("owner", Kind.STRING, "The rule's owner; null when unowned")));
        t.add(decl("alert-rule.cleared", "Alert Rule cleared", "engine",
                a("rule", Kind.STRING, "The Alert Rule"), o("dataset", Kind.STRING, "The Dataset watched"),
                o("kpi", Kind.STRING, "The KPI watched"), o("measure", Kind.STRING, "The Measure watched"),
                o("value", Kind.NUMBER, "The value now"), o("ageSeconds", Kind.INTEGER, "The data's age (freshness)"),
                o("maximumAge", Kind.STRING, "The allowed age (freshness)"), o("key", Kind.STRING, "The storm key"),
                o("owner", Kind.STRING, "The rule's owner")));
        t.add(decl("expectation.violated", "Expectation violated", "engine",
                a("expectation", Kind.STRING, "The Expectation"), a("kind", Kind.STRING, "Its kind"),
                a("violations", Kind.INTEGER, "Rows or checks in violation"), a("severity", Kind.STRING, "Its severity")));
        t.add(decl("decision-rule.applied", "Decision Rule applied", "engine",
                a("rule", Kind.STRING, "The Decision Rule"),
                o("pipeline", Kind.STRING, "The Pipeline, for a Pipeline subject"), o("batch", Kind.STRING, "The Consignment"),
                o("job", Kind.STRING, "The Job, for a Job subject"), o("enrichment", Kind.STRING, "The Enrichment"),
                o("run", Kind.STRING, "The Run"),
                a("matched", Kind.INTEGER, "Rows matched"), a("applied", Kind.STRING, "Consequences applied, comma-separated")));
        t.add(decl("decision-rule.create-alert", "Decision Rule raised an alert", "engine",
                a("alert", Kind.STRING, "The alert name"), a("severity", Kind.STRING, "Its severity")));
        t.add(decl("decision-rule.render-widget", "Decision Rule asked for a widget render", "engine",
                a("action", Kind.STRING, "The consequence action")));
        t.add(decl("decision-rule.generate-report", "Decision Rule asked for a report", "engine",
                a("action", Kind.STRING, "The consequence action")));
        return List.copyOf(t);
    }

    private static SignalTypeDecl decl(String type, String title, String poster, SignalField... fields) {
        return new SignalTypeDecl(type, title, poster, List.of(fields), SINCE);
    }

    private static SignalField a(String name, Kind kind, String description) {
        return SignalField.always(name, kind, description);
    }

    private static SignalField o(String name, Kind kind, String description) {
        return SignalField.optional(name, kind, description);
    }
}
