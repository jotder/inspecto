package com.gamma.asn.schema;

import com.gamma.asn.core.TagClass;
import com.gamma.asn.schema.ast.BuiltinKind;
import com.gamma.asn.schema.ast.ComponentAst;
import com.gamma.asn.schema.ast.ModuleAst;
import com.gamma.asn.schema.ast.TypeAst;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves parsed modules into a tag-annotated {@link CompiledSchema}: reference resolution
 * across modules (IMPORTS by name, never line-skipping), IMPLICIT / EXPLICIT / AUTOMATIC
 * tagging, COMPONENTS OF expansion. CHOICE stays a discriminated union — never flattened.
 */
public final class SchemaCompiler {

    private final Map<String, Def> defs = new HashMap<>();
    private final Map<String, CompiledType> memo = new LinkedHashMap<>();
    private final List<String> warnings; // null unless compileLenient(warnings)
    private final List<Diagnostic> diagnostics; // null unless compileLenient(diagnostics)
    private final boolean simplifyAliases;

    private record Def(ModuleAst module, TypeAst ast) {
    }

    private SchemaCompiler(List<ModuleAst> modules, List<String> warnings,
                           List<Diagnostic> diagnostics, boolean simplifyAliases) {
        this.warnings = warnings;
        this.diagnostics = diagnostics;
        this.simplifyAliases = simplifyAliases;
        for (ModuleAst m : modules) {
            for (Map.Entry<String, TypeAst> e : m.types().entrySet()) {
                defs.putIfAbsent(e.getKey(), new Def(m, e.getValue()));
            }
        }
    }

    public static CompiledSchema compile(List<ModuleAst> modules, String rootTypeName) {
        SchemaCompiler c = new SchemaCompiler(modules, null, null, false);
        CompiledType root = c.compileNamed(rootTypeName);
        return new CompiledSchema(root, c.memo);
    }

    /**
     * Lenient compile for the hand-doctored grammars: an unresolved type reference decays
     * to OCTET STRING (hex fallback at decode time) and is reported in {@code warningsOut}
     * instead of failing the whole schema.
     */
    public static CompiledSchema compileLenient(List<ModuleAst> modules, String rootTypeName,
                                                List<String> warningsOut) {
        SchemaCompiler c = new SchemaCompiler(modules, warningsOut, null, false);
        CompiledType root = c.compileNamed(rootTypeName);
        return new CompiledSchema(root, c.memo);
    }

    /**
     * Lenient compile with optional alias-chain simplification and structured diagnostics.
     */
    public static CompiledSchema compileLenient(List<ModuleAst> modules, String rootTypeName,
                                                boolean simplifyAliases, List<Diagnostic> out) {
        SchemaCompiler c = new SchemaCompiler(modules, null, out, simplifyAliases);
        CompiledType root = c.compileNamed(rootTypeName);
        return new CompiledSchema(root, c.memo);
    }

    private boolean isLenient() {
        return warnings != null || diagnostics != null;
    }

    private void warn(String message) {
        if (warnings != null) {
            warnings.add(message);
        }
        if (diagnostics != null) {
            diagnostics.add(new Diagnostic(0, 0, "WARNING", message));
        }
    }

    /** Root defaults to the first type assigned in the first module. */
    public static CompiledSchema compile(List<ModuleAst> modules) {
        ModuleAst first = modules.getFirst();
        if (first.types().isEmpty()) {
            throw new Asn1ParseException("module " + first.name() + " defines no types");
        }
        return compile(modules, first.types().keySet().iterator().next());
    }

    // ---------------- named types (memoized; recursion-safe) ----------------

    private CompiledType compileNamed(String name) {
        CompiledType existing = memo.get(name);
        if (existing != null) {
            return existing;
        }
        Def def = defs.get(name);
        if (def == null) {
            if (!isLenient()) {
                throw new Asn1ParseException("unresolved type reference '" + name + "'");
            }
            return decayToOctetString(name, "unresolved type reference '" + name + "' — decoding as OCTET STRING");
        }
        List<String> aliasChain = new ArrayList<>();
        aliasChain.add(name);
        if (simplifyAliases && def.ast() instanceof TypeAst.Ref) {
            Set<String> visited = new HashSet<>();
            visited.add(name);
            while (def.ast() instanceof TypeAst.Ref(String nextName)) {
                if (!visited.add(nextName)) {
                    if (!isLenient()) {
                        throw new Asn1ParseException("cyclic alias reference involving '" + nextName + "'");
                    }
                    return decayToOctetString(name, "cyclic alias reference involving '" + nextName + "' — decoding as OCTET STRING");
                }
                Def nextDef = defs.get(nextName);
                if (nextDef == null) {
                    if (!isLenient()) {
                        throw new Asn1ParseException("unresolved type reference '" + nextName + "'");
                    }
                    return decayToOctetString(name, "unresolved type reference '" + nextName + "' — decoding as OCTET STRING");
                }
                aliasChain.add(nextName);
                def = nextDef;
            }
        }
        CompiledType placeholder = new CompiledType();
        placeholder.nameChain.addAll(aliasChain);
        memo.put(name, placeholder);
        fill(placeholder, def.ast(), def.module());
        return placeholder;
    }

    private CompiledType decayToOctetString(String name, String warningMessage) {
        if (warnings != null) {
            warnings.add(warningMessage);
        }
        if (diagnostics != null) {
            diagnostics.add(new Diagnostic(0, 0, "ERROR", warningMessage));
        }
        CompiledType fallback = new CompiledType();
        fallback.nameChain.add(name);
        fallback.kind = CompiledType.Kind.PRIMITIVE;
        fallback.primitive = BuiltinKind.OCTET_STRING;
        fallback.tag = TagKey.universal(BuiltinKind.OCTET_STRING.universalTag());
        memo.put(name, fallback);
        return fallback;
    }

    private CompiledType compileType(TypeAst ast, ModuleAst module) {
        if (ast instanceof TypeAst.Ref(String name)) {
            return compileNamed(name);
        }
        CompiledType out = new CompiledType();
        fill(out, ast, module);
        return out;
    }

    private void fill(CompiledType out, TypeAst ast, ModuleAst module) {
        switch (ast) {
            case TypeAst.Ref(String name) -> {
                CompiledType target = compileNamed(name);
                out.kind = target.kind();
                out.tag = target.tag();
                List<String> chain = new ArrayList<>(out.nameChain);
                for (String n : target.nameChain()) {
                    if (!chain.contains(n)) {
                        chain.add(n);
                    }
                }
                out.nameChain = chain;
                out.primitive = target.primitive();
                out.valueNames = target.valueNames();
                out.inner = target.inner();
                out.components = target.components();
                out.constraint = target.constraint();
            }
            case TypeAst.Constrained(TypeAst inner, String constraintText) -> {
                fill(out, inner, module);
                out.constraint = constraintText;
            }
            case TypeAst.Tagged tagged -> fillTagged(out, tagged, module);
            case TypeAst.SequenceType(List<ComponentAst> comps) -> {
                out.kind = CompiledType.Kind.SEQUENCE;
                out.tag = TagKey.universal(BuiltinKind.SEQUENCE.universalTag());
                out.components = compileComponents(comps, module);
            }
            case TypeAst.SetType(List<ComponentAst> comps) -> {
                out.kind = CompiledType.Kind.SET;
                out.tag = TagKey.universal(BuiltinKind.SET.universalTag());
                out.components = compileComponents(comps, module);
            }
            case TypeAst.ChoiceType(List<ComponentAst> alts) -> {
                out.kind = CompiledType.Kind.CHOICE;
                out.tag = null;
                out.components = compileComponents(alts, module);
            }
            case TypeAst.SequenceOf(TypeAst element) -> {
                out.kind = CompiledType.Kind.SEQUENCE_OF;
                out.tag = TagKey.universal(BuiltinKind.SEQUENCE.universalTag());
                out.inner = compileType(element, module);
            }
            case TypeAst.SetOf(TypeAst element) -> {
                out.kind = CompiledType.Kind.SET_OF;
                out.tag = TagKey.universal(BuiltinKind.SET.universalTag());
                out.inner = compileType(element, module);
            }
            case TypeAst.Enumerated(BuiltinKind kind, Map<String, Long> named) -> {
                out.kind = CompiledType.Kind.PRIMITIVE;
                out.primitive = kind;
                out.tag = TagKey.universal(kind.universalTag());
                Map<Long, String> byNumber = new LinkedHashMap<>();
                named.forEach((n, v) -> byNumber.putIfAbsent(v, n));
                out.valueNames = byNumber;
            }
            case TypeAst.Builtin(BuiltinKind kind) -> {
                if (kind == BuiltinKind.ANY) {
                    out.kind = CompiledType.Kind.ANY;
                    out.tag = null;
                } else {
                    out.kind = CompiledType.Kind.PRIMITIVE;
                    out.primitive = kind;
                    out.tag = TagKey.universal(kind.universalTag());
                }
            }
        }
    }

    private void fillTagged(CompiledType out, TypeAst.Tagged tagged, ModuleAst module) {
        TagKey key = new TagKey(tagged.tagClass(), tagged.number());
        boolean explicit = isExplicit(tagged, module);
        if (explicit) {
            out.kind = CompiledType.Kind.EXPLICIT_WRAPPER;
            out.tag = key;
            out.inner = compileType(tagged.inner(), module);
        } else {
            CompiledType inner = compileType(tagged.inner(), module);
            CompiledType retagged = inner.withTag(key);
            out.kind = retagged.kind();
            out.tag = key;
            List<String> chain = new ArrayList<>(out.nameChain);
            for (String n : retagged.nameChain()) {
                if (!chain.contains(n)) {
                    chain.add(n);
                }
            }
            out.nameChain = chain;
            out.primitive = retagged.primitive();
            out.valueNames = retagged.valueNames();
            out.inner = retagged.inner();
            out.components = retagged.components();
            out.constraint = retagged.constraint();
        }
    }

    private boolean isExplicit(TypeAst.Tagged tagged, ModuleAst module) {
        return switch (tagged.mode()) {
            case EXPLICIT -> true;
            case IMPLICIT -> false;
            // X.680: with IMPLICIT/AUTOMATIC TAGS the tag is implicit, except an untagged
            // CHOICE or ANY has no tag of its own to replace — those stay explicit.
            case MODULE_DEFAULT -> module.tagDefault() == ModuleAst.TagDefault.EXPLICIT_TAGS
                    || isUntaggedChoiceOrAny(tagged.inner(), module, new HashSet<>());
        };
    }

    private boolean isUntaggedChoiceOrAny(TypeAst ast, ModuleAst module, Set<String> visited) {
        return switch (ast) {
            case TypeAst.ChoiceType c -> true;
            case TypeAst.Builtin(BuiltinKind kind) -> kind == BuiltinKind.ANY;
            case TypeAst.Constrained(TypeAst inner, String ignored) ->
                    isUntaggedChoiceOrAny(inner, module, visited);
            case TypeAst.Ref(String name) -> {
                if (!visited.add(name)) {
                    yield false;
                }
                Def def = defs.get(name);
                yield def != null && isUntaggedChoiceOrAny(def.ast(), def.module(), visited);
            }
            default -> false;
        };
    }

    private List<CompiledType.Component> compileComponents(List<ComponentAst> comps, ModuleAst module) {
        List<CompiledType.Component> out = new ArrayList<>();
        boolean automatic = module.tagDefault() == ModuleAst.TagDefault.AUTOMATIC_TAGS
                && comps.stream().noneMatch(c -> !c.componentsOf() && c.type() instanceof TypeAst.Tagged);
        long autoTag = 0;
        for (ComponentAst comp : comps) {
            if (comp.componentsOf()) {
                CompiledType referenced = compileType(comp.type(), module);
                if (referenced.kind() != CompiledType.Kind.SEQUENCE
                        && referenced.kind() != CompiledType.Kind.SET) {
                    throw new Asn1ParseException("COMPONENTS OF must reference a SEQUENCE/SET, got "
                            + referenced.typeName());
                }
                out.addAll(referenced.components());
                continue;
            }
            TypeAst effective = comp.type();
            if (automatic) {
                effective = new TypeAst.Tagged(TagClass.CONTEXT, autoTag++,
                        TypeAst.TagMode.MODULE_DEFAULT, effective);
            }
            CompiledType compiled = compileType(effective, module);
            out.add(new CompiledType.Component(comp.name(), compiled, comp.optional() || comp.defaultValue() != null));
        }
        return out;
    }
}
