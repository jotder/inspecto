package com.gamma.notify;

import com.gamma.api.PublicApi;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Platform Service {@code mail}: send one message to <b>explicitly named recipients</b>. Granted to a Run
 * via a Job Type's {@code requires: [mail]} declaration and looked up as
 * {@code ctx.services().get(MailAccess.class)}. The reference consumer is the {@code mail.send} built-in
 * Job Type (job-parameter-contract §9).
 *
 * <h3>Why this is separate from {@link NotificationAccess}</h3>
 * They answer different questions. {@code notifications} puts an item in the appUser's in-app feed and lets
 * {@link NotificationService} fan it out per the user's stored preferences — the recipient is the product's
 * decision. This one addresses a message the <em>author</em> named in a Job's parameters. Routing the second
 * through the first would either ignore the declared recipients or silently retarget them at whoever the
 * feed happens to be configured for, and neither is honest.
 *
 * <h3>Delivery goes through the channel seam, not a second mail client</h3>
 * The implementation delivers over the discovered {@code email} {@link NotificationChannel} —
 * {@link NotificationChannel#deliver(Notification, String)} already exists to address an explicit
 * destination, and {@code SmtpEmailChannel} honours it (falling back to its fixed {@code notify.smtp.to}
 * only when the target is blank). So SMTP configuration, transport and credentials stay in exactly one
 * place, and any future email transport that implements the SPI serves this Job Type with no change here.
 *
 * <p>⚠ <b>The channel seam has no CC concept.</b> {@code deliver(n, target)} takes one recipient list, so
 * {@code cc} recipients are appended to it and receive the message as ordinary addressees. That is a real
 * (small) fidelity loss against §9's declaration, kept deliberately: the alternative is widening a
 * {@code @PublicApi} SPI for one caller. Recorded in {@code docs/BACKLOG.md}.
 *
 * @since 5.1.0
 */
@PublicApi(since = "4.0.0")
public interface MailAccess {

    /**
     * Send {@code subject}/{@code body} to {@code to} (plus {@code cc}, see the CC note above).
     *
     * @return {@code true} when a configured email channel accepted it; {@code false} when no email
     *         channel is configured — a deployment without SMTP makes this Job Type inert rather than
     *         failing every Run, matching how {@link NotificationService} skips unconfigured channels
     * @throws Exception whatever the transport throws; the caller decides whether that fails the Run
     */
    boolean send(List<String> to, List<String> cc, String subject, String body) throws Exception;

    /**
     * The default binding: the first configured {@code email} {@link NotificationChannel} on the
     * classpath, discovered the same way {@link NotificationService} discovers its own.
     *
     * <p>Discovery happens per call rather than once at boot so a channel that becomes configured later
     * (an operator sets {@code notify.smtp.*} and reloads) is picked up without a restart — the cost is one
     * {@link ServiceLoader} pass per send, which is nothing against an SMTP round-trip.
     */
    static MailAccess overChannels() {
        return (to, cc, subject, body) -> {
            String target = recipients(to, cc);
            if (target.isEmpty()) return false;
            for (NotificationChannel channel : ServiceLoader.load(NotificationChannel.class)) {
                if (!"email".equals(channel.id()) || !channel.configured()) continue;
                channel.deliver(Notification.create("job", "JOB_RUN", null,
                        subject == null ? "" : subject, body == null ? "" : body, null), target);
                return true;
            }
            return false;
        };
    }

    /** The comma-separated address list the channel seam expects; blanks and duplicates dropped. */
    private static String recipients(List<String> to, List<String> cc) {
        return java.util.stream.Stream.concat(
                        to == null ? java.util.stream.Stream.empty() : to.stream(),
                        cc == null ? java.util.stream.Stream.empty() : cc.stream())
                .filter(a -> a != null && !a.isBlank())
                .map(String::trim)
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}
