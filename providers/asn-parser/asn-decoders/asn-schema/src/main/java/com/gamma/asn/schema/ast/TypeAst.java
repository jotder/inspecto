package com.gamma.asn.schema.ast;

import com.gamma.asn.core.TagClass;

import java.util.List;
import java.util.Map;

/** Parsed (unresolved) type notation. Compilation into tag-annotated form happens later. */
public sealed interface TypeAst {

    /** Reference to a named type in this or an imported module. */
    record Ref(String name) implements TypeAst {
    }

    record Builtin(BuiltinKind kind) implements TypeAst {
    }

    /** ENUMERATED / INTEGER with named numbers; names are preserved, not coerced to int. */
    record Enumerated(BuiltinKind kind, Map<String, Long> namedValues) implements TypeAst {
        public Enumerated {
            namedValues = Map.copyOf(namedValues);
        }
    }

    enum TagMode { IMPLICIT, EXPLICIT, MODULE_DEFAULT }

    record Tagged(TagClass tagClass, long number, TagMode mode, TypeAst inner) implements TypeAst {
    }

    record SequenceType(List<ComponentAst> components) implements TypeAst {
        public SequenceType {
            components = List.copyOf(components);
        }
    }

    record SetType(List<ComponentAst> components) implements TypeAst {
        public SetType {
            components = List.copyOf(components);
        }
    }

    record ChoiceType(List<ComponentAst> alternatives) implements TypeAst {
        public ChoiceType {
            alternatives = List.copyOf(alternatives);
        }
    }

    record SequenceOf(TypeAst element) implements TypeAst {
    }

    record SetOf(TypeAst element) implements TypeAst {
    }

    /** Subtype constraint, parsed and retained verbatim; enforcement is optional/later. */
    record Constrained(TypeAst inner, String constraintText) implements TypeAst {
    }
}
