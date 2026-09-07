package com.gamma.control;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * An opaque pagination cursor (api-contract-design §7): the ordered keyset of the last row on a page,
 * encoded so clients treat it as a token and never parse it. A list route encodes the sort-key parts of
 * its last returned row into {@code nextCursor}; the next request echoes it back and the route decodes it
 * into the keyset "resume after" marker. Encoding is URL-safe Base64 over the JSON array of the string
 * key parts (JSON so a delimiter can never collide with a key value). Decoding is total: any absent,
 * blank, or malformed cursor yields an empty list ("start from the beginning"), never an error.
 *
 * <p>⚠ <b>Public since EDG-01 cell 6</b> (2026-09-08), for the same reason {@code RouteModule} went public
 * in cell 3a: this is the shared keyset codec that {@code /events}, {@code /jobs/runs} and {@code /objects}
 * all page with, and one of those three ({@code EventRoutes}) now lives in the optional
 * {@code inspecto-events} module, outside this package. It is a codec, not a seam — an out-of-package route
 * module must encode cursors identically or its pages drift, so a second implementation would be a defect.
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class Cursor {
    private Cursor() {}

    /** Encode the ordered key parts of a page's last row into an opaque cursor token. */
    public static String encode(List<String> keyParts) {
        try {
            byte[] json = ApiContext.JSON.writeValueAsBytes(keyParts);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** Decode a cursor token back into its key parts; empty when absent/blank/malformed (start from top). */
    public static List<String> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return List.of();
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor.trim());
            List<?> parts = ApiContext.JSON.readValue(json, List.class);
            return parts.stream().map(String::valueOf).toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
