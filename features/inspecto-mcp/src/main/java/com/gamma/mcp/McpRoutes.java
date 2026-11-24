package com.gamma.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.audit.AuditAttrs;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.audit.EventType;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.AuthChallenges;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.RequestAttrs;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code POST /mcp} — the Model Context Protocol endpoint (AGENT-KIT-1 phase AK-1, read-only). Streamable HTTP,
 * revision {@value #MODERN} pinned, dual-era: a request without modern {@code _meta} that opens with
 * {@code initialize} is served as a legacy revision, statelessly (no session is minted or honoured).
 *
 * <p><b>No side door.</b> The host already ran authentication, the rate limit, the access-policy decision and the
 * per-Space module switch on this request. Every MCP Tool and resource is then an in-process
 * {@link ApiContext#replay replay} of an existing route as the caller, so that route's own gates and masking decide
 * what the agent sees. This class holds no data access and no capability table of its own.
 *
 * <p>Design and the full check order: {@code docs/okf/product/module/mcp/spec-mcp.md}.
 */
public final class McpRoutes implements RouteModule {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(McpRoutes.class);

    static final String MODERN = "2026-07-28";
    static final Set<String> LEGACY = Set.of("2025-11-25", "2025-06-18");
    static final List<String> SUPPORTED = List.of(MODERN, "2025-11-25", "2025-06-18");

    static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CLIENT = "io.modelcontextprotocol/clientInfo";
    static final String META_SERVER = "io.modelcontextprotocol/serverInfo";

    /** A request body larger than this is refused (413) before it is parsed. */
    static final int MAX_BODY = 256 * 1024;
    /** A replayed route's answer larger than this is cut, and the result says so. */
    static final int MAX_RESULT = 256 * 1024;
    /** How long a client may cache {@code tools/list}, {@code resources/list} and {@code server/discover}. */
    static final long LIST_TTL_MS = 300_000;

    static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601,
            INVALID_PARAMS = -32602, HEADER_MISMATCH = -32020, UNSUPPORTED_VERSION = -32022;

    /** The RFC 9728 protected-resource metadata route (AK-2a), public: the host lists it in its exact-match public paths. */
    static final String METADATA = "/.well-known/oauth-protected-resource";

    @Override
    public void register(ApiContext api) {
        api.post("/mcp", (ex, m) -> handle(api, ex));
        api.get(METADATA, (ex, m) -> metadata(ex));
        AuthChallenges.register("/mcp", McpRoutes::challenge);
    }

    // ── discovery: OAuth protected-resource metadata (RFC 9728, AGENT-KIT-1 AK-2a) ──────────────────

    /**
     * {@code GET /.well-known/oauth-protected-resource} under {@code /api/v1} (and {@code /api/v1/spaces/{id}}): which
     * authorization server issues the tokens this MCP endpoint accepts, so an MCP client runs authorization code with
     * PKCE against the customer's IdP itself. 404 when no Authenticator names an issuer (sign-in off). The host serves
     * nothing outside {@code /api/v1}, so the RFC's root path needs a reverse-proxy alias (admin guide).
     */
    private static Object metadata(HttpExchange ex) throws IOException {
        String issuer = issuer();
        if (issuer == null)
            return send(ex, 404, Map.of("error", "no OAuth sign-in is configured on this install, so the MCP endpoint "
                    + "names no authorization server"));
        String origin = origin(ex);
        if (origin == null) return send(ex, 400, Map.of("error", "the request names no valid Host"));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("resource", origin + prefix(ex, METADATA) + "/mcp");
        doc.put("authorization_servers", List.of(issuer));
        doc.put("scopes_supported", scopes());
        doc.put("bearer_methods_supported", List.of("header"));
        doc.put("resource_name", "Inspecto MCP endpoint");
        return send(ex, 200, doc);
    }

    /** The {@code WWW-Authenticate} value on the host's 401 for {@code /mcp}: where the metadata lives (RFC 9728 §5.1). */
    static String challenge(HttpExchange ex) {
        String origin = origin(ex);
        if (issuer() == null || origin == null) return null;
        return "Bearer resource_metadata=\"" + origin + prefix(ex, "/mcp") + METADATA + "\"";
    }

    private static String issuer() {
        return Authenticators.active().flatMap(Authenticator::issuer).filter(i -> !i.isBlank()).orElse(null);
    }

    /** {@code -Dmcp.publicOrigin} (behind a TLS-terminating proxy), else the request's own scheme and Host. */
    private static String origin(HttpExchange ex) {
        String pinned = System.getProperty("mcp.publicOrigin");
        if (pinned != null && !pinned.isBlank()) return pinned.trim().replaceAll("/+$", "");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !HOST.matcher(host).matches()) return null;
        return (ex instanceof com.sun.net.httpserver.HttpsExchange ? "https" : "http") + "://" + host;
    }

    private static final java.util.regex.Pattern HOST = java.util.regex.Pattern.compile("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?|\\[[0-9A-Fa-f:.]{2,45}](:[0-9]{1,5})?");

    /** The raw request path before {@code route} — {@code /api/v1} or {@code /api/v1/spaces/{id}}. */
    private static String prefix(HttpExchange ex, String route) {
        String raw = ex.getRequestURI().getRawPath();
        return raw.endsWith(route) ? raw.substring(0, raw.length() - route.length()) : "/api/v1";
    }

    /** {@code -Dauth.oidc.scopes} (space-separated, as the SPA's sign-in uses it), else {@code openid}. */
    private static List<String> scopes() {
        String raw = System.getProperty("auth.oidc.scopes");
        if (raw == null || raw.isBlank()) return List.of("openid");
        return java.util.Arrays.stream(raw.trim().split("[ ,]+")).filter(x -> !x.isEmpty()).toList();
    }

    @Override
    public Set<String> featureIds() {
        return Set.of("mcp");
    }

    // ── transport ───────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Object handle(ApiContext api, HttpExchange ex) throws IOException {
        // 1. A Subject: on a bundle started without sign-in nothing authenticated this request (Basic carries no module,
        //    but a Standard install without an Authenticator would otherwise serve an anonymous agent).
        if (!(RequestAttrs.attr(ex, RequestAttrs.ATTR_SUBJECT) instanceof Subject))
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                    "the MCP endpoint needs sign-in: no Authenticator is configured on this install");
        // 2. DNS-rebinding rule of the specification: a present Origin must be the one allowed browser origin.
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null) {
            String allowed = System.getProperty("control.cors");
            if (allowed == null || allowed.isBlank() || !allowed.trim().equals(origin.trim()))
                return send(ex, 403, error(null, INVALID_REQUEST, "Origin '" + origin + "' is not allowed", null));
        }
        // 3. One bounded JSON-RPC object.
        byte[] raw = api.rawBody(ex, MAX_BODY);
        JsonNode req;
        try {
            req = ApiContext.JSON.readTree(raw);
        } catch (JsonProcessingException bad) {
            return send(ex, 400, error(null, PARSE_ERROR, "the body is not JSON", null));
        }
        if (req == null || !req.isObject())
            return send(ex, 400, error(null, INVALID_REQUEST, "the body must be one JSON-RPC object (batches are not accepted)", null));
        Object id = idOf(req.get("id"));
        if (!req.path("jsonrpc").isTextual() || !"2.0".equals(req.get("jsonrpc").asText()) || !req.path("method").isTextual()
                || (req.has("id") && id == null))
            return send(ex, 400, error(id, INVALID_REQUEST, "not a JSON-RPC 2.0 request", null));
        String method = req.get("method").asText();
        JsonNode params = req.path("params");
        if (!req.has("id")) return send(ex, 202, null);   // a notification: accepted, nothing to answer

        // 4. The era and the header rules.
        String headerVersion = ex.getRequestHeaders().getFirst("MCP-Protocol-Version");
        JsonNode meta = params.path("_meta");
        String metaVersion = meta.path(META_VERSION).isTextual() ? meta.get(META_VERSION).asText() : null;
        String version;
        if (metaVersion != null) {
            if (!MODERN.equals(metaVersion)) return unsupported(ex, id, metaVersion);
            String refused = headerMismatch(ex, method, params, headerVersion, metaVersion);
            if (refused != null) return send(ex, 400, error(id, HEADER_MISMATCH, refused, null));
            version = MODERN;
        } else if ("initialize".equals(method)) {
            String asked = params.path("protocolVersion").asText("");
            version = LEGACY.contains(asked) ? asked : "2025-11-25";
        } else if (headerVersion != null && MODERN.equals(headerVersion.trim())) {
            return send(ex, 400, error(id, HEADER_MISMATCH, "MCP-Protocol-Version " + MODERN
                    + " needs io.modelcontextprotocol/protocolVersion in params._meta", null));
        } else if (headerVersion != null && LEGACY.contains(headerVersion.trim())) {
            version = headerVersion.trim();
        } else if (headerVersion != null) {
            return unsupported(ex, id, headerVersion.trim());
        } else {
            return send(ex, 400, error(id, HEADER_MISMATCH,
                    "missing MCP-Protocol-Version header and io.modelcontextprotocol/protocolVersion _meta", null));
        }
        boolean modern = MODERN.equals(version);
        if (modern && "initialize".equals(method))
            return send(ex, 404, error(id, METHOD_NOT_FOUND, "initialize is not part of " + MODERN
                    + "; this server also speaks " + String.join(", ", LEGACY) + " for clients that need it", null));

        Map<String, Object> client = meta.path(META_CLIENT).isObject()
                ? ApiContext.JSON.convertValue(meta.get(META_CLIENT), Map.class) : null;
        try {
            Map<String, Object> result = switch (method) {
                case "server/discover" -> modern ? discover() : null;
                case "initialize" -> initialize(version);
                case "ping" -> modern ? null : new LinkedHashMap<>();   // legacy only: removed in 2026-07-28
                case "tools/list" -> listResult("tools", McpTools.ALL_TOOLS.stream().map(McpTools.Tool::definition).toList(), modern);
                case "resources/list" -> listResult("resources",
                        McpTools.RESOURCES.stream().map(McpTools.Resource::definition).toList(), modern);
                case "tools/call" -> callTool(api, ex, params, version, client);
                case "resources/read" -> readResource(api, ex, params, version, client);
                default -> null;
            };
            if (result == null) return send(ex, 404, error(id, METHOD_NOT_FOUND, "method '" + method + "' is not supported", null));
            if (modern) {
                result.putIfAbsent("resultType", "complete");
                result.put("_meta", Map.of(META_SERVER, serverInfo()));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("jsonrpc", "2.0");
            out.put("id", id);
            out.put("result", result);
            return send(ex, 200, out);
        } catch (McpTools.BadArgs bad) {
            return send(ex, 200, error(id, INVALID_PARAMS, bad.getMessage(), null));
        }
    }

    /** Null when every mirrored header matches the body; otherwise what is wrong (spec "Server Validation"). */
    private static String headerMismatch(HttpExchange ex, String method, JsonNode params, String headerVersion,
                                         String metaVersion) {
        if (headerVersion == null) return "missing MCP-Protocol-Version header";
        if (!headerVersion.trim().equals(metaVersion))
            return "MCP-Protocol-Version header '" + headerVersion + "' does not match _meta '" + metaVersion + "'";
        String hMethod = ex.getRequestHeaders().getFirst("Mcp-Method");
        if (hMethod == null) return "missing Mcp-Method header";
        if (!hMethod.equals(method)) return "Mcp-Method header '" + hMethod + "' does not match body method '" + method + "'";
        String field = "tools/call".equals(method) ? "name" : "resources/read".equals(method) ? "uri" : null;
        if (field == null) return null;
        String body = params.path(field).isTextual() ? params.get(field).asText() : null;
        String hName = ex.getRequestHeaders().getFirst("Mcp-Name");
        if (hName == null) return "missing Mcp-Name header";
        String decoded = decodeSentinel(hName);
        if (decoded == null) return "Mcp-Name header is not valid base64";
        if (!decoded.equals(body)) return "Mcp-Name header does not match body " + field;
        return null;
    }

    /** {@code =?base64?…?=} decoded; any other value as is; {@code null} for a malformed sentinel. */
    static String decodeSentinel(String v) {
        if (!(v.startsWith("=?base64?") && v.endsWith("?=") && v.length() >= 11)) return v;
        try {
            return new String(Base64.getDecoder().decode(v.substring(9, v.length() - 2)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    private static Object unsupported(HttpExchange ex, Object id, String requested) throws IOException {
        return send(ex, 400, error(id, UNSUPPORTED_VERSION, "Unsupported protocol version",
                Map.of("supported", SUPPORTED, "requested", requested)));
    }

    // ── methods ─────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> capabilities() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tools", Map.of());
        c.put("resources", Map.of());
        return c;
    }

    private static final String INSTRUCTIONS = "Inspecto MCP endpoint. Every tool acts as the signed-in person, in the "
            + "Space this URL names, and sees only what that person may see; classified values come back masked. A change "
            + "is never applied by you: validate_config and dry_run_pipeline first, then propose_change, which holds it "
            + "as a Pending Change for another person to approve. Write tools need sign-in through an agent client. Read "
            + "the inspecto://glossary resource and use its canonical terms. Call edition_and_modules before relying on "
            + "a feature.";

    private static Map<String, Object> discover() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("supportedVersions", SUPPORTED);
        r.put("capabilities", capabilities());
        r.put("instructions", INSTRUCTIONS);
        r.put("ttlMs", LIST_TTL_MS);
        r.put("cacheScope", "private");
        return r;
    }

    private static Map<String, Object> initialize(String version) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", version);
        r.put("capabilities", capabilities());
        r.put("serverInfo", serverInfo());
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    private static Map<String, Object> listResult(String key, List<Map<String, Object>> items, boolean modern) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put(key, items);
        if (modern) {
            r.put("ttlMs", LIST_TTL_MS);
            r.put("cacheScope", "private");
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(ApiContext api, HttpExchange ex, JsonNode params, String version,
                                         Map<String, Object> client) {
        String name = params.path("name").asText(null);
        McpTools.Tool tool = name == null ? null : McpTools.tool(name);
        if (tool == null) throw new McpTools.BadArgs("unknown tool '" + name + "'");
        JsonNode argsNode = params.path("arguments");
        if (!argsNode.isMissingNode() && !argsNode.isNull() && !argsNode.isObject())
            throw new McpTools.BadArgs("'arguments' must be an object");
        Map<String, Object> args = tool.validate(argsNode.isObject()
                ? ApiContext.JSON.convertValue(argsNode, Map.class) : Map.of());
        McpTools.Call call = tool.router().route(args);
        ApiContext.Replayed r;
        if (McpTools.refused(call)) {
            // The endpoint's own refusal list (plan §4.4): never replayed, whoever asks. No router produces such a path.
            r = new ApiContext.Replayed(403, jsonText(Map.of("error", "the MCP endpoint never offers " + call.method() + " "
                    + call.path() + " — approving, revealing, promoting and changing Apps or access are a person's acts")));
        } else if (tool.write() && !RequestAttrs.subject(ex).map(Subject::agent).orElse(false)) {
            // D-AK3: every write names a verified agent client — a token from the console's own client keeps the reads.
            r = new ApiContext.Replayed(403, jsonText(Map.of("error", "'" + name + "' needs sign-in through an agent client "
                    + "(-Dauth.oidc.agentClients), so that every write names a verified client; the read tools stay open")));
        } else {
            r = replay(api, ex, call, "mcpTool", name, client);
        }
        audit(ex, "mcp.tool.called", "mcpTool", name, call, r.status(), version, client);
        return toolResult(r);
    }

    private Map<String, Object> readResource(ApiContext api, HttpExchange ex, JsonNode params, String version,
                                             Map<String, Object> client) {
        String uri = params.path("uri").asText(null);
        McpTools.Resource res = uri == null ? null : McpTools.resource(uri);
        if (res == null) throw new McpTools.BadArgs("unknown resource '" + uri + "'");
        String text;
        int status;
        McpTools.Call call = null;
        if (res.classpath() != null) {
            text = classpathText(res.classpath());
            status = text == null ? 404 : 200;
            if (text == null) text = "{\"error\":\"the glossary is not packaged in this build\"}";
        } else {
            call = new McpTools.Call("GET", res.route(), null);
            ApiContext.Replayed r = replay(api, ex, call, "mcpResource", uri, client);
            text = r.body();
            status = r.status();
        }
        audit(ex, "mcp.resource.read", "mcpResource", uri, call, status, version, client);
        if (status >= 400) throw new McpTools.BadArgs("resource '" + uri + "' could not be read (HTTP " + status + "): " + clip(text));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", uri);
        content.put("mimeType", res.mimeType());
        content.put("text", clip(text));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contents", List.of(content));
        return out;
    }

    private static String classpathText(String path) {
        try (InputStream in = McpRoutes.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ── the replay: the one way a tool reaches data ─────────────────────────────────────────────

    private static ApiContext.Replayed replay(ApiContext api, HttpExchange ex, McpTools.Call call, String kind,
                                              String name, Map<String, Object> client) {
        Map<String, String> headers = new LinkedHashMap<>(call.headers());   // a tool's own fixed headers (X-Change-Reason)
        String ua = ex.getRequestHeaders().getFirst("User-Agent");
        if (ua != null) headers.put("User-Agent", ua);
        // Ride on the replayed route's own AUDIT row (an export, a write in AK-2), so it names the tool too.
        Map<String, Object> auditAttrs = new LinkedHashMap<>();
        auditAttrs.put("via", "mcp");
        auditAttrs.put(kind, name);
        if (client != null) auditAttrs.put("mcpClient", clientLabel(client));
        // AGENT-PROPOSE-500-1: the reason rides as a request attribute, not a header - an in-process replay need not be
        // header-safe, and the JDK refuses a header holding "—" or "→", which agents write.
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put(RequestAttrs.ATTR_AUDIT_ATTRS, auditAttrs);
        String reason = headers.remove("X-Change-Reason");
        if (reason != null) attrs.put(RequestAttrs.ATTR_CHANGE_REASON, reason);
        try {
            return api.replay(ex, call.method(), call.path(), call.body(), headers, attrs);
        } catch (ApiException refused) {
            return new ApiContext.Replayed(refused.status, jsonText(Map.of("error", String.valueOf(refused.getMessage()))));
        } catch (Exception failed) {
            // AGENT-PROPOSE-500-1: a server fault inside a replay is logged like one on a direct request (ControlApi.fail500:
            // ERROR, the correlation id, the stack) - before this it was answered and dropped, so the first live golden run's
            // 53 HTTP 500s left no trace. The agent gets the correlation id an operator can search for, never the message.
            Object cid = ApiContext.attr(ex, ApiContext.ATTR_CORRELATION_ID);
            LOG.error("MCP {} '{}' replaying {} {} failed (Correlation-ID {})", kind, name, call.method(), call.path(), cid, failed);
            return new ApiContext.Replayed(500, jsonText(Map.of("error", "the route failed: " + failed.getClass().getSimpleName()
                    + " - correlation id " + cid)));
        }
    }

    private static Map<String, Object> toolResult(ApiContext.Replayed r) {
        boolean error = r.status() >= 400 || r.status() < 0;
        String body = r.body() == null ? "" : r.body();
        boolean cut = body.length() > MAX_RESULT;
        String text = error ? "HTTP " + r.status() + ": " + clip(body) : clip(body);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(content));
        if (!error && !cut) {
            try {
                JsonNode parsed = ApiContext.JSON.readTree(body);
                if (parsed != null && parsed.isObject()) out.put("structuredContent", parsed);
            } catch (JsonProcessingException notJson) {
                // text only
            }
        }
        out.put("isError", error);
        return out;
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= MAX_RESULT ? s
                : s.substring(0, MAX_RESULT) + "\n[truncated: the answer was " + s.length() + " characters; narrow the request]";
    }

    // ── audit ───────────────────────────────────────────────────────────────────────────────────

    /** One AUDIT row per tool call / resource read, naming the person and the (self-declared) agent client. */
    private static void audit(HttpExchange ex, String action, String kind, String name, McpTools.Call call, int status,
                              String version, Map<String, Object> client) {
        try {
            String actor = RequestAttrs.actor(ex);
            Event.Builder b = Event.builder(EventType.AUDIT)
                    .source("audit")
                    .message(actor + " " + action + " " + name + (status >= 400 ? " (refused, HTTP " + status + ")" : ""))
                    .actor(actor).actorType(RequestAttrs.actorType(ex))
                    .action(action).actionCategory("agent")
                    .target(kind.equals("mcpTool") ? "mcp-tool" : "mcp-resource", name)
                    .userAgent(RequestAttrs.userAgent(ex))
                    .ip(RequestAttrs.ip(ex))
                    .correlationId(RequestAttrs.attr(ex, RequestAttrs.ATTR_CORRELATION_ID) instanceof String c ? c : null)
                    .attr(kind, name)
                    .attr("mcpProtocolVersion", version)
                    .attr(AuditAttrs.HTTP_STATUS, status);
            if (call != null) b.attr("mcpReplay", call.method() + " " + call.path());
            if (client != null) {
                b.attr("mcpClient", clientLabel(client));
                b.attr("mcpClientDeclared", true);
            }
            RequestAttrs.subject(ex).filter(Subject::machine).ifPresent(s -> b.attr(AuditAttrs.IDP_SUBJECT, s.machineSub()));
            // AK-2a: the VERIFIED agent client (the self-declared mcpClient above is a label only).
            RequestAttrs.subject(ex).filter(Subject::agent).ifPresent(s -> b.attr(AuditAttrs.AGENT_CLIENT, s.agentClient()));
            EventSink.current().emit(b);
        } catch (RuntimeException ignore) {
            // best effort: the audit trail must never break the request (as AuditTrail)
        }
    }

    /** {@code name/version}, each cut to 100 characters — the client chose both, so neither is trusted or unbounded. */
    static String clientLabel(Map<String, Object> client) {
        String n = String.valueOf(client.getOrDefault("name", "unknown"));
        Object v = client.get("version");
        String label = cap(n) + (v == null ? "" : "/" + cap(String.valueOf(v)));
        return label.replaceAll("\\p{Cntrl}", "?");
    }

    private static String cap(String s) {
        return s.length() <= 100 ? s : s.substring(0, 100);
    }

    // ── wire helpers ────────────────────────────────────────────────────────────────────────────

    static Map<String, Object> serverInfo() {
        String v = McpRoutes.class.getPackage().getImplementationVersion();
        return Map.of("name", "inspecto", "title", "Inspecto", "version", v == null ? "dev" : v);
    }

    private static Map<String, Object> error(Object id, int code, String message, Object data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        if (data != null) err.put("data", data);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", err);
        return out;
    }

    /** A JSON-RPC id is a string or an integer; anything else is no id. */
    private static Object idOf(JsonNode n) {
        if (n == null) return null;
        if (n.isTextual()) return n.asText();
        if (n.isIntegralNumber()) return n.numberValue();   // echoed exactly, a BigInteger included
        return null;
    }

    static byte[] jsonBytes(Object o) {
        try {
            return ApiContext.JSON.writeValueAsBytes(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsonText(Map<String, Object> o) {
        return new String(jsonBytes(o), StandardCharsets.UTF_8);
    }

    /** Raw JSON-RPC on the wire — never the {@code /api/v1} envelope, which an MCP client cannot read. */
    private static Object send(HttpExchange ex, int status, Object body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return ApiContext.HANDLED;
        }
        byte[] bytes = jsonBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        return ApiContext.HANDLED;
    }
}
