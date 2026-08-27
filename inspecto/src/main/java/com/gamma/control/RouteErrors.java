package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * The control plane's shared domain-exception → HTTP-status mappers. Each method replaces a catch
 * chain that used to be hand-repeated across route classes; pick the one whose mapping matches the
 * handler's contract exactly — they differ deliberately (422 means "understood but illegal here",
 * 400 means "malformed ask").
 */
final class RouteErrors {

    private RouteErrors() {}

    /** The engine's fail-closed lookup signals: absent target → 404, unknown kind/argument → 400. */
    static <T> T mapErrors(Supplier<T> body) {
        try {
            return body.get();
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }
    }

    /** Case-workflow operations: unknown id → 404, illegal state (closed/merged/non-CASE) → 422,
     *  malformed request → 400. */
    static <T> T mapCaseErrors(Supplier<T> body) {
        try {
            return body.get();
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalStateException illegal) {
            throw new ApiException(422, illegal.getMessage());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }
    }

    /** Component previews: bad config → 400, an engine parse/SQL failure → 422 ("preview failed"). */
    static <T> T mapPreviewErrors(PreviewSupplier<T> body) {
        try {
            return body.get();
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (SQLException | IOException e) {
            throw new ApiException(422, "preview failed: " + e.getMessage());
        }
    }

    /** {@code store.exists} for {@code type}, mapping an unsafe id (e.g. containing {@code ..}) to 422
     *  rather than letting {@link IllegalArgumentException} escape to the generic 500 handler. */
    static boolean exists(ComponentStore store, String type, String id) {
        try {
            return store.exists(type, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    /** The stored content for {@code type}/{@code id} — absent → 404 naming {@code label}, unsafe id → 422. */
    static Map<String, Object> existing(ComponentStore store, String type, String label, String id) {
        try {
            return store.get(type, id).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new ApiException(404, label + " '" + id + "' not found"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    /** A preview computation — the engine surfaces parse/SQL trouble as checked exceptions. */
    @FunctionalInterface
    interface PreviewSupplier<T> {
        T get() throws SQLException, IOException;
    }
}
