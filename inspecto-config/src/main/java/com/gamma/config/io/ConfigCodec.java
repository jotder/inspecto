package com.gamma.config.io;

import com.gamma.api.PublicApi;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.util.Map;

/**
 * The canonical {@code .toon} encode/decode for configuration maps.
 *
 * <ul>
 *   <li>{@link #toMap(String)} — <b>strict</b> decode. There is one decode and it is strict: it is
 *       {@link ToonHelper#decode(String)}, the single seam every TOON reader goes through, so a tabular row
 *       whose width disagrees with its header is refused naming the line, the table, both counts and the
 *       fix ({@code TOON-UNQUOTED-DECIMAL-SKIPS-PIPELINE-1}). There is no lenient mode: non-strict JToon
 *       silently truncates or pads such a row ({@code CONFIGCODEC-LENIENT-IS-STRICT-1}). ⚠ JToon has
 *       no comment syntax: a raw {@code #} line is parsed as TOON, never skipped, and above a block it
 *       silently drops what follows;
 *   <li>{@link #toToon(Object)} — canonical encode. {@code JToon.encode} never emits comments and always
 *       quotes values that need it, so anything this codec produces decodes back through {@link #toMap}.
 * </ul>
 *
 * <p>The JSON wire form needed by the Control API is produced by the API's Jackson mapper directly
 * on the decoded {@code Map} / the spec records, so it is not duplicated here.
 */
@PublicApi(since = "4.0.0")
public final class ConfigCodec {

    private ConfigCodec() {}

    /** Strict decode of {@code .toon} text to its top-level map — see {@link ToonHelper#decode(String)}. */
    public static Map<String, Object> toMap(String toon) {
        return ToonHelper.decode(toon);
    }

    /** Canonical, comment-free encode of a config map (or any JToon-encodable value). */
    public static String toToon(Object value) {
        return JToon.encode(value);
    }
}
