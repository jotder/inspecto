package com.gamma.intelligence.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.control.ControlApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * A thin loopback HTTP client the agent's <em>act</em> tools (AGT-5 P3) use to drive Inspecto's own
 * governed control plane — the same fail-closed, audited routes a UI/API caller hits, never a private
 * backdoor (plan §0.2, §3). Every request carries {@code X-Agent-Session: <session>} so
 * {@code ApiContext.actor}/{@code AuditTrail} attribute the ensuing write as {@code actor=agent:<session>}
 * (the exact seam the shipped S6 agent-invoke case proved).
 *
 * <p>The base URL is discovered at call time from {@link ControlApi#LOCAL_BASE_URL_PROP} (published by
 * a running control plane in this JVM). When absent — no control plane up — {@link #baseUrl()} is empty
 * and callers return an honest {@code ok=false} rather than mutating anything out-of-band.
 */
public final class ControlPlaneClient {

    /** Response projection: HTTP status, parsed JSON body (best-effort), and the {@code ETag} header. */
    public record Response(int status, Map<String, Object> body, String etag, String raw) {
        public boolean ok() { return status >= 200 && status < 300; }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT_SESSION_HEADER = "X-Agent-Session";

    /** API-5 (2026-07-25): the control plane serves its route table only under {@code /api/v1} — the
     *  unversioned aliases these tools used are retired. The version lives here rather than in
     *  {@link ControlApi#LOCAL_BASE_URL_PROP} (which stays a bare origin), mirroring how the SPA composes
     *  {@code apiBaseUrl + "/v1" + path}. */
    private static final String API_PREFIX = "/api/v1";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** The loopback control-plane base URL (root, no {@code /api/v1}), or empty when none is running. */
    public Optional<String> baseUrl() {
        String url = System.getProperty(ControlApi.LOCAL_BASE_URL_PROP);
        return url == null || url.isBlank() ? Optional.empty() : Optional.of(url.trim());
    }

    /**
     * Issue one request to a control-plane path (e.g. {@code /components/expectation/amt-nonneg}),
     * attributed to {@code agentSession}. {@code path} is version-free — {@link #API_PREFIX} is prepended
     * here, and the v1 envelope is unwrapped on the way back. {@code jsonBody} is sent for non-GET (null for GET);
     * {@code ifMatch} adds an optimistic-lock precondition when non-null. Never throws — a transport
     * failure or absent control plane surfaces as a {@code status<0} {@link Response}.
     */
    public Response exchange(String method, String path, Object jsonBody, String ifMatch, String agentSession) {
        Optional<String> base = baseUrl();
        if (base.isEmpty()) return new Response(-1, Map.of(), null, "control plane not reachable");
        try {
            HttpRequest.BodyPublisher pub = jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(jsonBody));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base.get() + API_PREFIX + path))
                    .timeout(Duration.ofSeconds(20))
                    .header(AGENT_SESSION_HEADER, agentSession)
                    .method(method, pub);
            if (jsonBody != null) b.header("Content-Type", "application/json");
            if (ifMatch != null && !ifMatch.isBlank()) b.header("If-Match", ifMatch);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), parse(r.body()), r.headers().firstValue("ETag").orElse(null), r.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Response(-1, Map.of(), null, "control-plane request failed: " + e.getMessage());
        }
    }

    /**
     * Project the response body as the resource itself, unwrapping the v1 envelope.
     *
     * <p>Because requests now go to {@code /api/v1} (API-5), a success body is
     * {@code {data, metadata, links, permissions, diagnostics}} rather than the bare resource — so
     * {@code data} is lifted out and callers keep reading domain fields directly
     * ({@code body().get("runId")}), exactly as they did against the unversioned surface. This is the
     * server-side counterpart of the SPA's {@code v1Interceptor}. Error bodies are left alone: the v1
     * error object ({@code {error:{errorCode, message, …}}}) has no {@code data}, and callers report
     * failures from {@link Response#raw()} anyway.
     */
    private static Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
            if (parsed.containsKey("data")) {
                // A v1 success envelope. `data` is null for no-content results and may be a non-object
                // (list/scalar) for some routes — neither is projectable as a field map, so yield empty.
                return parsed.get("data") instanceof Map<?, ?> data ? castMap(data) : Map.of();
            }
            return parsed;
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return Map.of(); // non-object bodies (rare on these routes) are simply not projected
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) { return (Map<String, Object>) m; }
}
