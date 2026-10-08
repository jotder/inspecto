package com.gamma.ops.tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The keys of a decoded config block that its record does not model (MODULE-REORG-P4-2). They ride through the record
 * so a save that rebuilds the document from {@code toMap()} keeps them; the route refuses every one that is not an
 * author-owned {@code x-} annotation, while a hand-edited file still loads (lenient, as the other config kinds).
 */
public final class Extras {

    private Extras() {}

    public static Map<String, Object> of(Map<String, Object> block, Set<String> modelled) {
        Map<String, Object> out = new LinkedHashMap<>();
        block.forEach((k, v) -> { if (!modelled.contains(k)) out.put(k, v); });
        return out;
    }
}
