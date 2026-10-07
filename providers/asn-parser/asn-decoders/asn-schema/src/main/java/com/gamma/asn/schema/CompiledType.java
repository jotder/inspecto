package com.gamma.asn.schema;

import com.gamma.asn.schema.ast.BuiltinKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fully resolved, tag-annotated type node. Mutable only during compilation (named types can
 * be recursive, so the compiler registers a node before filling it); treat as immutable after.
 */
public final class CompiledType {

    public enum Kind { SEQUENCE, SET, CHOICE, SEQUENCE_OF, SET_OF, PRIMITIVE, ANY, EXPLICIT_WRAPPER }

    public record Component(String name, CompiledType type, boolean optional) {
    }

    Kind kind;
    /** Expected outer tag; null for untagged CHOICE / ANY (they match by alternatives / anything). */
    TagKey tag;
    /** Type-reference names from most-specific to base — decoder lookup key chain. */
    List<String> nameChain = new ArrayList<>();
    BuiltinKind primitive;
    /** number → name for ENUMERATED / INTEGER named values. */
    Map<Long, String> valueNames = Map.of();
    /** EXPLICIT_WRAPPER: the wrapped type. SEQUENCE_OF / SET_OF: the element type. */
    CompiledType inner;
    List<Component> components = List.of();
    String constraint;

    public Kind kind() {
        return kind;
    }

    public TagKey tag() {
        return tag;
    }

    public List<String> nameChain() {
        return nameChain;
    }

    public BuiltinKind primitive() {
        return primitive;
    }

    public Map<Long, String> valueNames() {
        return valueNames;
    }

    public CompiledType inner() {
        return inner;
    }

    public List<Component> components() {
        return components;
    }

    public String constraint() {
        return constraint;
    }

    /** Display name: most specific type-reference name, else the builtin/kind name. */
    public String typeName() {
        if (!nameChain.isEmpty()) {
            return nameChain.getFirst();
        }
        return primitive != null ? primitive.name() : kind.name();
    }

    /** Copy with the tag replaced — how IMPLICIT tagging is applied to a shared named type. */
    CompiledType withTag(TagKey newTag) {
        CompiledType c = new CompiledType();
        c.kind = kind;
        c.tag = newTag;
        c.nameChain = nameChain;
        c.primitive = primitive;
        c.valueNames = valueNames;
        c.inner = inner;
        c.components = components;
        c.constraint = constraint;
        return c;
    }

    CompiledType withNamePrepended(String name) {
        CompiledType c = withTag(tag);
        c.nameChain = new ArrayList<>(nameChain);
        c.nameChain.addFirst(name);
        return c;
    }

    /**
     * Tags this node can start with in the encoding; null = matches any tag (ANY / open type).
     * For untagged CHOICE this is the union over alternatives.
     */
    public Set<TagKey> acceptedTags() {
        Set<TagKey> memo = acceptedTagsMemo;
        if (memo != null || acceptedTagsIsAny) {
            return memo;
        }
        memo = acceptedTags(new HashSet<>());
        // the compiled graph is immutable once built, so this memo is pure caching;
        // volatile keeps it safely published when one schema is shared across pipelines
        if (memo == null) {
            acceptedTagsIsAny = true;
        } else {
            acceptedTagsMemo = memo;
        }
        return memo;
    }

    /** null is a meaningful result (ANY: matches everything), so it needs its own flag. */
    private volatile Set<TagKey> acceptedTagsMemo;
    private volatile boolean acceptedTagsIsAny;

    private Set<TagKey> acceptedTags(Set<CompiledType> visited) {
        if (tag != null) {
            return Set.of(tag);
        }
        if (kind == Kind.ANY) {
            return null;
        }
        if (!visited.add(this)) {
            return Set.of(); // defensive: cyclic untagged CHOICE
        }
        if (kind == Kind.CHOICE) {
            Set<TagKey> union = new LinkedHashSet<>();
            for (Component alt : components) {
                Set<TagKey> tags = alt.type().acceptedTags(visited);
                if (tags == null) {
                    return null; // an ANY alternative matches everything
                }
                union.addAll(tags);
            }
            return union;
        }
        return Set.of();
    }

    /**
     * Tag → the component positions that accept it, so the binder does not rescan every
     * component for every child (that was O(children × components) per record). Built once
     * and memoized; the compiled graph is immutable by the time any binder runs.
     */
    public ComponentIndex componentIndex() {
        ComponentIndex idx = componentIndex;
        if (idx == null) {
            idx = ComponentIndex.build(components);
            componentIndex = idx;
        }
        return idx;
    }

    private volatile ComponentIndex componentIndex;

    /**
     * Positions are ascending per tag, which is what lets the binder keep its exact
     * legacy matching order: first component at-or-after the ordered cursor, else the
     * first one before it (the wrap-around for repeated tags).
     */
    public static final class ComponentIndex {

        private final Map<TagKey, int[]> byTag;
        /** Components accepting ANY tag — they match every key, so they merge into every lookup. */
        private final int[] anyPositions;

        private ComponentIndex(Map<TagKey, int[]> byTag, int[] anyPositions) {
            this.byTag = byTag;
            this.anyPositions = anyPositions;
        }

        static ComponentIndex build(List<Component> components) {
            Map<TagKey, List<Integer>> acc = new java.util.HashMap<>();
            List<Integer> any = new ArrayList<>();
            for (int i = 0; i < components.size(); i++) {
                Set<TagKey> tags = components.get(i).type().acceptedTags();
                if (tags == null) { // ANY / open type
                    any.add(i);
                    continue;
                }
                for (TagKey t : tags) {
                    acc.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
                }
            }
            Map<TagKey, int[]> byTag = new java.util.HashMap<>();
            acc.forEach((t, list) -> byTag.put(t, toArray(list)));
            return new ComponentIndex(byTag, toArray(any));
        }

        private static int[] toArray(List<Integer> list) {
            int[] out = new int[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = list.get(i);
            }
            return out;
        }

        /** Lowest position >= {@code from} accepting {@code key}, or -1. */
        public int firstAtOrAfter(TagKey key, int from) {
            return lowest(firstAtOrAfter(byTag.get(key), from),
                    firstAtOrAfter(anyPositions, from));
        }

        /** Lowest position < {@code limit} accepting {@code key}, or -1. */
        public int firstBefore(TagKey key, int limit) {
            return lowest(firstBelow(byTag.get(key), limit),
                    firstBelow(anyPositions, limit));
        }

        /** Merges two "position or -1" results, preferring the earlier component. */
        private static int lowest(int a, int b) {
            if (a < 0) {
                return b;
            }
            return b < 0 ? a : Math.min(a, b);
        }

        private static int firstAtOrAfter(int[] positions, int from) {
            if (positions == null) {
                return -1;
            }
            for (int p : positions) { // ascending, and typically length 1
                if (p >= from) {
                    return p;
                }
            }
            return -1;
        }

        private static int firstBelow(int[] positions, int limit) {
            if (positions == null || positions.length == 0 || positions[0] >= limit) {
                return -1;
            }
            return positions[0];
        }
    }

    /** True when {@code key} is acceptable as this type's outer tag. */
    public boolean matches(TagKey key) {
        if (tag != null) { // the common case: skip the set entirely (binder hot path)
            return tag.equals(key);
        }
        Set<TagKey> accepted = acceptedTags();
        return accepted == null || accepted.contains(key);
    }
}
