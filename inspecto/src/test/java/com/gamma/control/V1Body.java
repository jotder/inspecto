package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test-only view of a control-plane response body, with the v1 envelope peeled off.
 *
 * <p>Since API-5 (2026-07-25) every route is reached under {@code /api/v1}, so success bodies arrive
 * wrapped as {@code {data, metadata, links, permissions, diagnostics}} ({@link Envelope}) rather than as
 * the bare resource. Tests assert on the resource, not the transport, so {@link #of} lifts {@code data}
 * out — the same unwrap the SPA's {@code v1Interceptor} and {@code ControlPlaneClient} perform.
 *
 * <p>Bodies with no {@code data} key are returned as-is: the unversioned infra probes
 * ({@code /health}, {@code /metrics}) are never enveloped, and the v1 error object
 * ({@code {error:{errorCode, message, …}}}) is itself the payload a failing test wants to inspect.
 */
final class V1Body {

    private static final ObjectMapper JSON = new ObjectMapper();

    private V1Body() {}

    /** Parse {@code raw} and unwrap the v1 envelope's {@code data} when present. */
    static JsonNode of(String raw) throws Exception {
        JsonNode node = JSON.readTree(raw);
        return node.has("data") ? node.get("data") : node;
    }

    /** The envelope itself, un-peeled — for tests that assert on {@code metadata}/{@code links}/
     *  {@code permissions} rather than on the resource. */
    static JsonNode envelope(String raw) throws Exception {
        return JSON.readTree(raw);
    }
}
