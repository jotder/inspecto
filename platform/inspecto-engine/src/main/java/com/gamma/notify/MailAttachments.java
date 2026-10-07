package com.gamma.notify;

import com.gamma.config.safety.PathJail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The attachment policy for outgoing mail (ASSURE-XLSX-ATTACHMENTS-1): where the bytes may come from, which
 * types may go, how big, and what the recipient's filename may contain. One home, so the Job that reads an
 * artifact and the channel that encodes it cannot disagree.
 *
 * <ul>
 *   <li><b>Source</b> — {@link #fromRunArtifact}: a file the Job itself just recorded as a Run Artifact, held
 *       to the same {@link PathJail} rule as every job path, including the {@code *.secrets} refusal. There is
 *       deliberately no "attach this path" parameter anywhere: an authored path would turn mail into an
 *       exfiltration channel for any file the server can read.</li>
 *   <li><b>Type</b> — {@link #CONTENT_TYPES}, keyed by extension; anything else is refused.</li>
 *   <li><b>Size</b> — {@code notify.mail.attachment.max.bytes} per file (default 10 MiB) and
 *       {@code notify.mail.message.max.bytes} for the sum (default 20 MiB). Most relays refuse around 25 MB
 *       <em>after</em> base64's third, so the defaults stay under that.</li>
 *   <li><b>Filename</b> — {@link #sanitizeFilename}: no CR/LF (header injection), quotes, or path characters.</li>
 * </ul>
 */
public final class MailAttachments {

    private MailAttachments() {}

    public static final String MAX_ATTACHMENT_PROPERTY = "notify.mail.attachment.max.bytes";
    public static final String MAX_MESSAGE_PROPERTY = "notify.mail.message.max.bytes";
    public static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    public static final long DEFAULT_MAX_MESSAGE_BYTES = 20L * 1024 * 1024;

    /** Extension → MIME type: the report formats {@code ReportJob} renders, and nothing else. */
    public static final Map<String, String> CONTENT_TYPES = Map.of(
            "csv", "text/csv",
            "json", "application/json",
            "pdf", "application/pdf",
            "png", "image/png",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public static long maxAttachmentBytes() { return Long.getLong(MAX_ATTACHMENT_PROPERTY, DEFAULT_MAX_ATTACHMENT_BYTES); }
    public static long maxMessageBytes()    { return Long.getLong(MAX_MESSAGE_PROPERTY, DEFAULT_MAX_MESSAGE_BYTES); }

    /**
     * A filename safe to put in a {@code Content-Disposition} header: every character outside
     * {@code [A-Za-z0-9._-]} (CR, LF, quotes, {@code / \ :}, spaces, control and non-ASCII characters) becomes
     * {@code _}, leading dots are dropped (no hidden or {@code ..} names), and the result is capped at 100
     * characters. Nothing left but {@code _}/{@code .} ⇒ {@code attachment}.
     */
    public static String sanitizeFilename(String raw) {
        String s = raw == null ? "" : raw;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);   // a path's last segment only
        s = s.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        if (s.length() > 100) s = s.substring(s.length() - 100);
        return s.replaceAll("[_.]", "").isEmpty() ? "attachment" : s;
    }

    /** The allowlisted MIME type for {@code filename}'s extension, or throw naming the allowlist. */
    public static String contentTypeOf(String filename) {
        String name = filename == null ? "" : filename;
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = CONTENT_TYPES.get(ext);
        if (type == null)
            throw new IllegalArgumentException("attachment '" + name + "' has type '." + ext
                    + "', which is not on the allowlist " + new java.util.TreeSet<>(CONTENT_TYPES.keySet()));
        return type;
    }

    /**
     * Read a Job's own Run Artifact as an attachment. {@code artifact} is jailed exactly as a job path is
     * ({@link PathJail#requireJobPathUnderAny}: under an allowed root, never through a {@code *.secrets}
     * directory), its type must be allowlisted, and its size is checked BEFORE it is read.
     */
    public static MailAttachment fromRunArtifact(Path artifact, List<Path> roots, Path spaceRoot) throws IOException {
        Path jailed = PathJail.requireJobPathUnderAny(roots, spaceRoot, artifact.toString(), "attachment");
        String name = jailed.getFileName().toString();
        String type = contentTypeOf(name);
        if (!Files.isRegularFile(jailed))
            throw new IllegalArgumentException("attachment '" + name + "' is not a file");
        long size = Files.size(jailed);
        long cap = maxAttachmentBytes();
        if (size > cap)
            throw new IllegalArgumentException("attachment '" + name + "' is " + size + " bytes, over the "
                    + cap + "-byte cap (" + MAX_ATTACHMENT_PROPERTY + ")");
        return new MailAttachment(name, type, Files.readAllBytes(jailed));
    }

    /** The message-level checks: every type allowlisted, each file and the total under their caps. */
    public static void check(List<MailAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return;
        long cap = maxAttachmentBytes();
        long total = 0;
        for (MailAttachment a : attachments) {
            if (!CONTENT_TYPES.containsValue(a.contentType()))
                throw new IllegalArgumentException("attachment '" + a.filename() + "' has content type '"
                        + a.contentType() + "', which is not on the allowlist");
            if (a.size() > cap)
                throw new IllegalArgumentException("attachment '" + a.filename() + "' is " + a.size()
                        + " bytes, over the " + cap + "-byte cap (" + MAX_ATTACHMENT_PROPERTY + ")");
            total += a.size();
        }
        long max = maxMessageBytes();
        if (total > max)
            throw new IllegalArgumentException("attachments total " + total + " bytes, over the " + max
                    + "-byte per-message cap (" + MAX_MESSAGE_PROPERTY + ")");
    }
}
