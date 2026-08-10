package com.gamma.job;

import com.gamma.notify.MailAccess;
import com.gamma.signal.Severity;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The {@code mail.send} Job Type — composes one email from its declared parameters and sends it to the
 * recipients the author named (job-parameter-contract §9).
 *
 * <p>It is the <b>reference consumer of the declaration contract</b>: its four parameters between them
 * exercise {@code label}, {@code tier}, {@code group}, {@code multi} and the {@code EMAIL}/{@code TEXT}
 * types, so the authoring form it produces is generated entirely from {@link MailSendJobType#DESCRIPTOR}
 * with no UI code that knows this Job Type exists. That is the property step 15 is there to prove.
 *
 * <p>Delivery goes through the {@code mail} Platform Service ({@link MailAccess}), which in turn delivers
 * over the configured {@code email} NotificationChannel — so this class contains no SMTP knowledge and a
 * deployment configures mail in exactly one place.
 *
 * <h3>What a $-Expression can and cannot do here</h3>
 * Every field accepts one — {@code to: $signal.recipient} resolves at fire time and is then held to the
 * {@code EMAIL} contract like any literal. But evaluation is <b>whole-value</b> (§6.1), so a token inside a
 * longer string stays literal: {@code "Daily report for $yesterday"} sends those characters verbatim. A
 * per-Run subject has to come from a whole-value parameter.
 */
final class MailSendJob implements Job {

    private final JobConfig cfg;

    MailSendJob(JobConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "mail.send"; }

    @Override public JobResult run() {
        throw new UnsupportedOperationException("mail.send requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) {
        long t0 = System.nanoTime();
        Map<String, String> p = ctx.params();
        List<String> to = addresses(p.get("to"));
        List<String> cc = addresses(p.get("cc"));
        String subject = p.getOrDefault("subject", "");

        if (to.isEmpty()) {
            // `to` is declared required, so the resolver refuses a Run that binds nothing. Reaching here
            // means it resolved to something that is entirely blank — report it rather than "succeeding"
            // at sending to no one.
            return JobResult.failed("mail.send: `to` resolved to no addresses", (System.nanoTime() - t0) / 1_000_000L);
        }

        // Under a dry run the framework substitutes recording stand-ins for granted services, so this
        // sends nothing and says so — the same contract the notifications service follows.
        boolean sent;
        try {
            sent = ctx.services().get(MailAccess.class).send(to, cc, subject, p.getOrDefault("body", ""));
        } catch (Exception e) {
            ctx.log().error("mail.send failed", e, "recipients", to.size() + cc.size());
            return JobResult.failed("mail.send: " + e, (System.nanoTime() - t0) / 1_000_000L);
        }

        // Recipients are logged by COUNT, not by address: a Run Log is widely readable and the addresses
        // are the operator's contact data, not diagnostics.
        ctx.log().info(sent ? "mail sent" : "no email channel configured — nothing sent",
                "recipients", to.size() + cc.size(), "subject", subject);
        ctx.signals().emit("mail.sent", Severity.INFO,
                Map.of("job", cfg.name(), "run", ctx.runId(), "recipients", to.size() + cc.size(), "sent", sent));

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return sent
                ? JobResult.ok("sent to " + (to.size() + cc.size()) + " recipient(s)", ms)
                // Not a failure: a deployment without SMTP makes this Job inert rather than red on every
                // fire, matching how NotificationService skips an unconfigured channel.
                : JobResult.ok("no email channel configured — nothing sent", ms);
    }

    /** A declared `multi` parameter arrives as CSV (§7.5); blanks dropped. */
    private static List<String> addresses(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
