package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.config.safety.PathJail;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;

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
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>Maker-checker for human config changes</b> (`ASSURE-MAKER-CHECKER-1`, WS-13): the hold every authoring
 * write reaches just before it writes, and the durable store of the <b>Pending Changes</b> it creates.
 *
 * <p><b>The hold.</b> A route calls {@link #hold} with the kind + name it is about to write, the content it
 * would write ({@code null} for a delete) and the content it would replace ({@code null} for a create), AFTER
 * every validation it runs and BEFORE any side effect. With the Space's {@link ApprovalPolicy} off for that
 * kind the call returns and the route writes exactly as before. With it on, the write becomes a Pending Change
 * and {@link Held} unwinds the request: {@code ControlApi} answers {@code 202} with the Pending Change, and
 * nothing was written.
 *
 * <p><b>The apply.</b> Approving replays the ORIGINAL request through the SAME route in-process
 * ({@link ApiContext#replay}), as the approver, with the Pending Change stamped on the replay
 * ({@link ApiContext#ATTR_APPROVED_CHANGE}). Every gate the route runs runs again; when the replay reaches this
 * hold it lets the write through only if it is the SAME write: same kind and name, the content it replaces
 * still hashes to the base version the author saw (else 409 — stale), and the content it now produces still
 * hashes to what was approved (else 409). So an approver can never apply something other than what they read.
 *
 * <p><b>Storage</b> — one JSON document per Pending Change at {@code <write-root>/pending-changes/<id>.json},
 * the {@code ReconStateStore} pattern: per Space, atomic temp + move, fail closed on an unreadable document,
 * jailed under the write root. Not an OperationalDb family, so there is no backup / bundle-staging lockstep
 * to keep; it sits in the config tree a Space backup already carries. Outside the pipeline config history on
 * purpose: a Pending Change is a proposal, and nothing reads it as config.
 */
public final class PendingChanges {

    private PendingChanges() {}

    static final String DIR = "pending-changes";
    static final Pattern SAFE_ID = Pattern.compile("pc-\\d{14}-[0-9a-f]{6}");
    /** The request headers a replay keeps: a conditional write stays conditional. */
    private static final Set<String> KEPT_HEADERS = Set.of("If-Match");
    /** A header the author may send to say why — kept on the Pending Change and shown to the approver. */
    static final String HEADER_REASON = "X-Change-Reason";
    static final int MAX_REASON = 500;
    private static final Object LOCK = new Object();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /**
     * Thrown by {@link #hold} when the write became a Pending Change: {@code ControlApi.routeDispatch} answers
     * {@code 202} with {@link #body()}. An exception because a hold sits deep in route helpers that return
     * other shapes, and it must unwind every side effect the route would have run after the write.
     */
    public static final class Held extends RuntimeException {
        private final transient Map<String, Object> body;

        Held(Map<String, Object> body) {
            super("held for approval", null, false, false);
            this.body = body;
        }

        public Map<String, Object> body() { return body; }
    }

    // ── the hold ────────────────────────────────────────────────────────────────────────────────

    /**
     * The maker-checker hold. Returns when the write may go ahead; throws {@link Held} when it became a Pending
     * Change; throws {@code 409} when an approved replay is not the write that was approved, or another change
     * to the same target is already pending.
     *
     * @param kind     the policy kind — the config type or component kind
     * @param name     the target's name within its kind
     * @param proposed the content the write would produce; {@code null} for a delete
     * @param current  the content it would replace; {@code null} when the target does not exist yet
     */
    public static void hold(ApiContext api, HttpExchange ex, String kind, String name,
                            Map<String, Object> proposed, Map<String, Object> current) throws IOException {
        if (ApiContext.attr(ex, ApiContext.ATTR_APPROVED_CHANGE) instanceof Map<?, ?> approved) {
            @SuppressWarnings("unchecked") Map<String, Object> pc = (Map<String, Object>) approved;
            verifyApproved(pc, kind, name, proposed, current);
            return;
        }
        Path root = api.writeRoot();
        ApprovalPolicy policy = ApprovalPolicy.forRoot(root);
        ApprovalPolicy.Rule rule = policy.ruleFor(kind);
        if (rule == null) return;

        String reason = ex.getRequestHeaders().getFirst(HEADER_REASON);
        if (reason != null && reason.length() > MAX_REASON)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, HEADER_REASON + " is at most " + MAX_REASON + " chars");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", "pc-" + STAMP.format(now) + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24)));
        rec.put("kind", kind);
        rec.put("name", name);
        rec.put("operation", proposed == null ? "delete" : current == null ? "create" : "update");
        rec.put("status", "pending");
        rec.put("author", ApiContext.actor(ex));
        rec.put("authorType", ApiContext.actorType(ex));
        rec.put("reason", reason == null || reason.isBlank() ? null : reason.trim());
        rec.put("createdAt", now.toString());
        rec.put("expiresAt", now.plus(policy.expiresAfterHours(), ChronoUnit.HOURS).toString());
        rec.put("approverCapability", rule.approverCapability());
        rec.put("fourEyes", rule.fourEyes());
        rec.put("baseVersion", version(current));
        rec.put("proposedVersion", version(proposed));
        rec.put("current", current);
        rec.put("proposed", proposed);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", ex.getRequestMethod());
        String query = ex.getRequestURI().getRawQuery();
        request.put("path", ControlApi.routePath(ex) + (query == null || query.isEmpty() ? "" : "?" + query));
        request.put("body", new String(api.rawBody(ex), StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        for (String h : KEPT_HEADERS) {
            String v = ex.getRequestHeaders().getFirst(h);
            if (v != null) headers.put(h, v);
        }
        request.put("headers", headers);
        rec.put("request", request);

        synchronized (LOCK) {
            for (Map<String, Object> other : list(root))
                if ("pending".equals(other.get("status")) && kind.equals(other.get("kind")) && name.equals(other.get("name")))
                    throw new ApiException(409, ErrorCodes.CONFLICT, "a change to " + kind + " '" + name
                            + "' is already pending approval (" + other.get("id") + ") — it must be approved, declined "
                            + "or expire before another is proposed");
            save(root, rec);
        }
        audit(ex, "pending-change.proposed", kind + " '" + name + "' held for approval as " + rec.get("id"), rec);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "pending");
        body.put("written", false);
        body.put("pendingChange", summary(rec));
        throw new Held(body);
    }

    /** A replay of an approved change reached the hold: let it through only if it is the SAME write. */
    private static void verifyApproved(Map<String, Object> pc, String kind, String name,
                                       Map<String, Object> proposed, Map<String, Object> current) {
        if (!kind.equals(pc.get("kind")) || !name.equals(pc.get("name")))
            throw new ApiException(409, ErrorCodes.CONFLICT, "the approved change is to " + pc.get("kind") + " '"
                    + pc.get("name") + "', but its replay writes " + kind + " '" + name + "' — not applied");
        if (!version(current).equals(pc.get("baseVersion"))) {
            pc.put("outcome", "stale");
            throw new ApiException(409, ErrorCodes.CONFLICT, kind + " '" + name + "' changed after this change was "
                    + "proposed (base " + pc.get("baseVersion") + ", now " + version(current) + ") — not applied; "
                    + "propose it again against the current version");
        }
        if (!version(proposed).equals(pc.get("proposedVersion")))
            throw new ApiException(409, ErrorCodes.CONFLICT, "the change no longer produces the content that was "
                    + "approved — not applied");
        pc.put("outcome", "verified");
    }

    /**
     * Top-level keys that are not authored content: the times a route stamps AT WRITE TIME
     * ({@code createdAt}/{@code updatedAt} — Decision Rules, Expectations, a Notification channel), which differ
     * between a proposal and its replay by construction, and the RESULT stamps an evaluation writes onto the
     * component ({@code lastResult}, {@code lastSimulation}), which a routine run changes while a change waits.
     * The version a Pending Change pins leaves them out; everything else is content.
     */
    private static final Set<String> WRITE_TIME_STAMPS = Set.of("createdAt", "updatedAt", "lastResult", "lastSimulation");

    /** The version a Pending Change pins: the content hash less {@link #WRITE_TIME_STAMPS}; {@code absent} for none. */
    static String version(Map<String, Object> content) {
        if (content == null) return "absent";
        Map<String, Object> c = new LinkedHashMap<>(content);
        WRITE_TIME_STAMPS.forEach(c::remove);
        return ContentHash.of(c);
    }

    /**
     * The author a replayed write acts on behalf of, or {@code null} outside a replay — so provenance a route
     * stamps from the caller (a component's {@code owner}) names the person who PROPOSED the change, and the
     * approved write is byte-for-byte the proposed one.
     */
    static String onBehalfOf(HttpExchange ex) {
        return ApiContext.attr(ex, ApiContext.ATTR_APPROVED_CHANGE) instanceof Map<?, ?> pc && pc.get("author") != null
                ? String.valueOf(pc.get("author")) : null;
    }

    /**
     * For a writer that cannot turn its write into ONE Pending Change — a bulk writer (bundle import, BI
     * template apply) or a write that is a side effect of another act: under a policy for any of {@code kinds}
     * it refuses outright (409, naming {@code why}) rather than writing around the policy.
     */
    public static void holdRefusing(ApiContext api, java.util.Collection<String> kinds, String why) {
        ApprovalPolicy policy = ApprovalPolicy.forRoot(api.writeRoot());
        for (String k : kinds)
            if (policy.ruleFor(k) != null)
                throw new ApiException(409, ErrorCodes.CONFLICT, "this Space's approval policy holds changes to '" + k
                        + "' for approval, and " + why + " — make the change on its own so it can be approved");
    }

    // ── the store ───────────────────────────────────────────────────────────────────────────────

    static Map<String, Object> summary(Map<String, Object> rec) {
        Map<String, Object> s = new LinkedHashMap<>(rec);
        s.remove("current");
        s.remove("proposed");
        s.remove("request");
        return s;
    }

    private static Path dir(Path root) {
        return root.toAbsolutePath().normalize().resolve(DIR);
    }

    private static Path file(Path root, String id) {
        if (!SAFE_ID.matcher(id).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "pending change id must match " + SAFE_ID.pattern());
        Path f = dir(root).resolve(id + ".json");
        if (!PathJail.contains(root.toAbsolutePath().normalize(), f))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "pending change path escapes the config root");
        return f;
    }

    static void save(Path root, Map<String, Object> rec) throws IOException {
        Path f = file(root, String.valueOf(rec.get("id")));
        Files.createDirectories(f.getParent());
        AtomicFiles.write(f, ApiContext.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(rec), ".pc-");
    }

    /** One Pending Change, or {@code null}. Fail closed: an unreadable document is an IOException, never absent. */
    static Map<String, Object> read(Path root, String id) throws IOException {
        Path f = file(root, id);
        if (!Files.isRegularFile(f)) return null;
        return ApiContext.JSON.readValue(Files.readAllBytes(f), new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /** Every Pending Change of the Space, newest first. */
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

    /** Record expiry: a still-pending change past its {@code expiresAt} becomes {@code expired}. */
    static boolean expireIfDue(HttpExchange ex, Path root, Map<String, Object> rec) throws IOException {
        if (!"pending".equals(rec.get("status"))) return false;
        Object at = rec.get("expiresAt");
        if (at == null || Instant.parse(String.valueOf(at)).isAfter(Instant.now())) return false;
        rec.put("status", "expired");
        rec.put("decidedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        rec.put("decidedBy", "system");
        save(root, rec);
        audit(ex, "pending-change.expired", rec.get("kind") + " '" + rec.get("name") + "' — " + rec.get("id")
                + " expired unapproved", rec);
        return true;
    }

    static Object lock() { return LOCK; }

    static void audit(HttpExchange ex, String action, String message, Map<String, Object> rec) {
        audit(ex, action, message, rec, UnaryOperator.identity());
    }

    static void audit(HttpExchange ex, String action, String message, Map<String, Object> rec,
                      UnaryOperator<Event.Builder> more) {
        try {
            Event.Builder b = Event.builder(EventType.AUDIT).source("audit").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("configuration")
                    .attr("pendingChange", rec.get("id")).attr("kind", rec.get("kind")).attr("name", rec.get("name"))
                    .attr("author", rec.get("author"));
            EventLog.current().emit(more.apply(b));
        } catch (RuntimeException ignored) {
            // best effort, like every audit emit on a request path — the Pending Change record is what matters
        }
    }
}
