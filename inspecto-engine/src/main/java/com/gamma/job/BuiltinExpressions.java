package com.gamma.job;

import static com.gamma.job.ExpressionDecl.Form.FUNCTION;
import static com.gamma.job.ExpressionDecl.Form.PREFIX;
import static com.gamma.job.ExpressionDecl.TriggerKind.ON_SIGNAL;
import static com.gamma.job.ExpressionDecl.literal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gamma.util.DottedPath;

/**
 * The Expression vocabulary the engine ships with (job-parameter-contract §2) — ten literals, one prefix
 * and four function tokens, lifted verbatim out of {@code ParameterResolver.deduce()}'s switch when the
 * {@link ExpressionRegistry} seam landed. Behaviour is unchanged: an unresolvable-but-declared token
 * yields {@link Optional#empty()}, which the resolver still treats as "fall through to the next layer".
 */
final class BuiltinExpressions implements ExpressionProvider {

    private static final Pattern DATE_FN = Pattern.compile("\\$(day|month|year)\\(\\s*(-?\\d+)\\s*\\)");
    private static final Pattern UPSTREAM =
            Pattern.compile("\\$upstream\\(([^)]+)\\)\\.artifact\\(([^)]+)\\)\\.(\\w+)");

    private static final List<ExpressionDecl> DECLS = List.of(
            literal("$today", ParamType.DATE, "The date at fire time, in the Job's zone", "2026-08-07"),
            literal("$yesterday", ParamType.DATE, "The day before the fire date", "2026-08-06"),
            literal("$tomorrow", ParamType.DATE, "The day after the fire date", "2026-08-08"),
            literal("$now", ParamType.INSTANT, "The fire-time instant", "2026-08-07T06:00:00Z"),
            literal("$now.epoch_seconds", ParamType.INTEGER, "Fire time as epoch seconds", "1785045600"),
            literal("$now.epoch_millis", ParamType.INTEGER, "Fire time as epoch milliseconds", "1785045600000"),
            // These three are NOT contextFree: they need a firing Run/Job, so the catalog must show their
            // worked sample rather than fabricate a live preview from a request-time context.
            runBound("$run.id", ParamType.STRING, "This Run's id", "run-20260807-060000-1"),
            literal("$run.fire_time", ParamType.INSTANT, "When this Run fired", "2026-08-07T06:00:00Z"),
            runBound("$run.actor", ParamType.STRING, "Who or what triggered this Run", "cron"),
            runBound("$job.last_success_time", ParamType.INSTANT,
                    "This Job's success watermark — the incremental-window anchor; unset before the first success",
                    "2026-08-06T06:00:04Z"),
            new ExpressionDecl("$signal.", PREFIX, ParamType.STRING,
                    "A dotted field of the firing Signal's payload", "$signal.dataset",
                    Set.of(ON_SIGNAL), false),
            dateFn("day", "days"), dateFn("month", "months"), dateFn("year", "years"),
            new ExpressionDecl("$upstream(<job>).artifact(<name>).<attr>", FUNCTION, ParamType.STRING,
                    "An attribute (ref | rows | bytes | watermark | time_range) of a predecessor Job's "
                            + "latest Run Artifact",
                    "$upstream(loader).artifact(output).ref", ExpressionDecl.ANY_TRIGGER, false));

    /** A literal token available on any Trigger but needing a firing Run/Job — no live preview. */
    private static ExpressionDecl runBound(String token, ParamType yields, String description, String example) {
        return new ExpressionDecl(token, ExpressionDecl.Form.LITERAL, yields, description, example,
                ExpressionDecl.ANY_TRIGGER, false);
    }

    private static ExpressionDecl dateFn(String unit, String plural) {
        return new ExpressionDecl("$" + unit + "(n)", FUNCTION, ParamType.DATE,
                "The fire date shifted by n " + plural + " (negative = past)",
                "$" + unit + "(-1)", ExpressionDecl.ANY_TRIGGER, true);
    }

    @Override public List<ExpressionDecl> declarations() { return DECLS; }

    @Override
    public Optional<String> evaluate(String expr, ExpressionContext ctx) {
        return Optional.ofNullable(switch (expr) {
            case "$today"     -> fireDate(ctx).toString();
            case "$yesterday" -> fireDate(ctx).minusDays(1).toString();
            case "$tomorrow"  -> fireDate(ctx).plusDays(1).toString();
            case "$now"       -> ctx.fireTime().toString();
            case "$now.epoch_seconds" -> String.valueOf(ctx.fireTime().getEpochSecond());
            case "$now.epoch_millis"  -> String.valueOf(ctx.fireTime().toEpochMilli());
            case "$run.id"        -> ctx.runId();
            case "$run.fire_time" -> ctx.fireTime().toString();
            case "$run.actor"     -> ctx.actor();
            case "$job.last_success_time" -> ctx.lastSuccess().get()
                    .map(t -> t.atZone(ctx.zone()).toInstant().toString()).orElse(null);
            default -> other(expr, ctx);
        });
    }

    /** The prefix and function forms — {@code null} when the shape declared it but the value is absent. */
    private static String other(String expr, ExpressionContext ctx) {
        if (expr.startsWith("$signal.")) {
            Object v = DottedPath.resolve(ctx.signalPayload(), expr.substring("$signal.".length()));
            return v == null ? null : String.valueOf(v);
        }
        Matcher m = DATE_FN.matcher(expr);
        if (m.matches()) {
            LocalDate base = fireDate(ctx);
            int n = Integer.parseInt(m.group(2));
            LocalDate shifted = switch (m.group(1)) {
                case "day"   -> base.plusDays(n);
                case "month" -> base.plusMonths(n);
                default      -> base.plusYears(n);   // "year"
            };
            return shifted.toString();
        }
        Matcher u = UPSTREAM.matcher(expr);
        if (u.matches()) return upstreamAttr(ctx, u.group(1).trim(), u.group(2).trim(), u.group(3));
        return null;
    }

    private static LocalDate fireDate(ExpressionContext ctx) {
        return LocalDate.ofInstant(ctx.fireTime(), ctx.zone());
    }

    /** {@code $upstream(<job>).artifact(<name>).<attr>} — an attr of a predecessor's latest artifact (§10). */
    private static String upstreamAttr(ExpressionContext ctx, String job, String artifact, String attr) {
        return ctx.upstream().apply(job, artifact).map(a -> switch (attr) {
            case "ref"        -> a.ref();
            case "rows"       -> String.valueOf(a.rows());
            case "bytes"      -> String.valueOf(a.bytes());
            case "watermark"  -> a.watermark();
            case "time_range" -> a.timeRange();
            default           -> null;
        }).orElse(null);
    }
}
