package com.gamma.workflow;

import java.util.Locale;

/**
 * The kind of {@link OperationalObject} — Layer 2 of the Operational Intelligence Platform
 * ({@code docs/superpowers/specs/2026-06-13-operational-intelligence-roadmap.md}). Unlike the
 * immutable {@code EVENT} layer (Phase 1), these are <b>mutable</b> objects with a lifecycle, so they
 * live in a table store rather than rolling Parquet (§0 of the roadmap).
 *
 * <p>One object table is keyed by this type: {@link #INCIDENT} (Phase 3), {@link #CASE} (Phase 4) and {@link #TASK}
 * share the same table and machinery, so later phases add lifecycle/links, not storage.
 *
 * <p>There is no {@code ALERT} value: Alerts left the object store in MODULE-REORG-P7 and live in the Alert-owned
 * {@code AlertStore}. A deployed Space's object table may still hold rows of type {@code ALERT} (or of any type this
 * build does not know); a store loads such a row <b>inert</b> - listed and readable, never mutated, rewritten or
 * dropped - see {@code OperationalObject#inert}.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public enum ObjectType {
    INCIDENT, CASE, TASK;

    /** Parse a stored type name; {@code null} when it names no type of this build (the caller loads the row inert). */
    public static ObjectType tryOf(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parse a type name case-insensitively. {@code null}/blank returns {@code null} ("no constraint",
     * for query filters); an unrecognised non-blank value throws {@link IllegalArgumentException} so
     * callers can surface a 400 rather than silently matching everything.
     */
    public static ObjectType of(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown object type '" + s + "' (expected one of "
                    + java.util.Arrays.toString(values()) + ")");
        }
    }
}
