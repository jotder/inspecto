package com.gamma.control;

import com.gamma.pipeline.ComponentStore;
import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A Space's per-kind approval policy (`ASSURE-MAKER-CHECKER-1` S1): which config kinds a change must be
 * approved for before it is written, who may approve it, and whether the approver must be someone else.
 * Persisted as {@code approval.toon} in the Space's config tree; {@code GET|PUT /settings/approval}.
 * <pre>
 *   approval:
 *     pipeline: { required: true, approverCapability: canApproveChanges, fourEyes: true }
 *   expiresAfterHours: 168
 * </pre>
 *
 * <p><b>Default OFF.</b> No file, or a file naming no kind, holds nothing — every write behaves exactly as it
 * did before this class existed.
 *
 * <p>🔴 <b>Fail closed, twice.</b> At SAVE, anything the policy cannot mean is refused (422): a kind that is not
 * {@link #GOVERNABLE}, a capability no route demands, an unknown key, a policy that requires approval on a
 * build with no {@link Authenticator} (four-eyes needs two people who can be told apart, so such a policy
 * could never approve anything). At READ, a file that is present but unreadable or invalid — a hand edit —
 * is NOT read as "off": it holds every governable kind with the defaults, and says so in the log.
 */
record ApprovalPolicy(Map<String, Rule> rules, int expiresAfterHours, boolean failedClosed) {

    private static final Logger log = LoggerFactory.getLogger(ApprovalPolicy.class);

    static final String FILE = "approval.toon";
    static final String DEFAULT_APPROVER = Roles.CAN_APPROVE_CHANGES;
    static final int DEFAULT_EXPIRY_HOURS = 168;
    static final int MAX_EXPIRY_HOURS = 24 * 366;
    static final ApprovalPolicy OFF = new ApprovalPolicy(Map.of(), DEFAULT_EXPIRY_HOURS, false);

    /**
     * The kinds a policy may name: exactly the kinds every authoring writer of which reaches
     * {@code PendingChanges.hold} ({@code ConfigWriteFunnelTest} holds that). The pipeline-shaped config
     * types written through {@code /config/write} + {@code /config/patch} (and, for {@code pipeline}, the
     * graph, history-restore and the four Pipeline edits), and every {@link ComponentStore} kind but the three
     * below. Bulk writers (bundle imports, BI template apply) and the Investigation Alert Rule bind refuse under
     * a policy instead ({@code PendingChanges.holdRefusing}).
     *
     * <p>{@code job} is governable since 2026-09-28 (operator): the six {@code /jobs} writers hold; a Job's
     * RUN-time writes do not, by decision. ⛔ Deliberately NOT governable: {@code requirement} (a business request's triage lifecycle, not config the engine runs), {@code channel}
     * and {@code notification-rule} (their {@code NotificationRoutes} writers are not held yet), Connections
     * (secret-aware CRUD of their own), and the Space settings documents — {@code approval.toon} above all,
     * since a policy that could hold its own change could never be turned off.
     */
    static final Set<String> GOVERNABLE = governable();

    private static Set<String> governable() {
        Set<String> s = new TreeSet<>(ComponentStore.WRITABLE_TYPES);
        s.removeAll(Set.of("requirement", "channel", "notification-rule"));
        s.addAll(Set.of("pipeline", "schema", "enrichment", "meta", JobRoutes.KIND));
        // ASSURE-ENTITY-LISTS-1 (D-P5): member / range changes and retire of an Entity List (inspecto-entity-list) hold
        // here; an add-only change whose every entry expires within 24 h applies at once and is reviewed after.
        s.add("entity-list");
        return java.util.Collections.unmodifiableSet(s);
    }

    /** One kind's rule. */
    record Rule(boolean required, String approverCapability, boolean fourEyes) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("required", required);
            m.put("approverCapability", approverCapability);
            m.put("fourEyes", fourEyes);
            return m;
        }
    }

    /** The rule in force for {@code kind}, or {@code null} when a change to it is written straight away. */
    Rule ruleFor(String kind) {
        if (failedClosed) return GOVERNABLE.contains(kind) ? new Rule(true, DEFAULT_APPROVER, true) : null;
        Rule r = rules.get(kind);
        return r != null && r.required() ? r : null;
    }

    /** Whether this policy holds ANY kind — an import that cannot classify a file refuses under it. */
    boolean holdsAnything() {
        return failedClosed || rules.values().stream().anyMatch(Rule::required);
    }

    Map<String, Object> toMap() {
        Map<String, Object> kinds = new LinkedHashMap<>();
        rules.forEach((k, r) -> kinds.put(k, r.toMap()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approval", kinds);
        m.put("expiresAfterHours", expiresAfterHours);
        return m;
    }

    void write(Path path) throws IOException {
        Map<String, Object> m = toMap();
        if (expiresAfterHours == DEFAULT_EXPIRY_HOURS) m.remove("expiresAfterHours");
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".approval-");
    }

    /** The policy of the Space whose config root is {@code writeRoot}; {@link #OFF} when there is none. */
    static ApprovalPolicy forRoot(Path writeRoot) {
        if (writeRoot == null) return OFF;
        Path f = writeRoot.resolve(FILE);
        if (!Files.exists(f)) return OFF;
        try {
            return parse(ToonHelper.load(f.toString()), false);
        } catch (Exception bad) {
            log.warn("[APPROVAL] {} is unreadable or invalid ({}) — holding EVERY governable kind for approval "
                    + "until it is fixed", f, bad.getMessage());
            return new ApprovalPolicy(Map.of(), DEFAULT_EXPIRY_HOURS, true);
        }
    }

    /**
     * Parse and validate {@code doc}; every refusal is an {@link IllegalArgumentException} naming the fault.
     * {@code saving} additionally refuses a policy that requires approval on a build with no Authenticator.
     */
    static ApprovalPolicy parse(Map<String, Object> doc, boolean saving) {
        for (String k : doc.keySet())
            if (!Set.of("approval", "expiresAfterHours").contains(k))
                throw new IllegalArgumentException("unknown key '" + k + "' (expected approval, expiresAfterHours)");
        int expiry = DEFAULT_EXPIRY_HOURS;
        Object rawExpiry = doc.get("expiresAfterHours");
        if (rawExpiry != null) {
            try {
                expiry = Integer.parseInt(String.valueOf(rawExpiry).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("expiresAfterHours must be an integer, got '" + rawExpiry + "'");
            }
            if (expiry < 1 || expiry > MAX_EXPIRY_HOURS)
                throw new IllegalArgumentException("expiresAfterHours must be 1.." + MAX_EXPIRY_HOURS + ", got " + expiry);
        }
        Object raw = doc.get("approval");
        Map<String, Rule> rules = new LinkedHashMap<>();
        if (raw != null && !(raw instanceof Map<?, ?>))
            throw new IllegalArgumentException("approval must be a map of kind → {required, approverCapability, fourEyes}");
        if (raw instanceof Map<?, ?> kinds) {
            for (Map.Entry<?, ?> e : kinds.entrySet()) {
                String kind = String.valueOf(e.getKey());
                if (!GOVERNABLE.contains(kind))
                    throw new IllegalArgumentException("kind '" + kind + "' cannot be governed — its writes do not "
                            + "all pass through the Pending Change hold (governable: " + GOVERNABLE + ")");
                rules.put(kind, rule(kind, e.getValue()));
            }
        }
        if (saving && rules.values().stream().anyMatch(Rule::required) && Authenticators.active().isEmpty())
            throw new IllegalArgumentException("an approval policy needs signed-in users — this build has no "
                    + "Authenticator, so an author and an approver cannot be told apart and nothing could be approved");
        return new ApprovalPolicy(java.util.Collections.unmodifiableMap(rules), expiry, false);
    }

    private static Rule rule(String kind, Object raw) {
        if (!(raw instanceof Map<?, ?> m))
            throw new IllegalArgumentException("approval." + kind + " must be a map {required, approverCapability, fourEyes}");
        for (Object k : m.keySet())
            if (!Set.of("required", "approverCapability", "fourEyes").contains(String.valueOf(k)))
                throw new IllegalArgumentException("approval." + kind + ": unknown key '" + k
                        + "' (expected required, approverCapability, fourEyes)");
        boolean required = bool(kind, "required", m.get("required"), false);
        boolean fourEyes = bool(kind, "fourEyes", m.get("fourEyes"), true);
        Object cap = m.get("approverCapability");
        String approver = cap == null || String.valueOf(cap).isBlank() ? DEFAULT_APPROVER : String.valueOf(cap).trim();
        if (!CapabilityManifest.capabilities().contains(approver))
            throw new IllegalArgumentException("approval." + kind + ".approverCapability '" + approver
                    + "' is not a capability any route demands (expected one of " + CapabilityManifest.capabilities() + ")");
        return new Rule(required, approver, fourEyes);
    }

    private static boolean bool(String kind, String key, Object v, boolean dflt) {
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        throw new IllegalArgumentException("approval." + kind + "." + key + " must be true or false, got '" + v + "'");
    }
}
