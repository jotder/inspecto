package com.gamma.spi.auth;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry of route prefixes that carry their OWN credential in the request path (a share or embed token) and so are exempt
 * from platform authentication - the extension point that replaces a hard-coded {@code startsWith} per feature in the host
 * (LA-EMBED-VIEW-1). A module registers its prefix when its routes register; the host asks {@link #isSelfVerifying}.
 *
 * <p>It is also where the credential is kept OUT of every record of a request: {@link #redact} replaces everything after a
 * registered prefix with a fixed marker, and the host and the audit trail pass every request path through it before it reaches a
 * log line, an audit event or a response link. A token in a URL path would otherwise land in each of them.
 */
public final class PublicPaths {

    private PublicPaths() {}

    /** What stands in for a path's credential in a record. */
    public static final String REDACTED = "[redacted]";

    private static final List<String> PREFIXES = new CopyOnWriteArrayList<>();

    /**
     * Register {@code prefix} (of the form {@code /public/<name>/}, e.g. {@code /public/investigations/}) as a self-verifying public
     * path whose remainder is a credential. Idempotent.
     */
    public static void registerSelfVerifying(String prefix) {
        if (prefix == null || !prefix.startsWith("/public/") || !prefix.endsWith("/") || prefix.length() < 10)
            throw new IllegalArgumentException("a self-verifying prefix is /public/<name>/ : " + prefix);
        if (!PREFIXES.contains(prefix)) PREFIXES.add(prefix);
    }

    /** Whether {@code path} (the effective path, API and Space prefixes stripped) is under a registered prefix. */
    public static boolean isSelfVerifying(String path) {
        if (path == null) return false;
        for (String p : PREFIXES) if (path.startsWith(p)) return true;
        return false;
    }

    /** {@code path} with everything after a registered prefix replaced by {@link #REDACTED}; any other path is returned unchanged. */
    public static String redact(String path) {
        if (path == null) return null;
        for (String p : PREFIXES) {
            int at = path.indexOf(p);   // also after a /api/v1 or /spaces/{id} prefix, which a log line may still carry
            if (at >= 0) return path.substring(0, at + p.length()) + REDACTED;
        }
        return path;
    }
}
