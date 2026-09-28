package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.config.safety.PathJail;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * jailed under the write root, every read-check-write under {@link #underStoreLock} (a cross-process file lock). Not an OperationalDb family, so there is no backup / bundle-staging lockstep
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
        // D-P13: the AUTHOR applies an approved change, re-checked at apply time — so record who they are in
        // this Space's role table: their recognised roles, re-resolved against the table as it is THEN. (Their
        // capabilities now are recorded for the reviewer only; apply never trusts them.)
        Subject author = ApiContext.subject(ex).orElse(null);
        rec.put("authorRoles", ApiContext.attr(ex, ComponentAccess.ATTR_HELD_ROLES) instanceof Set<?> held
                ? held.stream().map(String::valueOf).sorted().toList() : List.of());
        rec.put("authorCapabilities", author == null ? List.of() : author.capabilities().stream().sorted().toList());
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

        Files.createDirectories(dir(root));
        underStoreLock(root, () -> {
            for (Map<String, Object> other : list(root))
                if ("pending".equals(other.get("status")) && kind.equals(other.get("kind")) && name.equals(other.get("name")))
                    throw new ApiException(409, ErrorCodes.CONFLICT, "a change to " + kind + " '" + name
                            + "' is already pending approval (" + other.get("id") + ") — it must be approved, declined, "
                            + "withdrawn or expire before another is proposed");
            save(root, rec);
            return null;
        });
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
     * The AUTHOR of {@code rec} as a Subject whose capabilities are as they are NOW (D-P13, verification
     * finding 3): the approved write is the author's act, so the route re-checks the AUTHOR's authority at
     * apply time, and a checker need not be a builder. With recorded roles (an Authenticator that stamps them —
     * the OIDC one does) the capabilities are re-resolved against {@code configRoot}'s role table and Access
     * Profiles as they stand now, exactly as {@code OidcAuthenticator} resolves them — capabilities and data
     * scopes both. ⚠ Role MEMBERSHIP is the IdP's view as of the proposal: a server cannot re-ask the IdP about a
     * user who is not in the request. 🔴 FAIL CLOSED (re-verification finding 3): with no recorded roles (an
     * Authenticator that stamps none) the author's authority cannot be re-checked, so the apply is refused
     * (403) — a propose-time capability snapshot is never trusted.
     */
    @SuppressWarnings("unchecked")
    static Subject authorNow(Map<String, Object> rec, Path configRoot) {
        List<String> roles = rec.get("authorRoles") instanceof List<?> l ? (List<String>) l : List.of();
        if (roles.isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "the author's roles are unknown (the "
                    + "Authenticator recorded none when '" + rec.get("author") + "' proposed this change), so their "
                    + "authority cannot be re-checked at apply time — not applied");
        Map<String, Roles.Def> defs = Roles.effective(configRoot);
        java.util.Set<String> caps = new java.util.TreeSet<>();
        java.util.Set<String> scopes = new java.util.TreeSet<>();
        boolean unscoped = false;
        for (String r : roles) {
            Roles.Def d = defs.get(r);
            if (d == null) continue;
            caps.addAll(d.capabilities());
            if (d.dataScopes() == null) unscoped = true;
            else scopes.addAll(d.dataScopes());
        }
        caps.removeAll(AccessGrants.deniedCapabilities(configRoot, roles));
        return new Subject(String.valueOf(rec.get("author")), caps, unscoped ? null : scopes);
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

    /**
     * Whether the Space whose config root is {@code configRoot} holds changes to {@code kind} for approval —
     * for a writer outside any request (the agent's fix drafts), which has no exchange to hold and must simply
     * not write a governed component.
     */
    public static boolean governs(Path configRoot, String kind) {
        return ApprovalPolicy.forRoot(configRoot).ruleFor(kind) != null;
    }

    /**
     * {@link #holdRefusing} for a writer that lands raw config files (the Space bundle import): each
     * config-relative path is classified to its kind ({@link #kindOfConfigPath}); under a policy holding any
     * kind, a path that cannot be classified is refused too — it could be a governed config.
     */
    public static void holdRefusingPaths(ApiContext api, java.util.Collection<String> relPaths, String why) {
        ApprovalPolicy policy = ApprovalPolicy.forRoot(api.writeRoot());
        if (!policy.holdsAnything()) return;
        List<String> governed = new ArrayList<>();
        for (String p : relPaths) {
            String kind = kindOfConfigPath(p);
            if (kind == null || policy.ruleFor(kind) != null)
                governed.add(p + " (" + (kind == null ? "unclassified" : kind) + ")");
        }
        if (!governed.isEmpty())
            throw new ApiException(409, ErrorCodes.CONFLICT, "this Space's approval policy holds changes for approval, and "
                    + why + " — it would write " + governed + " without one; make each change on its own so it can be approved");
    }

    /**
     * The policy kind a config-relative file is, or {@code null} when it cannot be told: a registry component
     * by its type directory, else by the house filename conventions ({@code _pipeline.toon}, {@code _enrich.toon},
     * {@code _job.toon} / {@code jobs/}, a schema's {@code _mapping.csv} / {@code _structure.csv}). A bare
     * {@code <name>.toon} (how a schema or meta config is written) is NOT classified — it is ambiguous.
     */
    static String kindOfConfigPath(String relPath) {
        String p = relPath.replace('\\', '/');
        String[] parts = p.split("/");
        if (parts.length >= 3 && "registry".equals(parts[0])) {
            for (String type : com.gamma.pipeline.ComponentStore.WRITABLE_TYPES)
                if (com.gamma.pipeline.ComponentRegistry.dirForType(type).map(parts[1]::equals).orElse(false)) return type;
            return "connections".equals(parts[1]) ? "connection" : null;
        }
        String file = parts[parts.length - 1];
        if (file.endsWith("_pipeline.toon")) return "pipeline";
        if (file.endsWith("_enrich.toon")) return "enrichment";
        if (file.endsWith("_job.toon") || (parts.length >= 2 && "jobs".equals(parts[0]))) return "job";
        if (file.endsWith("_mapping.csv") || file.endsWith("_structure.csv")) return "schema";
        return null;
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

    /**
     * Persist {@code rec} with its MAC (re-verification finding 2 (ii)): HMAC-SHA256 over the record's canonical
     * JSON, keyed by {@link #key} — so a record no server wrote (a forged or edited file) is detected on read.
     */
    static void save(Path root, Map<String, Object> rec) throws IOException {
        Path f = file(root, String.valueOf(rec.get("id")));
        Files.createDirectories(f.getParent());
        Map<String, Object> clean = new LinkedHashMap<>(rec);
        clean.remove(MAC);
        clean.remove(INTEGRITY);
        Map<String, Object> normal = ApiContext.JSON.readValue(ApiContext.JSON.writeValueAsBytes(clean), MAP);
        normal.put(MAC, mac(root, normal));
        AtomicFiles.write(f, ApiContext.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(normal), ".pc-");
        rec.put(MAC, normal.get(MAC));
    }

    /**
     * One Pending Change, or {@code null}. Fail closed: an unreadable document is an IOException, never absent;
     * and a document whose MAC does not verify comes back with {@code status: invalid} and
     * {@code integrity: invalid} — shown, never decidable, never re-saved (that would sign a forgery).
     */
    static Map<String, Object> read(Path root, String id) throws IOException {
        Path f = file(root, id);
        if (!Files.isRegularFile(f)) return null;
        Map<String, Object> rec = ApiContext.JSON.readValue(Files.readAllBytes(f), MAP);
        Object claimed = rec.remove(MAC);
        String expected = mac(root, rec);
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

    private static final String MAC = "mac";
    private static final String INTEGRITY = "integrity";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    static final String KEY_FILE = ".pending-changes.key";

    /** HMAC-SHA256 (hex) over the canonical JSON of {@code rec} (its MAC excluded). */
    private static String mac(Path root, Map<String, Object> rec) throws IOException {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(key(root), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(m.doFinal(ContentHash.canonicalJson(rec).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * HMAC-SHA256 (hex) over {@code domain + "\n" + canonical JSON of rec}, keyed by the SAME per-Space key
     * ({@link #key}) — for another record type that must be tamper-evident (Action Requests,
     * {@code ASSURE-ACTION-REQUESTS-1}) without a second key. The domain prefix separates the types: a Pending
     * Change's MAC input starts with <code>{</code>, so no record of another domain can ever verify as one, nor
     * one as it.
     */
    static String domainMac(Path root, String domain, Map<String, Object> rec) throws IOException {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(key(root), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(m.doFinal((domain + "\n" + ContentHash.canonicalJson(rec))
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * Where the Space's Pending Change key lives (round-3 verification finding 2): OUTSIDE the config tree, in the
     * sibling directory {@code <config root>.secrets/} — for a hosted Space {@code <space>/config.secrets/}, for the
     * default Space {@code <assist.write.root>.secrets/}. Outside every tree that is exported ({@code /export}
     * walks the config tree only), imported (an import writes only under the config root) or shared (the Exchange
     * lives under {@code <spaces-root>/_shared/}); {@code BackupTask} skips any {@code *.secrets} directory and the
     * key file by name; {@code .gitignore} ignores it under {@code spaces/}.
     */
    static Path keyFile(Path root) {
        return com.gamma.util.SpaceSecretKeys.keyFile(root, KEY_FILE);
    }

    /** The suffix of the per-config-root secrets directory — {@code BackupTask} skips any directory ending in it. */
    public static final String SECRETS_SUFFIX = com.gamma.util.SpaceSecretKeys.SECRETS_SUFFIX;

    /**
     * The Space's Pending Change key: 32 random bytes, created on first use ({@link #keyFile}). Created with
     * {@code CREATE_NEW}, so exactly one writer ever creates it — a racing second writer gets
     * {@code FileAlreadyExistsException} and reads the first writer's key; no writer ever replaces it (a replace
     * would invalidate every record the first key signed). Owner-only where the platform allows it (POSIX
     * {@code rw-------}; on Windows an owner-only ACL). A reader that meets a file still being written retries
     * briefly until it holds a whole key. Never served, exported, imported or backed up. ⚠ The MAC defends
     * against a record written through any door that cannot READ the key — an import, a forged upload — not
     * against a local administrator, who can read both.
     */
    static byte[] key(Path root) throws IOException {
        // The creation rule (CREATE_NEW first-writer-wins, owner-only, read-retry) is shared: SpaceSecretKeys.
        return com.gamma.util.SpaceSecretKeys.readOrCreate(keyFile(root), "Pending Change key");
    }

    /**
     * The routes a Pending Change may be REPLAYED through (re-verification finding 2 (i)): exactly the routes
     * whose handler reaches {@link #hold} before it writes — {@code ConfigWriteFunnelTest} pins this table to the
     * inventory it scans. Approve refuses (409, nothing dispatched) a recorded request outside it, so a record
     * naming any other route — {@code PUT /access/roles}, say — can never be replayed, whoever wrote it.
     * Keyed {@code "METHOD pattern"} with the route-table pattern.
     */
    static final List<String> REPLAYABLE = List.of(
            "POST /config/write", "POST /config/patch", "DELETE /config/([^/]+)/([^/]+)",
            "PUT /pipelines/([^/]+)/graph", "POST /pipelines/([^/]+)/history/([^/]+)/restore",
            "POST /pipelines/([^/]+)/label", "POST /pipelines/([^/]+)/settings",
            "POST /pipelines/([^/]+)/save-as-template", "POST /pipelines/([^/]+)/rename",
            "POST /components/([^/]+)", "PUT /components/([^/]+)/([^/]+)", "DELETE /components/([^/]+)/([^/]+)",
            "POST /components/([^/]+)/([^/]+)/versions/([^/]+)/restore",
            "POST /components/findings-spec", "PUT /components/findings-spec/([^/]+)",
            "DELETE /components/findings-spec/([^/]+)", "POST /components/findings-spec/([^/]+)/versions/([^/]+)/restore",
            "POST /alerts/rules", "PUT /alerts/rules/([^/]+)", "DELETE /alerts/rules/([^/]+)",
            "POST /decision-rules", "PUT /decision-rules/([^/]+)", "DELETE /decision-rules/([^/]+)",
            "POST /expectations", "PUT /expectations/([^/]+)", "DELETE /expectations/([^/]+)",
            "PUT /access/catalog", "PUT /access/profiles/([^/]+)", "DELETE /access/profiles/([^/]+)",
            "POST /requirements/([^/]+)/kpi",
            "POST /jobs", "PUT /jobs/([^/]+)", "DELETE /jobs/([^/]+)", "POST /jobs/([^/]+)/enable",
            "POST /jobs/([^/]+)/disable", "POST /jobs/([^/]+)/reschedule");

    /** Whether {@code method path} (route-table path, query allowed) is on {@link #REPLAYABLE}. */
    static boolean replayable(String method, String path) {
        String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        for (String entry : REPLAYABLE) {
            int sp = entry.indexOf(' ');
            if (entry.substring(0, sp).equals(method)
                    && Pattern.compile("^" + entry.substring(sp + 1) + "$").matcher(route).matches()) return true;
        }
        return false;
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
        if (invalid(rec) || !"pending".equals(rec.get("status"))) return false;
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

    /** Work done while holding the store lock ({@link #underStoreLock}). */
    @FunctionalInterface
    interface StoreAction<T, E extends Exception> {
        T run() throws E;
    }

    /** The per-store lock file, beside the records (not {@code .json}, so {@link #list} never reads it). */
    static final String LOCK_FILE = ".lock";

    /**
     * Run {@code action} holding the Space's Pending Change store EXCLUSIVELY — across threads AND across
     * processes (`ASSURE-MAKER-CHECKER-MULTIPOD-1`): the JVM monitor first (an OS file lock is per-process, and a
     * second channel in the same JVM would throw {@code OverlappingFileLockException}), then an OS-level
     * {@code FileChannel.tryLock()} (bounded wait, see {@link #acquire}) on {@code pending-changes/.lock}, which a second Pod sharing the Space's
     * directory waits on. A read-check-write inside it is a compare-and-set: the loser re-reads the record the
     * winner saved (atomic temp + {@code ATOMIC_MOVE}, {@link AtomicFiles}). With no store directory yet there is
     * no record to race over, so only the monitor is taken (a read never creates the directory).
     */
    static <T, E extends Exception> T underStoreLock(Path root, StoreAction<T, E> action) throws IOException, E {
        synchronized (LOCK) {
            Path d = dir(root);
            if (!Files.isDirectory(d)) return action.run();
            try (FileChannel ch = FileChannel.open(d.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = acquire(ch)) {
                return action.run();
            }
        }
    }

    /** Bound on waiting for the store's file lock (ms); {@code -Dinspecto.pendingChanges.lockWaitMs} overrides. */
    static final String PROP_LOCK_WAIT_MS = "inspecto.pendingChanges.lockWaitMs";
    private static final long LOCK_POLL_MS = 25;

    /**
     * {@code tryLock} polled up to {@link #PROP_LOCK_WAIT_MS} (default 5 s) — a Pod that dies or stalls holding the
     * lock must not hang every other Pod's request thread forever. On timeout: 503 {@code STORE_BUSY}, retryable.
     * An {@code OverlappingFileLockException} (this JVM holds it outside the monitor) counts as busy too.
     */
    private static FileLock acquire(FileChannel ch) throws IOException {
        long waitMs = Math.max(0L, Long.getLong(PROP_LOCK_WAIT_MS, 5000L));
        long deadline = System.nanoTime() + waitMs * 1_000_000L;
        while (true) {
            try {
                FileLock l = ch.tryLock();
                if (l != null) return l;
            } catch (java.nio.channels.OverlappingFileLockException busy) {
                // held elsewhere in this JVM — treat like another process holding it
            }
            if (System.nanoTime() >= deadline)
                throw new ApiException(503, ErrorCodes.STORE_BUSY, "the Pending Change store is locked by another "
                        + "request or Pod and did not free up within " + waitMs + " ms — retry");
            try {
                Thread.sleep(LOCK_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for the Pending Change store lock", e);
            }
        }
    }

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
