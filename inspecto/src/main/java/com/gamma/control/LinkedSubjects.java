package com.gamma.control;

import com.gamma.spi.OptionalSpi;
import com.gamma.spi.http.ApiContext;

import java.util.List;
import java.util.Optional;

/**
 * The installed {@link LinkedSubjectProvider}s, read once per JVM through {@link OptionalSpi} (fail-soft: an absent or
 * unloadable module contributes nothing). {@link #of} answers the provider for a kind WHEN it is available in the
 * request's Space - "no provider" and "provider whose engine is down" are the same answer, because both mean there is
 * no Incident or Case to raise an Action Request from.
 */
public final class LinkedSubjects {

    private static volatile List<LinkedSubjectProvider> loaded;

    private LinkedSubjects() {
    }

    /** Every installed provider. */
    public static List<LinkedSubjectProvider> all() {
        List<LinkedSubjectProvider> l = loaded;
        if (l == null) loaded = l = List.copyOf(OptionalSpi.all(LinkedSubjectProvider.class));
        return l;
    }

    /** The provider for {@code kind} that is running in {@code api}'s Space; empty when none is. */
    public static Optional<LinkedSubjectProvider> of(ApiContext api, String kind) {
        return all().stream().filter(p -> p.kind().equals(kind)).filter(p -> p.available(api)).findFirst();
    }
}
