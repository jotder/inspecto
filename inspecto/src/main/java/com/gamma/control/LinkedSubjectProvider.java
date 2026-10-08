package com.gamma.control;

import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.Optional;

/**
 * What an Action Request is raised FROM (MODULE-REORG-P7): one provider per KIND of linked subject ({@code incident},
 * {@code case}). The Action Request add-on names no object type and reaches no object engine; it resolves,
 * visibility-checks and (for the {@code invoke-api} consequence) opens its subject through this seam. The operational
 * objects module ({@code inspecto-ops}) contributes {@code incident} and {@code case}.
 *
 * <p>Discovered through {@link com.gamma.spi.OptionalSpi} (see {@link LinkedSubjects}): a module that is not installed
 * contributes nothing, and creating an Action Request then answers 503 {@code CAPABILITY_UNAVAILABLE} exactly as it
 * did before the seam existed. A provider must return the SAME {@link #kind()} on every call.
 */
public interface LinkedSubjectProvider {

    /** The lower-case kind this provider serves: the Action Request's {@code incidentId} is kind {@code incident}. */
    String kind();

    /** Whether the engine behind this provider is running in the request's Space (ops installed AND its object engine up). */
    boolean available(ApiContext api);

    /** The subject {@code id} when it exists AND is of this kind; empty otherwise (never a different kind's object). */
    Optional<Linked> resolve(ApiContext api, String id);

    /**
     * Whether the caller may see the subject: its data scope and row policy
     * ({@code AnnotationTargets.objectVisibleTo} in the ops provider). An Action Request is visible exactly when its
     * subject is; invisible reads as absent.
     */
    boolean visibleTo(ApiContext api, HttpExchange ex, String id);

    /**
     * Open a subject of this kind for {@code request.origin()}, or reuse the one already open for it: the active
     * subject whose {@code reuseAttribute} equals {@code reuseValue} under that origin. Idempotent - repeated calls
     * for the same origin return the same id until that subject is resolved. A kind that no consequence may open
     * returns empty (the default).
     */
    default Optional<String> open(ApiContext api, OpenRequest request) {
        return Optional.empty();
    }

    /** A resolved subject: its id, kind and display text (the id when the object summary carries no title). */
    record Linked(String id, String kind, String display) {
    }

    /** {@link #open}'s arguments: {@code origin} scopes the subject (the active-attribute index key). */
    record OpenRequest(String origin, String reuseAttribute, String reuseValue, String title, String detail,
                       String severity, Map<String, String> attributes) {
    }
}
