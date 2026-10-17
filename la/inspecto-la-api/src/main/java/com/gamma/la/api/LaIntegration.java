package com.gamma.la.api;

import com.gamma.auth.secrets.SecretResolver;
import com.gamma.la.core.OutboundPort;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Space's <b>third-party integration settings</b> (LA-EDITIONS-1 phase 6, outbound): {@value #FILE} in the Space's
 * config root, written only by {@code PUT /inv/integration} ({@code canAdminister}) and reserved from every import.
 * JSON, not TOON, because a field-mapping template is arbitrary JSON and must round-trip exactly.
 *
 * <pre>
 * { "handoff":  { "connection": "case-manager", "system": "case-manager", "authHeader": "X-Api-Key",
 *                 "template": {...}, "caseIdPointer": "/id", "caseUrlPointer": "/links/self" },
 *   "webhooks": { "connection": "fraud-hub", "signingSecret": "${ENV:LA_WEBHOOK_SECRET}",
 *                 "events": ["investigation.created", "draft.promoted", "alert.fired", "approval.needed"] },
 *   "retry":    { "count": 2, "backoff": "EXPONENTIAL", "initialDelayMillis": 1000, "maxDelayMillis": 30000 } }
 * </pre>
 *
 * <p>⛔ <b>No secret is ever stored here.</b> The endpoint is a registered {@code https} Connection (onboarded under the
 * admin-only {@code canOnboardConnections}; its token is the Connection's {@code ${…}} reference), and the webhook signing
 * secret must itself be a {@link SecretResolver} reference - a literal is refused (422). A file that is present but
 * unreadable reads as NOT CONFIGURED - fail closed: nothing leaves.
 */
public final class LaIntegration {

    public static final String FILE = "la-integration.json";

    /** The webhook events a Space may subscribe to. */
    public static final List<String> EVENTS = List.of("investigation.created", "draft.promoted", "alert.fired", "approval.needed");

    /** The placeholders a hand-off template may use: a leaf that is exactly one of them takes its value (any JSON). */
    public static final Set<String> PLACEHOLDERS = Set.of("investigation.id", "investigation.purpose", "investigation.owner",
            "investigation.dataset", "bundle", "findings", "masking", "seal", "manifestRoot", "handedOffBy", "handedOffAt");

    static final int MAX_TEMPLATE_BYTES = 65_536;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");
    private static final Set<String> RESERVED_HEADERS = Set.of("host", "content-type", "content-length", "transfer-encoding",
            "connection", "idempotency-key", "x-inspecto-event", "x-inspecto-delivery-id", "x-inspecto-timestamp",
            "x-inspecto-signature");
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z.]+)\\s*}}");

    /** Where a hand-off goes and how its answer names the external case. */
    public record Handoff(String connection, String system, String authHeader, Object template, String caseIdPointer,
                          String caseUrlPointer) {}

    /** Where the signed event webhooks go. {@code signingSecret} is a {@code ${…}} reference, never a value. */
    public record Webhooks(String connection, String signingSecret, List<String> events) {}

    /** The parsed document; either part may be absent. */
    public record Settings(Handoff handoff, Webhooks webhooks, OutboundPort.Retry retry) {
        public static final Settings NONE = new Settings(null, null, OutboundPort.Retry.DEFAULT);
    }

    private LaIntegration() {}

    /** The settings of the Space whose config root is {@code root}; {@link Settings#NONE} when absent or unreadable. */
    public static Settings read(Path root) {
        if (root == null) return Settings.NONE;
        Path f = root.resolve(FILE);
        if (!Files.isRegularFile(f)) return Settings.NONE;
        try {
            @SuppressWarnings("unchecked") Map<String, Object> m = ApiContext.JSON.readValue(Files.readString(f), Map.class);
            return parse(m);
        } catch (IOException | RuntimeException e) {
            return Settings.NONE;   // fail closed: an unreadable document sends nothing
        }
    }

    /** The stored document as written (for GET), or an empty map. */
    static Map<String, Object> raw(Path root) {
        try {
            Path f = root.resolve(FILE);
            if (!Files.isRegularFile(f)) return new LinkedHashMap<>();
            @SuppressWarnings("unchecked") Map<String, Object> m = ApiContext.JSON.readValue(Files.readString(f), Map.class);
            return m;
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** Validate and write atomically; returns the normalised document. Throws {@link IllegalArgumentException} naming the fault. */
    static Map<String, Object> write(Path root, Map<String, Object> body) throws IOException {
        Settings s = parse(body);
        Map<String, Object> doc = shape(s);
        AtomicFiles.write(root.resolve(FILE), ApiContext.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc)
                .getBytes(StandardCharsets.UTF_8), ".la-integration-");
        return doc;
    }

    /** The normalised wire shape of {@code s}. */
    static Map<String, Object> shape(Settings s) {
        Map<String, Object> doc = new LinkedHashMap<>();
        if (s.handoff() != null) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("connection", s.handoff().connection());
            h.put("system", s.handoff().system());
            if (s.handoff().authHeader() != null) h.put("authHeader", s.handoff().authHeader());
            if (s.handoff().template() != null) h.put("template", s.handoff().template());
            h.put("caseIdPointer", s.handoff().caseIdPointer());
            if (s.handoff().caseUrlPointer() != null) h.put("caseUrlPointer", s.handoff().caseUrlPointer());
            doc.put("handoff", h);
        }
        if (s.webhooks() != null) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("connection", s.webhooks().connection());
            w.put("signingSecret", s.webhooks().signingSecret());
            w.put("events", s.webhooks().events());
            doc.put("webhooks", w);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", s.retry().count());
        r.put("backoff", s.retry().backoff());
        r.put("initialDelayMillis", s.retry().initialDelayMillis());
        r.put("maxDelayMillis", s.retry().maxDelayMillis());
        doc.put("retry", r);
        return doc;
    }

    /** Parse and validate a document; {@link IllegalArgumentException} names the first fault. */
    @SuppressWarnings("unchecked")
    static Settings parse(Map<String, Object> m) {
        onlyKeys(m, "", Set.of("handoff", "webhooks", "retry"));
        Handoff handoff = null;
        if (m.get("handoff") != null) {
            Map<String, Object> h = object(m.get("handoff"), "handoff");
            onlyKeys(h, "handoff.", Set.of("connection", "system", "authHeader", "template", "caseIdPointer", "caseUrlPointer"));
            String connection = name(h, "connection", "handoff", null);
            String system = name(h, "system", "handoff", "case-manager");
            String authHeader = optString(h, "authHeader", "handoff");
            if (authHeader != null && (!HEADER.matcher(authHeader).matches()
                    || RESERVED_HEADERS.contains(authHeader.toLowerCase(Locale.ROOT))))
                throw new IllegalArgumentException("handoff.authHeader must be a plain header name (not one the transport or "
                        + "the signature sets), got '" + authHeader + "'");
            Object template = h.get("template");
            if (template != null) {
                if (!(template instanceof Map<?, ?>)) throw new IllegalArgumentException("handoff.template must be a JSON object");
                checkTemplate(template);
                int bytes;
                try {
                    bytes = ApiContext.JSON.writeValueAsBytes(template).length;
                } catch (IOException e) {
                    throw new IllegalArgumentException("handoff.template is not serialisable");
                }
                if (bytes > MAX_TEMPLATE_BYTES)
                    throw new IllegalArgumentException("handoff.template is at most " + MAX_TEMPLATE_BYTES + " bytes");
            }
            String idPointer = pointer(h, "caseIdPointer", "/id");
            String urlPointer = pointer(h, "caseUrlPointer", null);
            handoff = new Handoff(connection, system, authHeader, template, idPointer, urlPointer);
        }
        Webhooks webhooks = null;
        if (m.get("webhooks") != null) {
            Map<String, Object> w = object(m.get("webhooks"), "webhooks");
            onlyKeys(w, "webhooks.", Set.of("connection", "signingSecret", "events"));
            String connection = name(w, "connection", "webhooks", null);
            String secret = optString(w, "signingSecret", "webhooks");
            if (secret == null)
                throw new IllegalArgumentException("webhooks.signingSecret is required: every webhook is signed (HMAC-SHA256)");
            if (!SecretResolver.isReference(secret))
                throw new IllegalArgumentException("webhooks.signingSecret must be a secret reference such as ${ENV:NAME} - a "
                        + "literal secret is never stored in configuration");
            if (!(w.get("events") instanceof List<?> raw) || raw.isEmpty())
                throw new IllegalArgumentException("webhooks.events must be a non-empty list of " + EVENTS);
            Set<String> events = new LinkedHashSet<>();
            for (Object o : raw) {
                if (!(o instanceof String e) || !EVENTS.contains(e))
                    throw new IllegalArgumentException("webhooks.events: unknown event '" + o + "' (expected " + EVENTS + ")");
                events.add(e);
            }
            webhooks = new Webhooks(connection, secret, List.copyOf(events));
        }
        OutboundPort.Retry retry = OutboundPort.Retry.DEFAULT;
        if (m.get("retry") != null) {
            Map<String, Object> r = object(m.get("retry"), "retry");
            onlyKeys(r, "retry.", Set.of("count", "backoff", "initialDelayMillis", "maxDelayMillis"));
            int count = (int) number(r, "count", retry.count(), 0, 5);
            String backoff = r.get("backoff") == null ? retry.backoff() : String.valueOf(r.get("backoff")).trim().toUpperCase(Locale.ROOT);
            if (!List.of("EXPONENTIAL", "LINEAR", "FIXED").contains(backoff))
                throw new IllegalArgumentException("retry.backoff must be EXPONENTIAL, LINEAR or FIXED");
            long initial = number(r, "initialDelayMillis", retry.initialDelayMillis(), 1, 60_000);
            long max = number(r, "maxDelayMillis", Math.max(initial, retry.maxDelayMillis()), initial, 300_000);
            retry = new OutboundPort.Retry(count, backoff, initial, max);
        }
        return new Settings(handoff, webhooks, retry);
    }

    /** Every placeholder a template's string leaves use must be one of {@link #PLACEHOLDERS}. */
    private static void checkTemplate(Object node) {
        if (node instanceof String s) {
            Matcher mt = PLACEHOLDER.matcher(s);
            while (mt.find())
                if (!PLACEHOLDERS.contains(mt.group(1)))
                    throw new IllegalArgumentException("handoff.template: unknown placeholder {{" + mt.group(1) + "}} (known: "
                            + new java.util.TreeSet<>(PLACEHOLDERS) + ")");
        } else if (node instanceof Map<?, ?> map) {
            for (Object v : map.values()) checkTemplate(v);
        } else if (node instanceof List<?> list) {
            for (Object v : list) checkTemplate(v);
        }
    }

    /**
     * Render a template: a string leaf that is EXACTLY one placeholder takes the context value as-is (an object stays an
     * object); a placeholder inside other text is replaced by the value's text. Keys and non-string leaves pass through.
     */
    static Object render(Object node, Map<String, Object> context) {
        if (node instanceof String s) {
            Matcher whole = PLACEHOLDER.matcher(s);
            if (whole.matches()) return context.get(whole.group(1));
            Matcher mt = PLACEHOLDER.matcher(s);
            StringBuilder out = new StringBuilder();
            while (mt.find()) {
                Object v = context.get(mt.group(1));
                mt.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
            }
            mt.appendTail(out);
            return out.toString();
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), render(v, context)));
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object v : list) out.add(render(v, context));
            return out;
        }
        return node;
    }

    // ── field helpers ────────────────────────────────────────────────────────────────────────────────

    private static void onlyKeys(Map<String, Object> m, String prefix, Set<String> allowed) {
        for (String k : m.keySet())
            if (!allowed.contains(k))
                throw new IllegalArgumentException("unknown key '" + prefix + k + "' (expected " + allowed + ")");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object o, String field) {
        if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("'" + field + "' must be an object");
        return (Map<String, Object>) m;
    }

    private static String optString(Map<String, Object> m, String key, String parent) {
        Object v = m.get(key);
        if (v == null || v instanceof String s && s.isBlank()) return null;
        if (!(v instanceof String s)) throw new IllegalArgumentException(parent + "." + key + " must be a string");
        return s.strip();
    }

    private static String name(Map<String, Object> m, String key, String parent, String dflt) {
        String v = optString(m, key, parent);
        if (v == null) {
            if (dflt == null) throw new IllegalArgumentException(parent + "." + key + " is required");
            return dflt;
        }
        if (!NAME.matcher(v).matches())
            throw new IllegalArgumentException(parent + "." + key + " must match " + NAME.pattern());
        return v;
    }

    private static String pointer(Map<String, Object> m, String key, String dflt) {
        String v = optString(m, key, "handoff");
        if (v == null) return dflt;
        if (!v.startsWith("/") || v.length() > 256)
            throw new IllegalArgumentException("handoff." + key + " must be a JSON pointer such as /id");
        return v;
    }

    private static long number(Map<String, Object> m, String key, long dflt, long min, long max) {
        Object v = m.get(key);
        if (v == null) return dflt;
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()))
            throw new IllegalArgumentException("retry." + key + " must be a whole number");
        long l = n.longValue();
        if (l < min || l > max) throw new IllegalArgumentException("retry." + key + " must be between " + min + " and " + max);
        return l;
    }
}
