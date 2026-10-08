package com.gamma.job;

import com.gamma.pipeline.SpaceConfigRoot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.safety.PathJail;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventType;
import com.gamma.notify.MailAccess;
import com.gamma.notify.MailAttachment;
import com.gamma.notify.MailAttachments;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.pipeline.XlsxWorkbook;
import com.gamma.query.DatasetRelation;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.util.SqlIdent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link JobType#REPORT} job: computes a report on a schedule and emits it as a single structured
 * line on the {@code inspecto.events} logger — plus, since BI-4, optionally <b>delivers</b> it: with
 * {@code out_dir} the report is rendered to a timestamped artifact file (a directory being the first
 * delivery destination — point it at a mounted share to hand off) and a {@link EventType#REPORT_READY}
 * event is emitted, which the notification layer routes to the configured external channels (webhook
 * POST; SMTP text with the artifact path). Since ASSURE-XLSX-ATTACHMENTS-1 a {@code recipients} list is also
 * mailed directly through the {@code mail} Platform Service, and {@code attach: true} carries the delivered
 * artifact itself — the Job's OWN Run Artifact, re-jailed by {@link MailAttachments#fromRunArtifact}.
 *
 * <h3>Scopes</h3>
 * <ul>
 *   <li>{@code status} (default) — the live snapshot from {@link ReportRunner}.</li>
 *   <li>{@code batch} / {@code service} / {@code all} — the historical batch-audit rollup.</li>
 *   <li>{@code dataset} (BI-4 export) — a headless BI query over a Dataset: params {@code dataset}
 *       (component id, required), {@code measures} (comma-separated {@code agg(field)}/{@code count};
 *       absent = raw rows), {@code group_by} (comma-separated columns), {@code limit} (default 10000).
 *       Renders CSV by default ({@code format: xlsx} a workbook via DuckDB's {@code excel} extension on a
 *       sealed connection, {@link XlsxWorkbook}; {@code format: png}/{@code pdf} render a table-image snapshot, capped
 *       at {@link TablePngRenderer#MAX_ROWS} rows — {@code pdf} is the same snapshot wrapped in a
 *       minimal hand-written PDF via {@link PdfRenderer}, no PDF library on the classpath); reports
 *       render JSON.</li>
 * </ul>
 *
 * <p>Params: {@code scope}, {@code out_dir}, {@code format}
 * ({@code json} | {@code csv} | {@code xlsx} | {@code png} | {@code pdf}), {@code recipients} (comma-separated
 * addresses mailed once the artifact is delivered), {@code attach} ({@code true} attaches it), and the
 * dataset-scope params above. Text cells in {@code csv} and {@code xlsx} are formula-neutralised
 * ({@link XlsxWorkbook#neutralise}).
 */
final class ReportJob implements Job {

    private static final Logger events = LoggerFactory.getLogger("inspecto.events");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final JobConfig cfg;
    private final ReportRunner reports;
    /** The space's data dir (for a dataset export's {@code physicalRef}); {@code null} degrades to view-backed only. */
    private final String dataDir;

    ReportJob(JobConfig cfg, ReportRunner reports) {
        this(cfg, reports, null);
    }

    ReportJob(JobConfig cfg, ReportRunner reports, String dataDir) {
        this.cfg = cfg;
        this.reports = reports;
        this.dataDir = dataDir;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "report"; }

    @Override
    public JobResult run() throws Exception {
        return execute(null);   // legacy no-ctx path — no Run Artifact recorder, no mail service
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        return execute(ctx);   // JobService invokes this — records the delivered file (R7)
    }

    private JobResult execute(JobContext ctx) throws Exception {
        ArtifactRecorder artifacts = ctx == null ? null : ctx.artifacts();
        // SCHEDULE-EXPORT-DASHBOARD-SCOPE-1 (declined until demand): a legacy dashboard schedule carries
        // `dashboardId`, which no scope reads — fail the Run loudly instead of emitting a status snapshot.
        if (cfg.opt("dashboardId", null) != null)
            throw new IllegalArgumentException(
                    "Dashboard export is not supported — recreate this schedule for a Dataset");
        String scope = cfg.opt("scope", "status").toLowerCase();
        long t0 = System.nanoTime();

        Object report;
        List<Map<String, Object>> rows = null;   // dataset scope: tabular result for CSV rendering
        int cut = -1;                            // dataset scope: the row cap the result hit, else -1
        switch (scope) {
            case "status"                  -> report = reports.statusReport();
            case "batch", "service", "all" -> report = reports.serviceReport();
            case "dataset" -> {
                QueryExecutor.Result r = datasetRows();
                rows = r.rows();
                report = rows;
                if (r.truncated()) cut = r.rowCount();   // the cap the executor trimmed to (the spec ceiling may be below the configured limit)
            }
            default -> throw new IllegalArgumentException(
                    "report scope must be 'status', 'batch' or 'dataset', got '" + scope + "'");
        }

        // The structured log snapshot stays — delivery is additive (BI-4).
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "report");
        line.put("job", cfg.name());
        line.put("scope", scope);
        line.put("report", report);
        events.info(JSON.writeValueAsString(line));

        if (cut >= 0)
            EventLog.current().emit(Event.builder(EventType.REPORT_TRUNCATED).level(EventLevel.WARN)
                    .source(ReportJob.class.getName())
                    .message("Report '" + cfg.name() + "' has more than " + cut + " rows - the result is cut at the limit; "
                            + "raise this job's `limit:` or narrow the spec")
                    .attr("job", cfg.name()).attr("limit", cut));
        String delivered = deliver(scope, report, rows, cut, artifacts);
        String mailed = mail(ctx, delivered);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return JobResult.ok("report '" + scope + "' emitted to inspecto.events"
                + (cut >= 0 ? " (TRUNCATED at " + cut + " rows)" : "")
                + (delivered != null ? " and delivered to " + delivered : "")
                + (mailed != null ? "; " + mailed : ""), ms);
    }

    /**
     * Mail the delivered report to {@code recipients}, attaching it when {@code attach: true}. The attachment
     * is ONLY the artifact this Run just wrote and recorded — there is no parameter naming a path — and it is
     * re-jailed (allowed roots, {@code *.secrets} refused), type-allowlisted and size-capped on the way in.
     * Returns a summary for the result message, or {@code null} when nothing was asked for.
     */
    private String mail(JobContext ctx, String delivered) throws Exception {
        List<String> to = split(cfg.opt("recipients", ""));
        // The AUTHORED config decides (never a trigger arg or a bound Signal), through the ONE shared predicate.
        boolean attach = AttachApprovals.attaches(cfg);
        if (to.isEmpty()) {
            if (attach) throw new IllegalArgumentException("attach: true needs recipients to mail the report to");
            return null;
        }
        if (delivered == null)
            throw new IllegalArgumentException("recipients needs out_dir: only a delivered report can be mailed");
        if (attach) requireApprovedVersion(ctx);
        MailAccess mail = ctx == null ? null : ctx.services().find(MailAccess.class).orElse(null);
        if (mail == null) return "not mailed (no mail service granted to this Run)";
        List<MailAttachment> files = attach
                ? List.of(MailAttachments.fromRunArtifact(Path.of(delivered), PathJail.allowedRoots(),
                        SpaceConfigRoot.current()))
                : List.of();
        boolean sent = mail.send(to, List.of(), "Report '" + cfg.name() + "' ready",
                "Report '" + cfg.name() + "' was delivered to " + delivered
                        + (attach ? " and is attached." : "."), files);
        return sent ? "mailed to " + to.size() + " recipient(s)" + (attach ? " with attachment" : "")
                : "not mailed (no email channel configured)";
    }

    /**
     * Render + write the artifact when {@code out_dir} is set, record it as a {@code file} Run Artifact
     * (when a recorder is present, so {@code GET /jobs/{name}/runs/{runId}/artifacts/report/content} can
     * serve it), emit REPORT_READY; returns the path or null.
     */
    private String deliver(String scope, Object report, List<Map<String, Object>> rows, int cut, ArtifactRecorder artifacts)
            throws Exception {
        String outDir = cfg.opt("out_dir", null);
        if (outDir == null) return null;
        String format = cfg.opt("format", rows != null ? "csv" : "json").toLowerCase();
        // JOB-PATH-REPORT-ENRICH-SPLIT-1: the SAME rule the 422 gate and every other job path reader
        // applies — a relative `out_dir` resolves against the Space config root, not the process
        // working directory (JOB-DIR-CWD-CONTAINMENT-1, operator 2026-09-16). This site was left on the
        // plain `requireUnderAny` when that landed, so a report delivered somewhere the gate had not
        // checked. ⛔ Do not reintroduce a second rule here; see PathJail.resolveJobPath's javadoc.
        Path dir = PathJail.requireJobPathUnderAny(
                PathJail.allowedRoots(), SpaceConfigRoot.current(), outDir, "out_dir");
        Files.createDirectories(dir);
        Path artifact = dir.resolve(cfg.name() + "_" + TS.format(LocalDateTime.now())
                + ("csv".equals(format) ? ".csv" : "xlsx".equals(format) ? ".xlsx" : "png".equals(format) ? ".png"
                        : "pdf".equals(format) ? ".pdf" : ".json"));
        if ("xlsx".equals(format)) {
            if (rows == null) throw new IllegalArgumentException(
                    "format xlsx requires scope dataset (rollup reports render as json)");
            XlsxWorkbook.write(cfg.name(), withTruncationNote(rows, cut), artifact);
        } else if ("csv".equals(format)) {
            if (rows == null) throw new IllegalArgumentException(
                    "format csv requires scope dataset (rollup reports render as json)");
            Files.writeString(artifact, toCsv(withTruncationNote(rows, cut)));
        } else if ("png".equals(format)) {
            if (rows == null) throw new IllegalArgumentException(
                    "format png requires scope dataset (rollup reports render as json)");
            TablePngRenderer.render(cfg.name(), rows, artifact);
        } else if ("pdf".equals(format)) {
            if (rows == null) throw new IllegalArgumentException(
                    "format pdf requires scope dataset (rollup reports render as json)");
            PdfRenderer.render(cfg.name(), rows, artifact);
        } else {
            Files.writeString(artifact, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        if (artifacts != null) artifacts.file("report", artifact, Files.size(artifact));
        int rowCount = rows != null ? rows.size() : -1;
        EventLog.current().emit(Event.builder(EventType.REPORT_READY)
                .source(ReportJob.class.getName())
                .message("Report '" + cfg.name() + "' (" + scope + ") ready: " + artifact
                        + (rowCount >= 0 ? " (" + rowCount + " row(s))" : ""))
                .attr("job", cfg.name())
                .attr("scope", scope)
                .attr("path", artifact.toString())
                .attr("truncated", cut >= 0));
        return artifact.toString();
    }

    /**
     * The RUN-TIME lock (round 3): an attaching report sends only the exact Job version a four-eyes approval
     * fingerprinted ({@link AttachApprovals}) — template expansion, hand edits, imports and recovery creates included.
     * Anything else fails the Run, with an audit row and a Signal, before a byte is mailed.
     */
    private void requireApprovedVersion(JobContext ctx) throws Exception {
        Path root = SpaceConfigRoot.current();
        String fp, why;
        try {
            fp = AttachApprovals.fingerprint(cfg, root, dataDir);
            if (AttachApprovals.approved(root, cfg.name(), fp)) return;
            why = "attach not approved for this job version; re-approve";
        } catch (IllegalArgumentException unresolvable) {
            fp = "unresolvable";
            why = unresolvable.getMessage();
        }
        EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                .message("Report '" + cfg.name() + "' refused to send its attachment: " + why)
                .action("report.attach.refused").actionCategory("security")
                .attr("job", cfg.name()).attr("fingerprint", fp));
        if (ctx != null)
            ctx.signals().emit("report.attach.refused", com.gamma.signal.Severity.WARN,
                    Map.of("job", cfg.name(), "reason", why, "fingerprint", fp));
        throw new IllegalStateException("report '" + cfg.name() + "': " + why);
    }

    /** The dataset-scope export rows: a headless BI query compiled from this job's params (BI-4/BI-7). */
    private QueryExecutor.Result datasetRows() throws Exception {
        // Space-scoped — see SpaceConfigRoot (MATERIALIZE-SPACE-ROOT-1).
        Path writeRoot = SpaceConfigRoot.requireCurrent("scope dataset");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", cfg.require("dataset"));
        // The grammar's own split (MEASURE-SHORTHAND-ONE-HOME-1); message unchanged (no context prefix).
        List<Map<String, Object>> measures =
                new ArrayList<>(MeasureCompiler.splitShorthand(split(cfg.opt("measures", "")), null));
        if (!measures.isEmpty()) body.put("measures", measures);
        List<String> groupBy = split(cfg.opt("group_by", ""));
        if (!groupBy.isEmpty()) body.put("groupBy", groupBy);
        int limit = datasetLimit();
        body.put("limit", limit);

        MeasureCompiler.Spec spec = measures.isEmpty() && groupBy.isEmpty()
                ? null   // raw export: SELECT * over the dataset (no aggregation)
                : MeasureCompiler.parse(body, 10_000, 100_000);
        // One row PAST the cap (BI-QUERY-TRUNCATION-1): the compiled statement ends in its own LIMIT, so asking for
        // exactly the cap could never show the result was cut. The executor trims back to the cap and flags it.
        String sql = spec != null ? MeasureCompiler.compile(spec.withLimit(spec.limit() + 1))
                : "SELECT * FROM " + SqlIdent.q(cfg.require("dataset")) + " LIMIT " + (limit + 1);

        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> dataset = store.get("dataset", cfg.require("dataset"))
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new IllegalArgumentException("unknown dataset '" + cfg.require("dataset") + "'"));
        String relationSql = DatasetRelation.relationSql(dataset,
                (dataDir == null || dataDir.isBlank()) ? null : Path.of(dataDir),
                new ViewStore(writeRoot.resolve("views")));

        // The cap the executor trims to must be the one the SQL's probe was built from: the spec parser clamps to its
        // maximum, so an unclamped `limit` above it would leave the executor waiting for rows the SQL can never return.
        int cap = spec != null ? spec.limit() : limit;
        return QueryExecutor.run(new QueryExecutor.Request(
                cfg.require("dataset"), relationSql, sql, cap, 0, List.of(), List.of()));
    }

    /** The configured row cap (default 10,000), as the request carries it. */
    private int datasetLimit() {
        return Integer.parseInt(cfg.opt("limit", "10000"));
    }

    /**
     * A cut report says so inside the file: one final row whose {@code _note} cell reads "truncated at N rows" (a
     * new last column; the data columns keep their types). A report that fit is returned untouched.
     */
    private static List<Map<String, Object>> withTruncationNote(List<Map<String, Object>> rows, int cut) {
        if (cut < 0) return rows;
        List<Map<String, Object>> out = new ArrayList<>(rows);
        out.add(new LinkedHashMap<>(Map.of("_note", "truncated at " + cut + " rows")));
        return out;
    }

    /** Rows → CSV: header = union of row keys in first-seen order; RFC-ish quoting. */
    private static String toCsv(List<Map<String, Object>> rows) {
        Set<String> header = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) header.addAll(r.keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", header.stream().map(ReportJob::csv).toList())).append('\n');
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>(header.size());
            for (String h : header) {
                Object v = r.get(h);
                // A NUMBER cannot be a formula, so -3 stays -3; only text is neutralised (CSV injection).
                cells.add(v instanceof Number ? String.valueOf(v) : csv(v == null ? "" : String.valueOf(v)));
            }
            sb.append(String.join(",", cells)).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String raw) {
        String s = XlsxWorkbook.neutralise(raw);   // CSV injection: the same rule as the workbook
        return (s.contains(",") || s.contains("\"") || s.contains("\n"))
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static List<String> split(String csvList) {
        List<String> out = new ArrayList<>();
        for (String s : csvList.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
