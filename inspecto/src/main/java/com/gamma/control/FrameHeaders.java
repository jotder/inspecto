package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Frame-embedding headers (LA-EMBED-FRAME-HEADERS-1, slice S5). Two independent switches:
 *
 * <ul>
 *   <li><b>Host-wide frame-deny</b> - {@code -Dcontrol.frameDeny=true} (default OFF) puts {@code Content-Security-Policy: frame-ancestors 'none'}
 *       and {@code X-Frame-Options: DENY} on EVERY response of the host except the embed routes ({@link #isEmbedPath}); read per
 *       request, so a flip needs no restart.</li>
 *   <li><b>The embed viewer page</b> ({@code /embed/...}, the SPA shell) is always served with its own strict policy: no third-party
 *       origin, {@code Referrer-Policy: no-referrer}, no caching, and {@code frame-ancestors} = the origins a Space states in
 *       {@code link-analysis.toon} {@code embed.frame_ancestors} (union of the attached Spaces), else {@code 'none'}.</li>
 * </ul>
 * The embed routes are never touched by the host-wide flag: they state their own framing policy.
 */
final class FrameHeaders {

    static final String FLAG = "control.frameDeny";

    private FrameHeaders() {}

    static boolean hostDeny() {
        return Boolean.getBoolean(FLAG);
    }

    /** The raw request path without the {@code /api[/v1]} prefix. */
    private static String strip(String raw) {
        for (String p : new String[] {"/api/v1", "/api"})
            if (raw.equals(p) || raw.startsWith(p + "/")) return raw.substring(p.length());
        return raw;
    }

    /** The viewer page and the public read route: the two embed surfaces, which carry their own framing policy. */
    static boolean isEmbedPath(String rawPath) {
        String p = strip(rawPath);
        return p.startsWith("/embed/") || p.startsWith("/public/investigations/");
    }

    static void denyFraming(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", "frame-ancestors 'none'");
        h.set("X-Frame-Options", "DENY");
    }

    /** The shell's CSP: same-origin only, no script/style from anywhere else, no form. */
    static String shellPolicy(List<String> frameOrigins) {
        return "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
                + "connect-src 'self'; base-uri 'self'; form-action 'none'; frame-ancestors "
                + (frameOrigins.isEmpty() ? "'none'" : String.join(" ", frameOrigins));
    }

    static void embedShell(HttpExchange ex, List<String> frameOrigins) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", shellPolicy(frameOrigins));
        if (frameOrigins.isEmpty()) h.set("X-Frame-Options", "DENY");   // X-Frame-Options cannot name an allow-list: the CSP above speaks alone
        else h.remove("X-Frame-Options");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
    }

    private static final Pattern EXTERNAL_LINK = Pattern.compile("<link\\b[^>]*\\bhref\\s*=\\s*[\"']?https?://[^>]*>", Pattern.CASE_INSENSITIVE);
    /** An {@code @font-face} that names another origin: Angular inlines the Google font CSS into the built index, and the policy's {@code font-src 'self'} refuses every one. */
    private static final Pattern EXTERNAL_FONT_FACE = Pattern.compile("@font-face\\s*\\{[^}]*https?://[^}]*\\}",Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_HANDLER = Pattern.compile("\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRINT_MEDIA = Pattern.compile("\\s+media\\s*=\\s*[\"']print[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * The shell page for the viewer: the SPA's {@code index.html} with every link to another origin removed (the viewer makes no
     * third-party call: no font or icon host, not even a preconnect), and every inlined {@code @font-face} that names another origin, and the inline event handler of Angular's deferred stylesheet
     * dropped (the policy would refuse it, so the stylesheet is loaded eagerly instead).
     */
    static byte[] sanitiseShell(byte[] html) {
        String s = new String(html, StandardCharsets.UTF_8);
        s = EXTERNAL_LINK.matcher(s).replaceAll("");
        s = EXTERNAL_FONT_FACE.matcher(s).replaceAll("");   // the viewer falls back to system fonts: no refused request, a clean console
        if (EVENT_HANDLER.matcher(s).find()) {
            s = EVENT_HANDLER.matcher(s).replaceAll("");
            s = PRINT_MEDIA.matcher(s).replaceAll("");
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
