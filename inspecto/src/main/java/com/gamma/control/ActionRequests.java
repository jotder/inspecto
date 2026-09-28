package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.config.safety.PathJail;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.notify.NotificationTemplate;
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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>Action Requests</b> ({@code ASSURE-ACTION-REQUESTS-1}, WS-24): an outbound API call proposed from an Incident
 * or Case, held for a second person's approval, then sent by {@link ActionDispatcher}. This class is the model and
 * the durable per-Space store; {@link ActionRequestRoutes} is the HTTP surface.
 *
 * <p><b>Lifecycle</b> — {@code draft → pending → approved → dispatched → succeeded | failed}, plus {@code declined}
 * (a checker refused it) and {@code expired} (nobody decided it within the approval policy's
 * {@code expiresAfterHours}). A {@code failed} request can be retried: it goes back to {@code dispatched} with the
 * SAME idempotency key. Every transition is appended to {@code history}.
 *
 * <p><b>What is sent is fixed at creation.</b> The payload template is rendered ONCE, when the request is created
 * ({@link #render}: the {@code {{var}}} facility of {@link NotificationTemplate}, applied to every string leaf of a
 * JSON object, so a value can never break the JSON), and the rendered payload, the Connection id, the URL it
 * resolved to, the method and the idempotency key are what the approver reads and what the dispatcher sends. The
 * approve request carries nothing that can change them.
 *
 * <p><b>Storage</b> — the {@link PendingChanges} pattern exactly: one JSON document per request at
 * {@code <write-root>/action-requests/<id>.json}, atomic temp + move, jailed under the write root, fail closed on
 * an unreadable document. Signed with the SAME per-Space HMAC key as Pending Changes
 * ({@link PendingChanges#domainMac}, domain {@code action-request}), so a record no server wrote — a forged or
 * edited file — reads back {@code status: invalid} and can be neither approved nor dispatched. Not an OperationalDb
 * family, so there is no backup / bundle-staging lockstep to keep: the documents sit in the config tree a Space
 * backup already carries, and the key stays in the {@code .secrets} sibling the backup skips. Not config: nothing
 * reads it as config, and no approval-policy kind governs it — the request carries its own mandatory four-eyes
 * approval, so the policy must not hold it a second time.
 */
public final class ActionRequests {

    private ActionRequests() {}

    static final String DIR = "action-requests";
    static final String DOMAIN = "action-request";
    static final Pattern SAFE_ID = Pattern.compile("ar-\\d{14}-[0-9a-f]{6}");
    /** A caller-chosen idempotency key: a bounded, header-safe token. */
    static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH");
    /** The largest rendered payload a request may carry. */
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    /** The most of a response body a record keeps. */
    static final int EXCERPT_CAP = 1024;
    static final int MAX_REASON = PendingChanges.MAX_REASON;

    static final String DRAFT = "draft", PENDING = "pending", APPROVED = "approved", DISPATCHED = "dispatched",
            SUCCEEDED = "succeeded", FAILED = "failed", DECLINED = "declined", EXPIRED = "expired", INVALID = "invalid";

    private static final String MAC = "mac";
    private static final String INTEGRITY = "integrity";
    private static final Object LOCK = new Object();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    static Object lock() { return LOCK; }

    static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** A fresh record in {@code draft}; the caller moves it to {@code pending} and saves it. */
    static Map<String, Object> draft(String connection, String targetUrl, String method, Map<String, Object> payload,
                                     String idempotencyKey, String incidentId, String caseId, String origin,
                                     String author, String authorType, String reason, int expiresAfterHours) {
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String id = "ar-" + STAMP.format(at) + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24));
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", id);
        rec.put("recordType", DOMAIN);
        rec.put("status", DRAFT);
        rec.put("connection", connection);
        rec.put("targetUrl", targetUrl);
        rec.put("method", method);
        rec.put("payload", payload);
        rec.put("idempotencyKey", idempotencyKey == null ? id : idempotencyKey);
        rec.put("incidentId", incidentId);
        rec.put("caseId", caseId);
        rec.put("origin", origin);
        rec.put("author", author);
        rec.put("authorType", authorType);
        rec.put("reason", reason);
        rec.put("createdAt", at.toString());
        rec.put("expiresAt", at.plus(expiresAfterHours, ChronoUnit.HOURS).toString());
        rec.put("approver", null);
        rec.put("approvedAt", null);
        rec.put("attempts", 0);
        rec.put("lastResponse", null);
        rec.put("history", new ArrayList<>(List.of(step(DRAFT, author, at.toString()))));
        return rec;
    }

    /** Move {@code rec} to {@code status}, appending who and when to its history. */
    @SuppressWarnings("unchecked")
    static void transition(Map<String, Object> rec, String status, String by) {
        rec.put("status", status);
        List<Object> h = rec.get("history") instanceof List<?> l ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        h.add(step(status, by, now()));
        rec.put("history", h);
    }

    private static Map<String, Object> step(String status, String by, String at) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("status", status);
        s.put("by", by);
        s.put("at", at);
        return s;
    }

    /**
     * Render a payload template: every string leaf of the (nested) JSON object through
     * {@link NotificationTemplate#render}. Keys and non-string values pass through untouched.
     */
    @SuppressWarnings("unchecked")
    static Object render(Object template, Map<String, Object> context) {
        if (template instanceof String s) return NotificationTemplate.render(s, context);
        if (template instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), render(v, context)));
            return out;
        }
        if (template instanceof List<?> l) return l.stream().map(v -> render(v, context)).toList();
        return template;
    }

    /** The list view: everything but the payload (the detail view carries it). */
    static Map<String, Object> summary(Map<String, Object> rec) {
        Map<String, Object> s = new LinkedHashMap<>(rec);
        s.remove("payload");
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

    // ── the store ───────────────────────────────────────────────────────────────────────────────

    private static Path dir(Path root) {
        return root.toAbsolutePath().normalize().resolve(DIR);
    }

    private static Path file(Path root, String id) {
        if (id == null || !SAFE_ID.matcher(id).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "action request id must match " + SAFE_ID.pattern());
        Path f = dir(root).resolve(id + ".json");
        if (!PathJail.contains(root.toAbsolutePath().normalize(), f))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "action request path escapes the config root");
        return f;
    }

    /** Persist {@code rec}, signed. ⛔ Never call it on a record that read back invalid — that would sign a forgery. */
    static void save(Path root, Map<String, Object> rec) throws IOException {
        if (invalid(rec)) throw new IllegalStateException("refusing to re-sign an action request that failed its integrity check");
        Path f = file(root, String.valueOf(rec.get("id")));
        Files.createDirectories(f.getParent());
        Map<String, Object> clean = new LinkedHashMap<>(rec);
        clean.remove(MAC);
        Map<String, Object> normal = ApiContext.JSON.readValue(ApiContext.JSON.writeValueAsBytes(clean), MAP);
        normal.put(MAC, PendingChanges.domainMac(root, DOMAIN, normal));
        AtomicFiles.write(f, ApiContext.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(normal), ".ar-");
        rec.put(MAC, normal.get(MAC));
    }

    /**
     * One request, or {@code null}. Fail closed: an unreadable document is an IOException, never absent; a
     * document whose MAC does not verify — or that names another id or record type than its file — comes back
     * {@code status: invalid}, {@code integrity: invalid}: shown, never decidable, never dispatched, never re-saved.
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

    /** Every request of the Space, newest first. */
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

    /** A still-pending request past its {@code expiresAt} becomes {@code expired}. Call under {@link #lock}. */
    static boolean expireIfDue(Path root, Map<String, Object> rec) throws IOException {
        if (invalid(rec) || !PENDING.equals(rec.get("status"))) return false;
        Object at = rec.get("expiresAt");
        if (at == null || Instant.parse(String.valueOf(at)).isAfter(Instant.now())) return false;
        transition(rec, EXPIRED, "system");
        save(root, rec);
        audit("system", "system", "action-request.expired", rec.get("id") + " expired undecided", rec);
        return true;
    }

    /** One AUDIT row. Never the payload, never a token: the id, the Connection, the method and the linkage only. */
    static void audit(String actor, String actorType, String action, String message, Map<String, Object> rec) {
        audit(actor, actorType, action, message, rec, null);
    }

    static void audit(String actor, String actorType, String action, String message, Map<String, Object> rec,
                      com.gamma.event.EventLevel level) {
        try {
            EventLog log = EventLog.current();
            if (log == null) return;
            Event.Builder b = Event.builder(EventType.AUDIT).source("audit").message(message)
                    .actor(actor).actorType(actorType).action(action).actionCategory("operation")
                    .attr("actionRequest", rec.get("id")).attr("connection", rec.get("connection"))
                    .attr("method", rec.get("method")).attr("author", rec.get("author"));
            if (rec.get("incidentId") != null) b.attr("incidentId", rec.get("incidentId"));
            if (rec.get("caseId") != null) b.attr("caseId", rec.get("caseId"));
            if (level != null) b.level(level);
            log.emit(b);
        } catch (RuntimeException ignored) {
            // best effort, like every audit emit — the record is what matters
        }
    }
}
