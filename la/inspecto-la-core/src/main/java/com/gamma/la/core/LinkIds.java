package com.gamma.la.core;

import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The wire id of one <b>link</b> in a Working Set (D-U9 remainder, operator 2026-09-30): the evaluator's existing de
 * facto key {@code source␀target␀kind} ({@link InvestigationEvaluator}'s {@code links} map key — directional, one
 * Dataset per Investigation so pair + kind is unique), ENCODED as {@code lk.} + unpadded base64url of its UTF-8 bytes.
 *
 * <ul>
 *   <li><b>Reversible</b> — {@link #decode} gives back the three parts exactly; nothing is looked up.</li>
 *   <li><b>URL-safe</b> — {@code [A-Za-z0-9_-]} after the prefix, so it travels in a path, query or JSON unescaped;
 *       the NUL separator cannot occur in a column value read through JSON text, and needs no escaping rule (a
 *       {@code |}-delimited string would — ids may contain {@code |}).</li>
 *   <li><b>Stable</b> — a pure function of the key, so the same link has the same id in every response and replay.</li>
 *   <li><b>Masking</b> (D-U6) — ids are minted at RENDER time from the values the caller is shown: when an endpoint is
 *       masked, the id encodes its {@code masked:<hex>} token, never the raw value, so a masked id decodes to the
 *       pseudonyms only. Sent back, each token is resolved to its entity by the Investigation's own
 *       {@code EntityMasking} (inspecto-la-api) before the key is looked up.</li>
 * </ul>
 * ⚠ Inherited, not introduced: the evaluator keys a null kind as the literal {@code "null"}, so an absent kind and a
 * kind column holding the text {@code null} are one link — the id reproduces the key, it does not split it.
 */
public final class LinkIds {

    public static final String PREFIX = "lk.";
    /** 3 × the per-id cap (512) + 2 separators, base64-expanded, plus the prefix. */
    public static final int MAX_LENGTH = PREFIX.length() + (4 * (3 * 512 + 2) + 2) / 3;

    private LinkIds() {}

    /** The evaluator's key for a link. */
    public static String key(String source, String target, String kind) {
        return source + '\u0000' + target + '\u0000' + kind;
    }

    public static String encode(String source, String target, String kind) {
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key(source, target, kind).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Stamps {@code linkId} on every link-shaped map ({@code source} + {@code target}) inside a list under a
     * {@code links} or {@code linkAnnotations} key of an ALREADY MASKED response tree, in place. Run after masking so
     * the id encodes what the caller sees.
     */
    @SuppressWarnings("unchecked")
    public static Object stamp(Object node) {
        if (node instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                boolean linkList = "links".equals(e.getKey()) || "linkAnnotations".equals(e.getKey());
                if (linkList && e.getValue() instanceof List<?> l) {
                    for (Object o : l) if (o instanceof Map<?, ?> lm) stampOne((Map<String, Object>) lm);
                } else stamp(e.getValue());
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) stamp(o);
        }
        return node;
    }

    public static void stampOne(Map<String, Object> link) {
        if (link.get("source") instanceof String s && link.get("target") instanceof String t)
            link.put("linkId", encode(s, t, String.valueOf(link.get("kind"))));
    }

    /** {@code [source, target, kind]} of a wire id; 422 when it is not one this encoding produced. */
    public static List<String> decode(String id) {
        String bad = "every link id must be an 'lk.' id as a Working Set's links relation serves it (linkId)";
        if (id == null || !id.startsWith(PREFIX) || id.length() > MAX_LENGTH)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad);
        String key;
        try {
            key = new String(Base64.getUrlDecoder().decode(id.substring(PREFIX.length())), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad);
        }
        String[] parts = key.split("\u0000", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || !encode(parts[0], parts[1], parts[2]).equals(id))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad);
        return List.of(parts[0], parts[1], parts[2]);
    }
}
