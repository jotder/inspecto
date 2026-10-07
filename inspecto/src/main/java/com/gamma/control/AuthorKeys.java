package com.gamma.control;

import com.gamma.config.spec.AcceptedConfigKeys;
import com.gamma.config.spec.FindingCodes;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The unmodelled-key policy for a writer that persists the config it REBUILDS from a typed record's {@code toMap()}
 * (MODULE-REORG-P4-2): an author-owned {@code x-} key is an annotation and is carried through the save; any other key
 * the record does not model is refused (it would otherwise vanish behind a 200). {@code AlertRule} carries its own
 * extras inside the record; the writers that use this class have no such component, so the route carries them.
 */
final class AuthorKeys {

    private AuthorKeys() {}

    /** Copy every {@code x-} key of {@code from} into {@code into} (never overriding a key already there); returns {@code into}. */
    static Map<String, Object> carry(Map<String, Object> from, Map<String, Object> into) {
        if (from != null)
            from.forEach((k, v) -> { if (k.startsWith(AcceptedConfigKeys.EXTENSION_PREFIX)) into.putIfAbsent(k, v); });
        return into;
    }

    /** The keys of {@code body} that are neither {@code modelled} nor author-owned ({@code x-}), sorted. */
    static List<String> unknown(Map<String, Object> body, Set<String> modelled) {
        return body == null ? List.of() : body.keySet().stream()
                .filter(k -> !modelled.contains(k) && !k.startsWith(AcceptedConfigKeys.EXTENSION_PREFIX)).sorted().toList();
    }

    /** The refusal text (starts with {@code ERR_UNKNOWN_CONFIG_KEY}) naming the keys; {@code null} when there are none. */
    static String refusal(String what, Map<String, Object> body, Set<String> modelled) {
        List<String> unknown = unknown(body, modelled);
        return unknown.isEmpty() ? null : FindingCodes.ERR_UNKNOWN_CONFIG_KEY + ": " + what + " key(s) " + unknown
                + " are not part of " + what + " and would not be kept; remove them, or prefix an annotation with '"
                + AcceptedConfigKeys.EXTENSION_PREFIX + "' to keep it";
    }

    /** {@link #refusal} thrown as a 422 {@link ApiException}. */
    static void requireModelled(String what, Map<String, Object> body, Set<String> modelled) {
        String refusal = refusal(what, body, modelled);
        if (refusal != null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refusal);
    }
}
