package com.gamma.control;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Contribution point for the save-time checks an <b>optional</b> module owns for a config component kind. The
 * processor's component save gate ({@code ComponentRoutes.validateKind}) calls every registered validator for the
 * kind it owns, and the reserved-store check for every {@code dataset} and {@code sink} — the authoring route, the
 * bundle writers and the Space Template stager all pass through that one gate. Registered in
 * {@code META-INF/services/com.gamma.control.ComponentKindValidator}.
 *
 * <p>Without the module the kind is still accepted as opaque config (as {@code reconciliation} is), but nothing
 * validates it. A validator signals refusal with {@link IllegalArgumentException} (mapped to 422).
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface ComponentKindValidator {
    /** The component kind this validator owns, e.g. {@code "risk-score"}. */
    String type();

    /** Structure of one {@link #type()} component, with no Space needed. */
    void validate(String id, Map<String, Object> content);

    /** The checks of one {@link #type()} component that need the Space (Schema columns, output-name collisions). */
    void validateInSpace(Path writeRoot, Supplier<Path> dataRoot, String id, Map<String, Object> content);

    /** A {@code dataset} or {@code sink} must not name a store this module reserves for its own outputs. */
    void requireNotReserved(Path writeRoot, String type, String id, Map<String, Object> content);
}
