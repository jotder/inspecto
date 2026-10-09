package com.gamma.job;

import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.consignment.DbConsignmentOutputStore.DailyVolume;
import com.gamma.acquire.FileSequenceGaps;
import com.gamma.config.spec.GapTemplateGrammar;
import com.gamma.consignment.DbFileStageStore;
import com.gamma.consignment.FileStages;
import com.gamma.consignment.VolumeBaseline;
import com.gamma.etl.PipelineConfig;
import com.gamma.objects.IncidentAccess;
import com.gamma.signal.Severity;
import com.gamma.signal.SignalType;
import com.gamma.util.OperationsZone;
import com.gamma.util.StoreHealth;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.ArrayList;

/**
 * The {@code kpi.completeness} Job Type — the reader that turns the output registry into a completeness
 * answer for one Pipeline (completeness KPI K4; design {@code docs/archived-documents/plans-archive/completeness-kpi-k4-design.md}).
 *
 * <p>🔴 <b>It refuses before it reads.</b> Its first act is {@link #requireDurable}: unless this Space's
 * {@code consignmentOutputs} store resolved {@link StoreHealth.Status#UP}, the run throws, naming the
 * {@code -D} toggle. A KPI that read an absent store would report "nothing missing" for a Pipeline that
 * received nothing. A family with <b>no</b> {@code StoreHealth} entry was never opened and refuses the same way.
 *
 * <p>🔴 <b>{@code KPI-UNKNOWN-1}</b> (operator, 2026-10-06): a number that is not known is absent, never
 * {@code 0}. A day with no registered output carries no {@code files}/{@code rows} keys at all, and the
 * unknown-day bucket (sinks with null {@code bounds}) is reported as {@code unknownDayFiles}/
 * {@code unknownDayRows}, never folded into the day.
 */
final class KpiCompletenessJob implements Job {

    static final String TYPE = "kpi.completeness";
    static final String FAMILY = "consignmentOutputs";
    static final String TOGGLE = "consignment.outputs.backend";

    /** The declared parameters — {@code /jobs/types} publishes them and the form renders them. */
    static final List<ParameterDecl> PARAMS = List.of(
            ParameterDecl.required("pipeline", ParamType.STRING,
                    "The Pipeline whose received volume is assessed (the producer in the output registry)"),
            ParameterDecl.optional("record_day", ParamType.STRING, null,
                    "The day assessed, yyyy-MM-dd. Default: yesterday in the operations zone (-Dops.timezone)"),
            // K3: the rolling baseline. 28 / 7 / 0.3 are the design's proposed defaults (§7-f).
            ParameterDecl.of("baseline_window", ParamType.INTEGER).label("Baseline window (days)")
                    .min(1).max(366).defaultValue("28")
                    .description("How many calendar days before the assessed day form its rolling baseline").build(),
            ParameterDecl.of("min_baseline_days", ParamType.INTEGER).label("Minimum baseline days")
                    .min(1).max(366).defaultValue("7")
                    .description("Fewer observed prior days than this and the answer is NO_BASELINE (unknown)").build(),
            ParameterDecl.of("tolerance", ParamType.DECIMAL).label("Tolerance").min(0).max(1)
                    .defaultValue("0.3")
                    .description("Fraction below the baseline that is still ordinary, in [0,1]").build(),
            // K2 (operator, 2026-10-06): the template is the Collector's gap_detection.file_template; these
            // two parameters override it. ⛔ Neither set (and check_files on) refuses — never a silent zero.
            ParameterDecl.of("check_files", ParamType.BOOLEAN).label("Count missing files").defaultValue("true")
                    .description("Count missing files against the file template ({seq} or date-only). Off = volume only").build(),
            ParameterDecl.of("sequence_template", ParamType.STRING).label("File template override")
                    .description("Overrides the Collector's gap_detection.file_template, e.g. "
                            + "CDR_{yyyyMMddHH}_{seq}_*").build(),
            ParameterDecl.of("seq_scope", ParamType.STRING).label("Sequence scope override")
                    .options("PER_BUCKET", "CONTINUOUS")
                    .description("Overrides the Collector's gap_detection.seq_scope").build());

    static final String FILE_FAMILY = "fileStages";
    static final String FILE_TOGGLE = "file.stages.backend";

    private final JobConfig cfg;
    /** Pipeline name → its loaded config (for the Collector's template and id); empty when not wired. */
    private final Function<String, Optional<PipelineConfig>> pipelines;

    KpiCompletenessJob(JobConfig cfg, Function<String, Optional<PipelineConfig>> pipelines) {
        this.cfg = cfg;
        this.pipelines = pipelines == null ? n -> Optional.empty() : pipelines;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return TYPE; }

    @Override public JobResult run() {
        throw new UnsupportedOperationException(TYPE + " requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) {
        long t0 = System.nanoTime();
        // ⛔ First act, before the dry-run check: a dry run that skipped it would validate a config that cannot run.
        requireDurable(ctx.spaceId(), FAMILY, TOGGLE, "read the Pipeline's daily volume");
        String pipeline = cfg.require("pipeline");
        String recordDay = cfg.opt("record_day", LocalDate.now(OperationsZone.resolve()).minusDays(1).toString());
        LocalDate.parse(recordDay);   // a malformed day fails loudly, never compares as a string
        FileHalf files = "false".equalsIgnoreCase(cfg.opt("check_files", "true")) ? null : resolveFileHalf(ctx, pipeline);
        if (ctx.dryRun())
            return JobResult.ok(TYPE + " '" + pipeline + "' (dry run): store durable, nothing read",
                    (System.nanoTime() - t0) / 1_000_000L);

        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        if (store == null)   // belt and braces: nullness detects, StoreHealth explains — same message
            throw new IllegalStateException(refusal(FAMILY, TOGGLE, "read the Pipeline's daily volume",
                    "no output registry is installed for this space"));
        int window = Integer.parseInt(cfg.opt("baseline_window", "28"));
        int minDays = Integer.parseInt(cfg.opt("min_baseline_days", "7"));
        double tolerance = Double.parseDouble(cfg.opt("tolerance", "0.3"));
        // At least STREAK_DAYS-1 prior days are read, so the unknown streak is measurable at any window.
        String from = LocalDate.parse(recordDay).minusDays(Math.max(window, STREAK_DAYS - 1)).toString();
        // ⛔ The series goes to assess untouched: it already ignores the unknown bucket and treats an absent
        // day as NO_OBSERVATION. Pre-filtering here would duplicate, then contradict, that pinned rule.
        List<DailyVolume> series = store.dailyVolume(pipeline, from, recordDay);
        VolumeBaseline.Assessment a = VolumeBaseline.assess(series, recordDay, window, minDays, tolerance);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipeline", pipeline);
        payload.put("recordDay", recordDay);
        payload.put("status", a.status().name());
        putVolume(payload, series, recordDay);
        payload.put("baselineDays", a.baselineDays());
        if (a.baselineRows() >= 0) payload.put("baselineRows", a.baselineRows());   // omitted, never -1 or 0
        payload.put("deviation", a.deviation());                                    // JSON null when undefined
        boolean breach = a.status() == VolumeBaseline.Status.BREACH;
        FileSequenceGaps.Report gaps = files == null ? null : readGaps(files, recordDay);
        if (gaps != null) putGaps(payload, gaps);
        boolean fileGaps = gaps != null && gaps.hasGaps();
        int streak = unknownStreak(series, recordDay);
        payload.put("unknownStreakDays", streak);
        ctx.signals().emit(SignalType.KPI_COMPLETENESS_EVALUATED, breach || fileGaps ? Severity.WARN : Severity.INFO, payload);
        // §7-h (operator, 2026-10-06): 3+ consecutive days with nothing registered is a WARN, never an Incident.
        if (streak >= STREAK_DAYS)
            ctx.signals().emit(SignalType.KPI_COMPLETENESS_UNKNOWN_STREAK, Severity.WARN, payload);
        // §7-g (operator, 2026-10-06): a volume BREACH or a file gap opens ONE Incident per Pipeline-day carrying
        // both findings. ⛔ NO_BASELINE / NO_OBSERVATION alone are unknown, not breached.
        if (breach || fileGaps) {
            ctx.signals().emit(SignalType.KPI_COMPLETENESS_BREACHED, Severity.WARN, payload);
            openIncident(ctx, pipeline, recordDay, a, breach, gaps);
        }
        return JobResult.ok(TYPE + " '" + pipeline + "' " + recordDay + ": " + a.status() + ", " + describe(payload),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /** Consecutive UNKNOWN days (nothing registered) a WARN needs — §7-h (operator, 2026-10-06). */
    static final int STREAK_DAYS = 3;

    /** Consecutive days ending at {@code recordDay} with no registered output, within the fetched series. */
    static int unknownStreak(List<DailyVolume> series, String recordDay) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DailyVolume v : series) if (v.recordDay() != null) seen.add(v.recordDay());
        int n = 0;
        for (LocalDate d = LocalDate.parse(recordDay); n < STREAK_DAYS && !seen.contains(d.toString()); d = d.minusDays(1))
            n++;
        return n;
    }

    /**
     * ONE Incident per Pipeline-day (operator, 2026-10-06, §7-g): scope = the pipeline id, deduped centrally by
     * {@link IncidentAccess} on {@code pipelineDay} = {@code <pipeline>@<day>}, carrying both halves' findings.
     * A later run the same day whose findings differ updates that Incident's attributes (audited); identical
     * findings are suppressed. An absent grant leaves the run signal-only; a dry run opens nothing.
     */
    private static void openIncident(JobContext ctx, String pipeline, String recordDay, VolumeBaseline.Assessment a,
                                     boolean volumeBreach, FileSequenceGaps.Report gaps) {
        Optional<IncidentAccess> incidents = ctx.services().find(IncidentAccess.class);
        if (incidents.isEmpty()) {
            ctx.log().warn("completeness breach not promoted: no incidents service granted", "pipeline", pipeline);
            return;
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("pipeline", pipeline);
        attrs.put("recordDay", recordDay);
        attrs.put("pipelineDay", pipeline + "@" + recordDay);
        attrs.put("volumeStatus", a.status().name());
        StringBuilder msg = new StringBuilder("Pipeline " + pipeline + " on " + recordDay + ":");
        if (volumeBreach) {
            attrs.put("rows", String.valueOf(a.actualRows()));
            attrs.put("baselineRows", String.valueOf(a.baselineRows()));
            msg.append(" received ").append(a.actualRows()).append(" row(s) against a ").append(a.baselineDays())
                    .append("-day baseline of ").append(a.baselineRows()).append(".");
        }
        if (gaps != null) {
            attrs.put("missingFiles", String.valueOf(gaps.missingFiles()));
            attrs.put("emptyBuckets", String.valueOf(gaps.emptyBuckets().size()));
            if (gaps.hasGaps())
                msg.append(" ").append(gaps.missingFiles()).append(" missing file(s), ")
                        .append(gaps.emptyBuckets().size()).append(" empty bucket(s) against ").append(gaps.template()).append(".");
        }
        String what = volumeBreach && gaps != null && gaps.hasGaps() ? "volume and files"
                : volumeBreach ? "volume below baseline" : "missing files";
        incidents.get().openOrUpdateIncident("Completeness: " + pipeline + " " + recordDay + " — " + what,
                msg.toString(), "WARNING", pipeline, attrs, "pipelineDay");
    }

    /** K2's resolved inputs: the template, its scope, and the Collector whose filenames are read. */
    record FileHalf(String template, FileSequenceGaps.SeqScope scope, String collectorId) {}

    /**
     * The override parameters win over the Collector's {@code gap_detection} (operator, 2026-10-06). Refuses —
     * before anything is read — when no template is set anywhere, or when file stages are not durable.
     */
    private FileHalf resolveFileHalf(JobContext ctx, String pipeline) {
        PipelineConfig pc = pipelines.apply(pipeline).orElseThrow(() -> new IllegalStateException(TYPE
                + " cannot count missing files for '" + pipeline + "': no loaded pipeline config has that name, so "
                + "its Collector is unknown. Fix the pipeline parameter, or set check_files: false."));
        PipelineConfig.GapDetection gd = pc.collector().gapDetection();
        String template = cfg.opt("sequence_template", gd.fileTemplate());
        String scope = cfg.opt("seq_scope", gd.seqScope());
        if (template == null || template.isBlank())
            throw new IllegalStateException(TYPE + " cannot count missing files for '" + pipeline + "': no "
                    + "file template is set. Add collector.gap_detection.file_template (and seq_scope when it holds "
                    + "{seq}) to the pipeline, or the sequence_template / seq_scope job parameters, or set "
                    + "check_files: false. "
                    + "(gap_detection.sequence is the live detector's one-token template and cannot count files.)");
        String bad = GapTemplateGrammar.refusal(template, scope);
        if (bad != null) throw new IllegalArgumentException(bad);
        requireDurable(ctx.spaceId(), FILE_FAMILY, FILE_TOGGLE, "read the Collector's filename history");
        // A date-only template (no {seq}, operator 2026-10-06) has no scope: one expected file per bucket.
        return new FileHalf(template, scope == null || scope.isBlank() ? null
                : FileSequenceGaps.SeqScope.valueOf(scope.trim().toUpperCase()), pc.collector().id());
    }

    private static FileSequenceGaps.Report readGaps(FileHalf f, String recordDay) {
        DbFileStageStore stages = FileStages.shared();
        if (stages == null)
            throw new IllegalStateException(refusal(FILE_FAMILY, FILE_TOGGLE, "read the Collector's filename history",
                    "no file-stages registry is installed for this space"));
        LocalDate day = LocalDate.parse(recordDay);
        // recorded_at is processing time: pad a day each side, let analyze match and window by the NAME.
        List<String> names = new ArrayList<>();
        for (String rel : stages.relativePaths(f.collectorId(), day.minusDays(1) + " 00:00:00", day.plusDays(2) + " 00:00:00"))
            names.add(rel.substring(rel.lastIndexOf('/') + 1));
        return FileSequenceGaps.analyze(f.template(), names, day.atStartOfDay(), day.atTime(23, 59, 59), f.scope());
    }

    static void putGaps(Map<String, Object> payload, FileSequenceGaps.Report r) {
        payload.put("fileTemplate", r.template());
        if (r.scope() != null) payload.put("seqScope", r.scope().name());   // absent for a date-only template
        payload.put("observedFiles", r.observedFiles());
        payload.put("missingFiles", r.missingFiles());          // exact: interior holes, or empty days of a date-only template
        payload.put("emptyBuckets", r.emptyBuckets().size());   // buckets, never a file count
        payload.put("unmatchedFiles", r.unmatched());           // non-zero usually means a wrong template
    }

    /** The target day's volume and the unknown-day bucket — each present only when the store holds it. */
    static void putVolume(Map<String, Object> payload, List<DailyVolume> series, String recordDay) {
        for (DailyVolume v : series) {
            if (v.recordDay() == null) {
                payload.put("unknownDayFiles", v.files());
                payload.put("unknownDayRows", v.rows());
            } else if (v.recordDay().equals(recordDay)) {
                payload.put("files", v.files());
                payload.put("rows", v.rows());
            }
        }
    }

    static String describe(Map<String, Object> p) {
        String day = p.containsKey("rows") ? p.get("rows") + " row(s) in " + p.get("files") + " file(s)"
                : "volume unknown (nothing registered for the day)";
        return p.containsKey("unknownDayRows") ? day + "; " + p.get("unknownDayRows") + " row(s) with no known day" : day;
    }

    /** Refuse unless {@code family} resolved to a durable backend in this space. Absence == not configured. */
    static void requireDurable(String spaceId, String family, String toggle, String forWhat) {
        StoreHealth.Resolved r = StoreHealth.of(spaceId).get(family);
        if (r != null && r.status() == StoreHealth.Status.UP) return;
        String why = r == null ? "no record that the store was ever opened"
                : r.status() + " (target: " + r.target() + ") — " + r.detail();
        throw new IllegalStateException(refusal(family, toggle, forWhat, why));
    }

    private static String refusal(String family, String toggle, String forWhat, String why) {
        return TYPE + " needs a durable '" + family + "' store to " + forWhat + ", but this space resolved "
                + why + " — a completeness KPI that reads an absent or in-memory store reports 'nothing missing' "
                + "for a pipeline that received nothing, which is the one answer it exists to rule out. Configure -D"
                + toggle + " (and its .db.url) for this space, or remove this job.";
    }
}
