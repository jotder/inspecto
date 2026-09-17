package com.gamma.notify.channel;

// ⚠ Relocated from com.gamma.connect.notify (inspecto-connectors) into this module on 2026-09-07,
// EDG-01 cell 1. The package moved with it deliberately: leaving it in com.gamma.connect.notify would
// SPLIT that package across two jars, because DeliveryIds and the two DeliveryStatusAdapters stay in
// inspecto-connectors. Not a @PublicApi type, and the only reference to its old name was one doc path.

import com.gamma.notify.Notification;
import com.gamma.notify.NotificationChannel;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Date;
import java.util.Properties;
import static com.gamma.util.Values.trimToNull;

/**
 * SMTP email delivery channel — sends each notification as a plain-text mail. Lives in the connectors
 * module (network dependencies stay out of the lean core, the PKG-2 SBOM rule) and is discovered via
 * {@link java.util.ServiceLoader} when the connectors jar is on the classpath; it is inert until
 * configured.
 *
 * <p>Configuration (system properties, the engine's config idiom for operational backends):
 * <ul>
 *   <li>{@code notify.smtp.host} + {@code notify.smtp.to} — both required; either unset ⇒ the channel is
 *       {@linkplain #configured() not configured} and never invoked.</li>
 *   <li>{@code notify.smtp.port} — default {@code 25}.</li>
 *   <li>{@code notify.smtp.from} — default {@code inspecto@<host>}.</li>
 *   <li>{@code notify.smtp.user} / {@code notify.smtp.pass} — optional; both set ⇒ SMTP AUTH.</li>
 *   <li>{@code notify.smtp.starttls} — {@code true} to negotiate STARTTLS (default {@code false}).</li>
 * </ul>
 *
 * <p><b>TLS server-identity verification is always on and is not configurable.</b> Whenever the
 * connection becomes a TLS session, {@code mail.smtp.ssl.checkserveridentity} is set, so the certificate
 * the server presents must actually name {@code notify.smtp.host}. javax.mail 1.6.2 defaults that to
 * {@code false}, which left a chain-valid certificate for <em>any other</em> name good enough to
 * intercept the SMTP AUTH credentials. ⚠ This is a behaviour change: an install whose relay presents a
 * certificate that does not match the configured host name — typically one reached by IP, or by a CNAME
 * the certificate does not carry — starts failing to deliver mail instead of silently trusting it. The
 * fix is to configure {@code notify.smtp.host} as the name on the certificate, or to trust the issuing
 * CA via {@code -Djavax.net.ssl.trustStore}; there is deliberately no switch that turns verification off.
 *
 * <p><b>{@code starttls} is required, not opportunistic, once enabled.</b> Setting {@code notify.smtp.starttls}
 * also sets {@code mail.smtp.starttls.required=true} (not merely {@code .enable}), so a relay that simply
 * declines to advertise {@code STARTTLS} fails the send loudly instead of silently falling back to a
 * cleartext session in which the identity check above never runs and SMTP AUTH credentials go out in the
 * clear. ⚠ <b>Behaviour change (closes {@code NOTIFY-SMTP-STARTTLS-OPPORTUNISTIC-1}):</b> an install that set
 * {@code notify.smtp.starttls=true} against a relay which never actually offered {@code STARTTLS} — and so
 * has been silently sending plaintext mail and cleartext credentials — now fails to deliver instead. That is
 * the intended outcome: a loud failure beats a silent weakness. There is no escape hatch to restore the
 * opportunistic behaviour; the fix on the operator's side is to point at a relay that offers {@code STARTTLS},
 * or to stop setting {@code notify.smtp.starttls}.
 *
 * <p>{@link #deliver(Notification, String)} sends to an explicit {@code target} address (a persisted
 * {@link com.gamma.notify.ChannelConfig} destination), falling back to {@code notify.smtp.to} when
 * blank; {@link #deliver(Notification)} always uses the fixed {@code notify.smtp.to}.
 *
 * <p>Failures throw and are logged + isolated per notification by
 * {@link com.gamma.notify.NotificationService}; there is no retry — notifications are best-effort and
 * the in-app feed remains the durable record.
 *
 * @since 4.0.0
 */
public final class SmtpEmailChannel implements NotificationChannel {

    /** Preference-grid channel id — matches {@code NotificationPreferences.EMAIL}. */
    public static final String ID = "email";

    private final String host;
    private final int port;
    private final String from;
    private final String to;
    private final String user;
    private final String pass;
    private final boolean starttls;

    /** ServiceLoader constructor: reads {@code notify.smtp.*} system properties. */
    public SmtpEmailChannel() {
        this(System.getProperty("notify.smtp.host"),
             Integer.getInteger("notify.smtp.port", 25),
             System.getProperty("notify.smtp.from"),
             System.getProperty("notify.smtp.to"),
             System.getProperty("notify.smtp.user"),
             System.getProperty("notify.smtp.pass"),
             Boolean.getBoolean("notify.smtp.starttls"));
    }

    SmtpEmailChannel(String host, int port, String from, String to, String user, String pass, boolean starttls) {
        this.host = trimToNull(host);
        this.port = port;
        this.from = trimToNull(from) != null ? from.trim() : "inspecto@" + (this.host == null ? "localhost" : this.host);
        this.to = trimToNull(to);
        this.user = trimToNull(user);
        this.pass = trimToNull(pass);
        this.starttls = starttls;
    }

    /**
     * A {@link MimeMessage} that keeps a {@code Message-ID} we set ourselves (D8 correlation).
     *
     * <p>Necessary, not decorative: {@code Transport.send} calls {@code saveChanges()}, which calls
     * {@code updateMessageID()} and would replace our id with a generated one — silently breaking the
     * round trip while the mail still sends. With no explicit header set, behaviour is unchanged.
     */
    private static final class PreservedMessageIdMessage extends MimeMessage {
        PreservedMessageIdMessage(Session session) { super(session); }

        @Override
        protected void updateMessageID() throws javax.mail.MessagingException {
            if (getHeader("Message-ID") == null) super.updateMessageID();
        }
    }

    @Override public String id() { return ID; }

    @Override public boolean configured() { return host != null && to != null; }

    @Override
    public void deliver(Notification n) throws Exception {
        Transport.send(message(n));
    }

    /**
     * Deliver to an explicit {@code target} address (or comma-separated list) — the seam a persisted
     * {@link com.gamma.notify.ChannelConfig} destination is delivered through, so one SMTP transport
     * serves several operator-managed recipients instead of the single fixed {@code notify.smtp.to}.
     */
    /** Deliver to {@code target}, embedding {@code deliveryId} as our own {@code Message-ID} (D8). */
    @Override
    public void deliver(Notification n, String target, String deliveryId) throws Exception {
        Transport.send(message(n, target, deliveryId));
    }

    @Override
    public void deliver(Notification n, String target) throws Exception {
        Transport.send(message(n, target));
    }

    /** Builds the outgoing mail — separated so tests can assert the message without a live SMTP server. */
    MimeMessage message(Notification n) throws Exception {
        return message(n, to);
    }

    /** As {@link #message(Notification)}, but addressed to an explicit {@code target} (blank ⇒ fixed {@code to}). */
    MimeMessage message(Notification n, String target) throws Exception {
        return message(n, target, null);
    }

    /** As {@link #message(Notification, String)} plus a D8 correlation id stamped into {@code Message-ID}. */
    MimeMessage message(Notification n, String target, String deliveryId) throws Exception {
        MimeMessage msg = buildMessage(n, target != null && !target.isBlank() ? target : to);
        if (deliveryId != null && !deliveryId.isBlank()) {
            // We mint the Message-ID so bounce/complaint callbacks echo it back to us (D8 §2). It must be
            // set AFTER buildMessage and survive the send: JavaMail generates its own during
            // saveChanges()/send() otherwise, and saveChanges() would overwrite ours — hence the
            // updateMessageID() override on the subclass below.
            msg.setHeader("Message-ID", "<inspecto." + deliveryId + "@" + messageIdDomain() + ">");
        }
        return msg;
    }

    /** The right-hand side of our {@code Message-ID}, derived from the sender address. */
    private String messageIdDomain() {
        int at = from.indexOf('@');
        return at >= 0 && at < from.length() - 1 ? from.substring(at + 1) : "inspecto.local";
    }

    private MimeMessage buildMessage(Notification n, String recipients) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        // ⚠ UNCONDITIONAL, and it is the AUTH credentials that depend on it (NOTIFY-SMTP-TLS-VERIFY-1).
        // com.sun.mail:javax.mail 1.6.2 defaults this to false — SocketFetcher.configureSSLSocket() reads
        // `PropUtil.getBooleanProperty(props, prefix + ".ssl.checkserveridentity", false)` — so the stock
        // SSLSocketFactory validates the certificate CHAIN but nobody ever checks that the name on it is
        // the host we asked for. Any party who can answer for `notify.smtp.host` while holding a
        // CA-valid certificate for a name they legitimately own terminates our STARTTLS session and reads
        // the SMTP AUTH user/pass in the clear. Set here rather than under `if (starttls)` so it is not a
        // knob that has to be remembered again the day an implicit-SSL mode is added; javax.mail ignores
        // it when the socket never becomes an SSLSocket. ⛔ Deliberately no trust-all / skip-verify
        // escape hatch: an internal-CA deployment is served by the JVM trust store
        // (-Djavax.net.ssl.trustStore), which keeps authentication ON instead of turning it off.
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        if (starttls) {
            props.put("mail.smtp.starttls.enable", "true");
            // NOTIFY-SMTP-STARTTLS-OPPORTUNISTIC-1: .enable alone is opportunistic — a relay that declines
            // to advertise STARTTLS still gets a plaintext session, so the identity check above never runs
            // and SMTP AUTH credentials go out in the clear. .required makes the send fail instead.
            props.put("mail.smtp.starttls.required", "true");
        }
        Session session;
        if (user != null && pass != null) {
            props.put("mail.smtp.auth", "true");
            session = Session.getInstance(props, new javax.mail.Authenticator() {
                @Override protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new javax.mail.PasswordAuthentication(user, pass);
                }
            });
        } else {
            session = Session.getInstance(props);
        }
        MimeMessage msg = new PreservedMessageIdMessage(session);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipients));
        msg.setSubject("[Inspecto] " + n.title());
        msg.setText(n.body() + "\n\ncategory: " + n.category()
                + "\nsource: " + n.sourceType() + (n.sourceId() == null ? "" : " (" + n.sourceId() + ")")
                + "\nat: " + n.timestamp());
        msg.setSentDate(new Date(n.ts()));
        return msg;
    }
}
