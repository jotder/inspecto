package com.gamma.spi.auth;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * Which Space an embed viewer token belongs to (LA-EMBED-VIEW-1 remainder a). The host serves the viewer page without a user and
 * must put THAT Space's {@code embed.frame_ancestors} on the response, never the union over every attached Space; only the module
 * that holds the grants can tell, so it registers a resolver here and the host asks it. No resolver, or a token that does not
 * resolve to a live grant, answers empty and the host frames the page for nobody.
 */
public final class EmbedShellSpaces {

    private EmbedShellSpaces() {}

    private static volatile Function<String, Optional<Path>> resolver = t -> Optional.empty();

    /** Register the resolver: a presented viewer token to the root of the Space whose live grant it is. */
    public static void register(Function<String, Optional<Path>> r) {
        resolver = r == null ? t -> Optional.empty() : r;
    }

    /** The Space root of a LIVE grant for {@code token}; empty for anything else, and on any failure. */
    public static Optional<Path> spaceOf(String token) {
        try {
            return resolver.apply(token);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
