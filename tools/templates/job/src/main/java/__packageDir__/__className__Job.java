package {{packageName}};

import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobContext;
import com.gamma.job.JobResult;
import com.gamma.notify.Notification;
import com.gamma.notify.NotificationAccess;
import com.gamma.signal.Severity;

import java.util.Map;

/**
 * The {@code {{id}}} Job. Everything it may touch arrives through {@link JobContext} — it owns no
 * engine object, which is exactly what lets it ship from a pack jar instead of a code change.
 */
public class {{className}}Job implements Job {

    private final JobConfig config;

    public {{className}}Job(JobConfig config) {
        this.config = config;
    }

    @Override public String name() { return config.name(); }
    @Override public String type() { return "{{id}}"; }

    /** Never called: the engine always supplies a context. */
    @Override
    public JobResult run() {
        throw new UnsupportedOperationException("{{id}} requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) {
        long t0 = System.nanoTime();

        // Resolved parameters — literals AND evaluated $-Expressions. Declared defaults are already
        // applied, and a missing required one rejected the Run before this method was entered.
        Map<String, String> params = ctx.params();
        String subject = params.getOrDefault("subject", "world");

        ctx.log().info("TODO: do the work", "subject", subject);

        // The granted Platform Service. `get` would throw naming the missing grant; `find` is the
        // tolerant form — use it when the Job should still succeed on a build without the service.
        // Under a dry run the framework substitutes a recording stand-in: this logs the would-be
        // notification and stores nothing, so `dryRun()` stays honest without a branch here.
        ctx.services().find(NotificationAccess.class).ifPresent(feed ->
                feed.notify(Notification.create("job", "JOB_RUN", ctx.runId(),
                        "{{name}}: " + subject,
                        "Emitted by the {{id}} Job.",
                        // Collapses repeat fires into one unread entry.
                        "{{id}}:" + config.name())));

        ctx.signals().emit("{{id}}.completed", Severity.INFO,
                Map.of("job", config.name(), "run", ctx.runId()));

        return JobResult.ok("did nothing for " + subject, (System.nanoTime() - t0) / 1_000_000L);
    }
}
