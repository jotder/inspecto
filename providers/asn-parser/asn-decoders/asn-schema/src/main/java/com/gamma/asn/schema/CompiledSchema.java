package com.gamma.asn.schema;

import java.util.Map;

/**
 * The resolved schema: a root type plus every named type reachable from it.
 * (Stable text serialization — the successor of the legacy .csv tag map — is a follow-up;
 * see REDESIGN.md §4.3.)
 */
public record CompiledSchema(CompiledType root, Map<String, CompiledType> types) {

    public CompiledSchema {
        types = Map.copyOf(types);
    }
}
