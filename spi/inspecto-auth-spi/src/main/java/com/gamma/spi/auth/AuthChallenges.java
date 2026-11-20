package com.gamma.spi.auth;

import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The {@code WWW-Authenticate} value a module wants on the host's {@code 401} for one of its routes (AGENT-KIT-1 AK-2a):
 * the MCP endpoint registers {@code /mcp} so an MCP client that is refused learns where to sign in
 * ({@code Bearer resource_metadata="…"}, RFC 9728 §5.1). The host asks {@link #challenge} only when it answers 401; a
 * route nobody registered gets no header, as before. Keyed on the effective route path (API and Space prefixes stripped).
 */
public final class AuthChallenges {

    private AuthChallenges() {}

    private static final Map<String, Function<HttpExchange, String>> BY_PATH = new ConcurrentHashMap<>();

    /** Register the challenge for the exact route path {@code path} (e.g. {@code /mcp}). Last registration wins. */
    public static void register(String path, Function<HttpExchange, String> challenge) {
        BY_PATH.put(path, challenge);
    }

    /** The header value for {@code path}, or {@code null} when none is registered or it yields none. */
    public static String challenge(String path, HttpExchange ex) {
        Function<HttpExchange, String> f = path == null ? null : BY_PATH.get(path);
        if (f == null) return null;
        try {
            return f.apply(ex);
        } catch (RuntimeException e) {
            return null;   // a challenge is advice; it never turns a 401 into a 500
        }
    }
}
