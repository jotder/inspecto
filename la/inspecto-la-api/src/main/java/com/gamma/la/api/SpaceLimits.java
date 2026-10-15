package com.gamma.la.api;

import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.entitystore.LaLimits;

import java.nio.file.Path;

/**
 * The Space's Link Analysis limits in force at a route's code site ({@code LA-CONFIG-LIMITS-1} P3): the value the Space states
 * in its {@code link-analysis.toon}, else the catalogue default ({@link LaLimit}). Every whole-number limit these routes apply
 * fits an {@code int} (each item's range says so), hence the narrowing here.
 */
final class SpaceLimits {

    private SpaceLimits() { }

    /** The limits in force for the Space whose config root is {@code writeRoot} ({@code null} = the catalogue defaults). */
    static LaLimitValues of(Path writeRoot) {
        return LaLimits.forSpace(writeRoot);
    }

    /** One limit's value in force. */
    static int get(LaLimitValues v, LaLimit l) {
        return Math.toIntExact(v.get(l));
    }

    /** One limit's value in force for the Space whose config root is {@code writeRoot}. */
    static int get(Path writeRoot, LaLimit l) {
        return get(of(writeRoot), l);
    }
}
