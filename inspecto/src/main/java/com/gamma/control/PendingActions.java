package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.access.ComponentAccess;
import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.audit.EventType;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.AtomicFiles;
import com.gamma.config.safety.PathJail;
import com.gamma.config.safety.SpaceStateDirs;
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

/**
 * The <b>Pending Action</b> (AGENT-KIT-1 AK-2d, D-AK6, plan {@code 2026-11-20-agent-kit-ak2-plan.md} §6.2 lane B): an
 * operational action a <b>machine</b> agent asked for — a run's trigger, pause, resume, reprocess, drain, … (the
 * {@link AgentGate.Bucket#ACTION} routes) — held for a person to approve. A person's agent takes those actions at once,
 * within the person's rights (D-AK5); only a machine's are held. The Pending Change machinery, under its own term (a
 * Pending Change stays config only):
 * <ul>
 *   <li>the host holds the request <b>before the handler</b> ({@link #holdIfMachineAction}, from
 *       {@code ControlApi.routeDispatch} and {@code ControlApi.replay}) and answers {@code 202} — nothing ran;</li>
 *   <li>a record per held request in {@code pending-actions/}, MAC'd with the Space's Pending Change key under its own
 *       domain ({@link PendingChanges#domainMac}), under the Pending Change store's lock, expiring on the approval
 *       policy's clock;</li>
 *   <li>approval ({@code PendingActionRoutes}) by another person holding {@code canApproveChanges}, four eyes, the
 *       approver roster — operator 2026-11-25 — replays the recorded request as the machine ({@link PendingChanges#authorNow}:
 *       re-admitted by the Space, filtered to what a machine agent keeps), and only when it still names an action route;
 *       the route's own refusal (409) makes the action stale.</li>
 * </ul>
 */
final class PendingActions {

    private PendingActions() {}

    static final String DIR = "pending-actions";
    static final Pattern SAFE_ID = Pattern.compile("pa-\\d{14}-[0-9a-f]{6}");
    /** The request attribute that lets an approved replay through the hold; its value is the record being applied. */
    static final String ATTR_APPROVED_ACTION = "inspecto.approvedAction";
    private static final String DOMAIN = "pending-action";
    private static final String MAC = "mac";
    private static final String INTEGRITY = "integrity";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /**
     * Hold the request when a machine agent calls an {@link AgentGate.Bucket#ACTION} route: record it and throw
     * {@link PendingChanges.Held} (202). Returns for everyone else, and for the approved replay of this very record.
     *
     * @param pattern the matched route's pattern, without {@code ^…$}
     */
    static void holdIfMachineAction(ApiContext api, HttpExchange ex, String method, String pattern) throws IOException {
        Subject s = ApiContext.subject(ex).orElse(null);
        if (s == null || !s.agent() || !s.machine()) return;
        if (AgentGate.bucket(method, pattern) != AgentGate.Bucket.ACTION) return;
        String path = ControlApi.routePath(ex);
        if (ApiContext.attr(ex, ATTR_APPROVED_ACTION) instanceof Map<?, ?> approved) {
            if (approved.get("request") instanceof Map<?, ?> req && method.equals(req.get("method"))
                    && path.equals(withoutQuery(String.valueOf(req.get("path"))))) return;
            throw new ApiException(409, ErrorCodes.CONFLICT, "the approved action is " + approved.get("action")
                    + ", but its replay calls " + method + " " + path + " — not run");
        }
        propose(api, ex, s, method, pattern, path);
    }

    private static void propose(ApiContext api, HttpExchange ex, Subject s, String method, String pattern, String path)
            throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "pending action");
        String reason = ApiContext.attr(ex, com.gamma.spi.auth.RequestAttrs.ATTR_CHANGE_REASON) instanceof String carried
                ? carried : ex.getRequestHeaders().getFirst(PendingChanges.HEADER_REASON);
        if (reason != null && reason.length() > PendingChanges.MAX_REASON)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    PendingChanges.HEADER_REASON + " is at most " + PendingChanges.MAX_REASON + " chars");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", "pa-" + STAMP.format(now) + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24)));
        rec.put("action", method + " " + path);
        rec.put("route", method + " " + pattern);
        rec.put("status", "pending");
        rec.put("author", ApiContext.actor(ex));
        rec.put("authorType", ApiContext.actorType(ex));
        rec.put("authorRoles", ApiContext.attr(ex, ComponentAccess.ATTR_HELD_ROLES) instanceof Set<?> held
                ? held.stream().map(String::valueOf).sorted().toList() : List.of());
        rec.put("authorCapabilities", s.capabilities().stream().sorted().toList());
        rec.put("agentClient", s.agentClient());
        rec.put("authorMachine", s.machineSub());
        rec.put("mcpTool", ApiContext.attr(ex, ApiContext.ATTR_AUDIT_ATTRS) instanceof Map<?, ?> via
                && via.get("mcpTool") instanceof String t ? t : null);
        rec.put("reason", reason == null || reason.isBlank() ? null : reason.trim());
        rec.put("createdAt", now.toString());
        rec.put("expiresAt", now.plus(PendingChanges.expiryHours(root), ChronoUnit.HOURS).toString());
        rec.put("approverCapability", ApprovalPolicy.DEFAULT_APPROVER);
        rec.put("fourEyes", true);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        String query = ex.getRequestURI().getRawQuery();
        request.put("path", path + (query == null || query.isEmpty() ? "" : "?" + query));
        request.put("body", new String(api.rawBody(ex), StandardCharsets.UTF_8));
        rec.put("request", request);

        Files.createDirectories(SpaceStateDirs.writeDir(root, PendingChanges.DIR));   // the shared store lock lives there
        PendingChanges.underStoreLock(root, () -> {
            for (Map<String, Object> other : list(root))
                if ("pending".equals(other.get("status")) && rec.get("action").equals(other.get("action")))
                    throw new ApiException(409, ErrorCodes.CONFLICT, "the same action (" + rec.get("action")
                            + ") is already pending approval (" + other.get("id") + ")");
            save(root, rec);
            return null;
        });
        audit(ex, "pending-action.proposed", rec.get("action") + " held for approval as " + rec.get("id"), rec);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "pending");
        body.put("ran", false);
        body.put("pendingAction", summary(rec));
        throw new PendingChanges.Held(body);
    }

    private static String withoutQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    /** Whether the recorded request still names an {@link AgentGate.Bucket#ACTION} route — checked before any replay. */
    static boolean actionRoute(Map<String, Object> rec) {
        if (!(rec.get("route") instanceof String route) || !(rec.get("request") instanceof Map<?, ?> req)) return false;
        int sp = route.indexOf(' ');
        if (sp < 0) return false;
        String method = route.substring(0, sp), pattern = route.substring(sp + 1);
        return AgentGate.bucket(method, pattern) == AgentGate.Bucket.ACTION && method.equals(req.get("method"))
                && Pattern.compile("^" + pattern + "$").matcher(withoutQuery(String.valueOf(req.get("path")))).matches();
    }

    static Map<String, Object> summary(Map<String, Object> rec) {
        Map<String, Object> s = new LinkedHashMap<>(rec);
        s.remove("request");
        return s;
    }

    // ── the store ───────────────────────────────────────────────────────────────────────────────

    private static Path file(Path root, String id) {
        if (!SAFE_ID.matcher(id).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "pending action id must match " + SAFE_ID.pattern());
        Path f = SpaceStateDirs.writeDir(root, DIR).resolve(id + ".json");
        if (!PathJail.contains(root.toAbsolutePath().normalize(), f))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "pending action path escapes the config root");
        return f;
    }

    static void save(Path root, Map<String, Object> rec) throws IOException {
        Path f = file(root, String.valueOf(rec.get("id")));
        Files.createDirectories(f.getParent());
        Map<String, Object> clean = new LinkedHashMap<>(rec);
        clean.remove(MAC);
        clean.remove(INTEGRITY);
        Map<String, Object> normal = ApiContext.JSON.readValue(ApiContext.JSON.writeValueAsBytes(clean), MAP);
        normal.put(MAC, PendingChanges.domainMac(root, DOMAIN, normal));
        AtomicFiles.write(f, ApiContext.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(normal), ".pa-");
        rec.put(MAC, normal.get(MAC));
    }

    /** One Pending Action, or {@code null}; a record whose MAC does not verify reads as {@code invalid}, never decidable. */
    static Map<String, Object> read(Path root, String id) throws IOException {
        Path f = file(root, id);
        if (!Files.isRegularFile(f)) return null;
        Map<String, Object> rec = ApiContext.JSON.readValue(Files.readAllBytes(f), MAP);
        Object claimed = rec.remove(MAC);
        String expected = PendingChanges.domainMac(root, DOMAIN, rec);
        boolean ok = claimed instanceof String c && java.security.MessageDigest.isEqual(
                c.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        if (claimed != null) rec.put(MAC, claimed);
        if (!ok) {
            rec.put("status", "invalid");
            rec.put(INTEGRITY, "invalid");
        }
        return rec;
    }

    static boolean invalid(Map<String, Object> rec) {
        return "invalid".equals(rec.get(INTEGRITY));
    }

    /** Every Pending Action of the Space, newest first. */
    static List<Map<String, Object>> list(Path root) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        Path d = SpaceStateDirs.writeDir(root, DIR);
        if (!Files.isDirectory(d)) return out;
        try (var files = Files.list(d)) {
            for (Path p : files.toList()) {
                String id = p.getFileName().toString().replaceFirst("\\.json$", "");
                if (!p.getFileName().toString().endsWith(".json") || !SAFE_ID.matcher(id).matches()) continue;
                Map<String, Object> rec = read(root, id);
                if (rec != null) out.add(rec);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("id"))).reversed());
        return out;
    }

    /** A still-pending action past its {@code expiresAt} becomes {@code expired}. */
    static boolean expireIfDue(HttpExchange ex, Path root, Map<String, Object> rec) throws IOException {
        if (invalid(rec) || !"pending".equals(rec.get("status"))) return false;
        Object at = rec.get("expiresAt");
        if (at == null || Instant.parse(String.valueOf(at)).isAfter(Instant.now())) return false;
        rec.put("status", "expired");
        rec.put("decidedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        rec.put("decidedBy", "system");
        save(root, rec);
        audit(ex, "pending-action.expired", rec.get("action") + " — " + rec.get("id") + " expired unapproved", rec);
        return true;
    }

    static void audit(HttpExchange ex, String action, String message, Map<String, Object> rec) {
        audit(ex, action, message, rec, UnaryOperator.identity());
    }

    static void audit(HttpExchange ex, String action, String message, Map<String, Object> rec,
                      UnaryOperator<Event.Builder> more) {
        try {
            Event.Builder b = Event.builder(EventType.AUDIT).source("audit").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("operation")
                    .attr("pendingAction", rec.get("id")).attr("requestedAction", rec.get("action"))
                    .attr("author", rec.get("author"));
            EventLog.current().emit(more.apply(b));
        } catch (RuntimeException ignored) {
            // best effort, like every audit emit on a request path — the record is what matters
        }
    }
}
