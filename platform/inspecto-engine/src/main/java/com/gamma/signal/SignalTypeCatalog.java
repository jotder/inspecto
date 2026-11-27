package com.gamma.signal;

import com.gamma.api.PublicApi;
import com.gamma.module.EntitledServices;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every Signal Type that can be posted (SIGNAL-BOARD-1 §11.2): the declarations of every {@link SignalTypeProvider}
 * plus the dotted {@code emits} of the Job Types a Job Pack brings, which are listed with "fields not declared". A
 * type declared by two providers is an error at assembly; a pack emit that a provider already declares keeps the
 * provider's declaration.
 *
 * <p>{@link #STAMPED} keys are written by the framework onto the payload of any Signal and are never a type's own field.
 */
@PublicApi(since = "4.0.0")
public final class SignalTypeCatalog {

    /** Payload keys the framework stamps onto any Signal: the chain depth a Run or the Job service writes (§8.4). */
    public static final Set<String> STAMPED = Set.of("chainDepth");

    /** One catalogued type: its declaration, where it comes from ({@code core}, {@code module:<id>},
     *  {@code pack:<file>}), and whether its fields are declared (false for a pack's emit). */
    public record Entry(SignalTypeDecl decl, String source, boolean fieldsDeclared) {
        /** JSON view for {@code GET /signals/types}. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = decl.toMap();
            m.put("source", source);
            m.put("fieldsDeclared", fieldsDeclared);
            return m;
        }
    }

    /** One dotted type a Job Pack's Job Type declares in its {@code emits}. */
    public record PackEmit(String pack, String jobType, String type) {}

    private final Map<String, Entry> byType;

    private SignalTypeCatalog(Map<String, Entry> byType) {
        this.byType = byType;
    }

    /** The catalog of every entitled provider on the classpath, with no pack emits. */
    public static SignalTypeCatalog installed() {
        return assemble(EntitledServices.load(SignalTypeProvider.class), List.of());
    }

    /**
     * Assemble the catalog. Throws {@link IllegalStateException} when two providers declare the same type (or two
     * families with the same prefix). A pack emit that is not dotted is skipped: only a dotted name can be posted.
     */
    public static SignalTypeCatalog assemble(Iterable<? extends SignalTypeProvider> providers, Collection<PackEmit> packEmits) {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (SignalTypeProvider p : providers) {
            for (SignalTypeDecl d : p.signalTypes()) {
                Entry prior = m.putIfAbsent(d.type(), new Entry(d, p.source(), true));
                if (prior != null)
                    throw new IllegalStateException("Signal type '" + d.type() + "' is declared by both "
                            + prior.source() + " and " + p.source());
            }
        }
        for (PackEmit e : packEmits) {
            if (!SignalTypeDecl.isDotted(e.type()) || m.containsKey(e.type())) continue;
            m.put(e.type(), new Entry(new SignalTypeDecl(e.type(), e.type(), "job-type:" + e.jobType(), List.of(), null),
                    "pack:" + e.pack(), false));
        }
        return new SignalTypeCatalog(m);
    }

    /** Every catalogued type, in declaration order. */
    public List<Entry> entries() {
        return List.copyOf(byType.values());
    }

    /** The entry that covers {@code signalType}: its own declaration, else the longest family it belongs to. */
    public Optional<Entry> find(String signalType) {
        if (signalType == null) return Optional.empty();
        Entry exact = byType.get(signalType);
        if (exact != null) return Optional.of(exact);
        Entry best = null;
        for (Entry e : byType.values())
            if (e.decl().family() && e.decl().covers(signalType)
                    && (best == null || e.decl().type().length() > best.decl().type().length()))
                best = e;
        return Optional.ofNullable(best);
    }

    /**
     * What is wrong with posting {@code signalType} with {@code payload}: the type is not catalogued, a payload key is
     * not declared, or an {@code always} field is missing. Empty when the posting fits. A type whose fields are not
     * declared (a pack's emit) is checked for its name only.
     */
    public List<String> problems(String signalType, Map<String, ?> payload) {
        Optional<Entry> found = find(signalType);
        if (found.isEmpty()) return List.of("Signal type '" + signalType + "' is not catalogued: declare it in a SignalTypeProvider");
        Entry e = found.get();
        if (!e.fieldsDeclared()) return List.of();
        List<String> out = new ArrayList<>();
        Map<String, ?> p = payload == null ? Map.of() : payload;
        for (String key : p.keySet())
            if (!STAMPED.contains(key) && e.decl().field(key) == null)
                out.add("Signal type '" + signalType + "' (" + e.source() + ") does not declare payload field '" + key + "'");
        for (SignalField f : e.decl().fields())
            if (f.always() && !p.containsKey(f.name()))
                out.add("Signal type '" + signalType + "' (" + e.source() + ") always carries '" + f.name()
                        + "', but this posting lacks it");
        return out;
    }
}
