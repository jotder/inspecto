package com.gamma.asn.schema;

import java.util.List;

/**
 * One node of the named tree the binder produces.
 *
 * @param name     component/alternative name from the grammar, or the tag path when unknown
 * @param path     legacy dotted tag path, e.g. {@code 1.16.0.3} — synthetic 16/17 segments
 *                 for SEQUENCE OF / SET OF fall out of the universal tag numbers naturally
 * @param typeName most specific grammar type name, or the builtin kind
 * @param value    decoded string for leaves, null for constructed nodes
 */
public record NamedNode(String name, String path, String typeName, String value,
                        List<NamedNode> children, CompiledType type) {

    public NamedNode {
        children = List.copyOf(children);
    }

    /** Compiled type unknown (hex-fallback nodes). */
    public NamedNode(String name, String path, String typeName, String value,
                     List<NamedNode> children) {
        this(name, path, typeName, value, children, null);
    }

    public boolean leaf() {
        return children.isEmpty() && value != null;
    }

    /** First child with the given name, else null. */
    public NamedNode child(String childName) {
        for (NamedNode c : children) {
            if (c.name().equals(childName)) {
                return c;
            }
        }
        return null;
    }
}
