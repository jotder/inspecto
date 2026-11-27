package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
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
 * <p>⚠ <b>Public since EDG-01 cell 7</b> (2026-09-08), for the same reason the route SPI went public in
 * cell 3a: the {@code /objects}, {@code /notes} and {@code /tags} route families moved into the optional
 * {@code inspecto-ops} module and still need this core helper. Widening the visibility was the honest
 * option — copying it into the module would have left two implementations of one contract to drift.
 */
public final class RouteErrors {

    private RouteErrors() {}

    /** {@code bad} as a 422 {@code CONFIG_VALIDATION_FAILED}, keeping its finding code when it is a
     *  {@link com.gamma.config.spec.CodedArgumentException} (REFUSAL-CODES-1). */
    public static ApiException unprocessable(IllegalArgumentException bad) {
        return unprocessable(bad, bad.getMessage());
    }

    /** {@link #unprocessable(IllegalArgumentException)} with the route's own wording of the message. */
    public static ApiException unprocessable(IllegalArgumentException bad, String message) {
        return bad instanceof com.gamma.config.spec.CodedArgumentException c
                ? new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message, c.code(), c.fieldPath())
                : new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }

    /** The response body of a refusal: {@code {"error": message}}, plus its one coded finding when it has a
     *  finding code — the v1 envelope lifts it to {@code error.details.findings[0]} (REFUSAL-CODES-1). */
    public static Map<String, Object> body(ApiException refused) {
        if (refused.findingCode == null) return Map.of("error", refused.getMessage());
        return Map.of("error", refused.getMessage(), "findings", java.util.List.of(
                new com.gamma.config.spec.Finding(com.gamma.config.spec.Severity.ERROR, refused.fieldPath,
                        refused.getMessage(), refused.findingCode, null)));
    }

    /** The engine's fail-closed lookup signals: absent target → 404, unknown kind/argument → 400. */
    public static <T> T mapErrors(Supplier<T> body) {
        try {
            return body.get();
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, bad.getMessage());
        }
    }

    /** Case-workflow operations: unknown id → 404, illegal state (closed/merged/non-CASE) → 422,
     *  malformed request → 400. */
    public static <T> T mapCaseErrors(Supplier<T> body) {
        try {
            return body.get();
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        } catch (com.gamma.objects.LegalHoldException held) {   // LEGAL-HOLD-SCOPE-1: a conflict with the hold, not a bad request
            throw new ApiException(409, ErrorCodes.CONFLICT, held.getMessage());
        } catch (IllegalStateException illegal) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, illegal.getMessage());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, bad.getMessage());
        }
    }

    /** Component previews: bad config → 400, an engine parse/SQL failure → 422 ("preview failed"). */
    public static <T> T mapPreviewErrors(com.gamma.spi.http.ApiContext api, PreviewSupplier<T> body) {
        try {
            return body.get();
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, e.getMessage());
        } catch (SQLException | IOException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "preview failed: " + ServerFaults.sqlMessage(e.getMessage(), api));
        }
    }

    /** {@code store.exists} for {@code type}, mapping an unsafe id (e.g. containing {@code ..}) to 422
     *  rather than letting {@link IllegalArgumentException} escape to the generic 500 handler. */
    public static boolean exists(ComponentStore store, String type, String id) {
        try {
            return store.exists(type, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    /** The stored content for {@code type}/{@code id} — absent → 404 naming {@code label}, unsafe id → 422. */
    public static Map<String, Object> existing(ComponentStore store, String type, String label, String id) {
        try {
            return store.get(type, id).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, label + " '" + id + "' not found"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    /** A preview computation — the engine surfaces parse/SQL trouble as checked exceptions. */
    @FunctionalInterface
    interface PreviewSupplier<T> {
        T get() throws SQLException, IOException;
    }
}
