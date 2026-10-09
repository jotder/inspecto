package com.gamma.regreporting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.config.safety.PathJail;
import com.gamma.control.PendingChanges;
import com.gamma.event.EventLog;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>Regulatory Reports</b> ({@code REGULATORY-REPORTING-1}): the model and the durable per-Space store. A report is
 * rendered ONCE, at draft, from a {@link ReportTemplate} over a Case or Incident; its content, SHA-256 and resolved
 * drop directory are fixed on the record from then on, so the approver reads exactly what is submitted.
 *
 * <p><b>Lifecycle</b> — {@code draft → pending → approved → submitted | failed}, plus {@code declined} (the checker
 * refused it), {@code expired} (undecided before {@code expiresAt}) and {@code invalid} (its MAC does not verify).
 * A {@code failed} submission can be retried with the same bytes and file name. {@code rejected} is reserved for a
 * regulator's refusal, which nothing records yet. Every transition is appended to {@code history}.
 *
 * <p><b>Storage</b> — the Action Request pattern exactly: one JSON document per report at
 * {@code <write-root>/regulatory-reports/<id>.json}, atomic temp + move, jailed, fail closed on an unreadable document,
 * signed with the Space's Pending Change key under its own domain ({@link PendingChanges#domainMac}, domain
 * {@code regulatory-report}). A record no server wrote reads back {@code status: invalid}: shown, never decided,
 * never submitted, never re-saved.
 */
public final class RegulatoryReports {

    private RegulatoryReports() {}

    static final String DIR = "regulatory-reports";
    static final String DOMAIN = "regulatory-report";
    static final Pattern SAFE_ID = Pattern.compile("rr-\\d{14}-[0-9a-f]{6}");
    static final int MAX_REASON = PendingChanges.MAX_REASON;

    static final String DRAFT = "draft", PENDING = "pending", APPROVED = "approved", SUBMITTED = "submitted",
            FAILED = "failed", DECLINED = "declined", EXPIRED = "expired", INVALID = "invalid";

    private static final String MAC = "mac";
    private static final String INTEGRITY = "integrity";
    private static final Object LOCK = new Object();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    static Object lock() { return LOCK; }

    static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    static String newId() {
        return "rr-" + STAMP.format(Instant.now()) + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24));
    }

    /** Move {@code rec} to {@code status}, appending who and when to its history. */
    @SuppressWarnings("unchecked")
    static void transition(Map<String, Object> rec, String status, String by) {
        rec.put("status", status);
        List<Object> h = rec.get("history") instanceof List<?> l ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("status", status);
        s.put("by", by);
        s.put("at", now());
        h.add(s);
        rec.put("history", h);
    }

    /** Every person who made the report: the author and whoever requested its approval. Four-eyes excludes them all. */
    static List<Object> makers(Map<String, Object> rec) {
        List<Object> m = new ArrayList<>();
        m.add(rec.get("author"));
        if (rec.get("requestedBy") != null && !m.contains(rec.get("requestedBy"))) m.add(rec.get("requestedBy"));
        return m;
    }

    /** The list view: everything but the content (the detail view carries it). */
    static Map<String, Object> summary(Map<String, Object> rec) {
        Map<String, Object> s = new LinkedHashMap<>(rec);
        s.remove("content");
        s.remove("inputs");
        s.remove(MAC);
        return s;
    }

    /** The detail view: the whole record less its MAC. */
    static Map<String, Object> detail(Map<String, Object> rec) {
        Map<String, Object> s = new LinkedHashMap<>(rec);
        s.remove(MAC);
        return s;
    }

    static boolean invalid(Map<String, Object> rec) {
        return INVALID.equals(rec.get(INTEGRITY));
    }

    // ── the store ────────────────────────────────────────────────────────────────────────────────

    private static Path dir(Path root) {
        return root.toAbsolutePath().normalize().resolve(DIR);
    }

    private static Path file(Path root, String id) {
        if (id == null || !SAFE_ID.matcher(id).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "regulatory report id must match " + SAFE_ID.pattern());
        Path f = dir(root).resolve(id + ".json");
        if (!PathJail.contains(root.toAbsolutePath().normalize(), f))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "regulatory report path escapes the config root");
        return f;
    }

    /** Persist {@code rec}, signed. ⛔ Never on a record that read back invalid — that would sign a forgery. */
    static void save(Path root, Map<String, Object> rec) throws IOException {
        if (invalid(rec)) throw new IllegalStateException("refusing to re-sign a regulatory report that failed its integrity check");
        Path f = file(root, String.valueOf(rec.get("id")));
        Files.createDirectories(f.getParent());
        Map<String, Object> clean = new LinkedHashMap<>(rec);
        clean.remove(MAC);
        Map<String, Object> normal = ApiContext.JSON.readValue(ApiContext.JSON.writeValueAsBytes(clean), MAP);
        normal.put(MAC, PendingChanges.domainMac(root, DOMAIN, normal));
        AtomicFiles.write(f, ApiContext.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(normal), ".rr-");
        rec.put(MAC, normal.get(MAC));
    }

    /**
     * One report, or {@code null}. Fail closed: an unreadable document is an IOException; one whose MAC does not
     * verify, or that names another id or record type than its file, comes back {@code status: invalid}.
     */
    static Map<String, Object> read(Path root, String id) throws IOException {
        Path f = file(root, id);
        if (!Files.isRegularFile(f)) return null;
        Map<String, Object> rec = ApiContext.JSON.readValue(Files.readAllBytes(f), MAP);
        Object claimed = rec.remove(MAC);
        String expected = PendingChanges.domainMac(root, DOMAIN, rec);
        boolean ok = claimed instanceof String c && java.security.MessageDigest.isEqual(
                c.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
                && id.equals(rec.get("id")) && DOMAIN.equals(rec.get("recordType"));
        if (claimed != null) rec.put(MAC, claimed);
        if (!ok) {
            rec.put("id", id);
            rec.put("status", INVALID);
            rec.put(INTEGRITY, INVALID);
        }
        return rec;
    }

    /** Every report of the Space, newest first. */
    static List<Map<String, Object>> list(Path root) throws IOException {
        Path d = dir(root);
        if (!Files.isDirectory(d)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String id = p.getFileName().toString().replaceFirst("\\.json$", "");
                if (!SAFE_ID.matcher(id).matches()) continue;
                Map<String, Object> rec = read(root, id);
                if (rec != null) out.add(rec);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("id"))).reversed());
        return out;
    }

    /** A draft or pending report past its {@code expiresAt} becomes {@code expired}. Call under {@link #lock}. */
    static boolean expireIfDue(Path root, Map<String, Object> rec) throws IOException {
        if (invalid(rec) || !(PENDING.equals(rec.get("status")) || DRAFT.equals(rec.get("status")))) return false;
        Object at = rec.get("expiresAt");
        if (at == null || Instant.parse(String.valueOf(at)).isAfter(Instant.now())) return false;
        transition(rec, EXPIRED, "system");
        save(root, rec);
        audit("system", "system", "regulatory-report.expired", rec.get("id") + " expired undecided", rec, null);
        return true;
    }

    /**
     * One AUDIT row — hash-chained by the event log. Never the content or the inputs (a SAR is confidential): the id,
     * the template, the subject, the content hash and, once submitted, the file name.
     */
    static void audit(String actor, String actorType, String action, String message, Map<String, Object> rec,
                      com.gamma.audit.EventLevel level) {
        try {
            EventLog log = EventLog.current();
            if (log == null) return;
            Event.Builder b = Event.builder(EventType.AUDIT).source("audit").message(message)
                    .actor(actor).actorType(actorType).action(action).actionCategory("operation")
                    .attr("regulatoryReport", rec.get("id")).attr("template", rec.get("template"))
                    .attr("author", rec.get("author"));
            if (rec.get("contentSha256") != null) b.attr("contentSha256", rec.get("contentSha256"));
            if (rec.get("incidentId") != null) b.attr("incidentId", rec.get("incidentId"));
            if (rec.get("caseId") != null) b.attr("caseId", rec.get("caseId"));
            if (rec.get("submission") instanceof Map<?, ?> s && s.get("file") != null) b.attr("file", s.get("file"));
            if (level != null) b.level(level);
            log.emit(b);
        } catch (RuntimeException ignored) {
            // best effort, like every audit emit — the record is what matters
        }
    }
}
