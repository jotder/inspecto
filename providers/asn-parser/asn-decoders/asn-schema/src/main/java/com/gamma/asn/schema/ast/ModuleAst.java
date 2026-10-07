package com.gamma.asn.schema.ast;

import java.util.List;
import java.util.Map;

/**
 * One parsed ASN.1 module.
 *
 * @param imports symbol name → module name it comes from (resolved across files at compile)
 */
public record ModuleAst(String name,
                        TagDefault tagDefault,
                        Map<String, TypeAst> types,
                        Map<String, String> imports,
                        List<String> exports) {

    public enum TagDefault { EXPLICIT_TAGS, IMPLICIT_TAGS, AUTOMATIC_TAGS }

    public ModuleAst {
        types = Map.copyOf(types);
        imports = Map.copyOf(imports);
        exports = List.copyOf(exports);
    }
}
