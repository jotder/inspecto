package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This module's copy of core's test-only v1-envelope unwrapper (EDG-01 cell 7, 2026-09-08).
 *
 * <p>Core's {@code V1Body} lives in {@code inspecto/src/test} and is published to nothing, so a test moved
 * into an optional module cannot see it — the trap that broke cell 4's and cell 6's builds, each time
 * appearing as a bare "cannot find symbol". Cells 4 and 6 solved it with a small local {@code json()}
 * helper per file, which was right for one or two files.
 *
 * <p>⚠ <b>Seven moved test classes use it here</b>, so one shared copy in the same (split) package beats
 * seven private helpers: the moved tests stay byte-identical, which is what makes them reviewable as
 * moves rather than rewrites. The drift risk is bounded — this is the {@code {data, metadata, links, …}}
 * envelope shape, and core's {@code ApiContractTest} pins the served contract independently, so a change
 * that broke this would fail there first.
 */
final class V1Body {

    private static final ObjectMapper JSON = new ObjectMapper();

    private V1Body() {}

    /** Parse {@code raw} and unwrap the v1 envelope's {@code data} when present. */
    static JsonNode of(String raw) throws Exception {
        JsonNode node = JSON.readTree(raw);
        return node.has("data") ? node.get("data") : node;
    }

    /** As {@link #of(String)}, for the byte[]-bodied responses used where a route may answer binary. */
    static JsonNode of(byte[] raw) throws Exception {
        return of(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The envelope itself, un-peeled — for tests asserting on {@code metadata}/{@code links}. */
    static JsonNode envelope(String raw) throws Exception {
        return JSON.readTree(raw);
    }
}
