package com.gamma.asn.schema.ast;

/**
 * One SEQUENCE/SET/CHOICE component.
 *
 * @param componentsOf true for a {@code COMPONENTS OF X} entry — {@code type} is then the
 *                     referenced SEQUENCE whose components get spliced in at compile time
 * @param defaultValue raw default value text, null when absent
 */
public record ComponentAst(String name, TypeAst type, boolean optional,
                           String defaultValue, boolean componentsOf) {

    public static ComponentAst of(String name, TypeAst type, boolean optional, String defaultValue) {
        return new ComponentAst(name, type, optional, defaultValue, false);
    }

    public static ComponentAst componentsOf(TypeAst ref) {
        return new ComponentAst(null, ref, false, null, true);
    }
}
