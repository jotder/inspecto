package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.gamma.access.WriteGates;

/**
 * The seam an optional module uses to REGISTER a Dataset over a store it owns — through the SAME validated save path a
 * hand-authored Dataset takes ({@code POST /components/dataset}: {@link ComponentRoutes#createComponent} — sharing
 * envelope, {@link ComponentRoutes#validateKind}, the reserved-prefix check, the maker-checker hold, the versioned
 * store write), never by writing the registry file itself.
 *
 * <p>First user: {@code POST /entity-lists/{id}/register-dataset} (ASSURE-ENTITY-LISTS-RESIDUALS-1 (3)). The caller
 * does its own capability gate; this class owns the Dataset half.
 *
 * <p>⚠ Under an approval policy for kind {@code dataset} the registration is REFUSED (409), not held: a held change
 * is replayed through the route that recorded it, and {@code PendingChanges.REPLAYABLE} is pinned to the routes that
 * hold in their own source file. Author the Dataset on its own so it can be approved.
 */
public final class DatasetRegistration {

    /** One registration at a time per JVM, so the exists-check and the write of two callers cannot interleave. */
    private static final Object LOCK = new Object();

    private DatasetRegistration() {}

    /** Whether this Space already has a Dataset component {@code id}. */
    public static boolean exists(ApiContext api, String id) {
        return new com.gamma.pipeline.ComponentStore(WriteGates.requireWriteRoot(api, "dataset registration")
                .resolve("registry")).exists("dataset", id);
    }

    /**
     * Create the Dataset {@code id} with {@code content} (e.g. {@code {physicalRef, description}}).
     *
     * @return the stored component as {@code POST /components/dataset} answers it
     * @throws ApiException 409 when a Dataset {@code id} exists or the approval policy governs Datasets; 422 when the
     *                      content fails the Dataset gate; 503 without a write root
     */
    public static Map<String, Object> create(ApiContext api, HttpExchange ex, String id, Map<String, Object> content)
            throws IOException {
        PendingChanges.holdRefusing(api, List.of("dataset"), "registering a Dataset is an authoring write");
        Map<String, Object> body = new LinkedHashMap<>(content);
        body.put("id", id);
        synchronized (LOCK) {
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) new ComponentRoutes().createComponent(api, ex, "dataset", body);
            return doc;
        }
    }
}
