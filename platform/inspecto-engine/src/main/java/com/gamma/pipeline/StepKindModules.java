package com.gamma.pipeline;

import com.gamma.module.KnownModules;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Names the module behind a Step Processor kind this install does not have (MODULE-REORG-P4-1). A module declares the
 * catalog ids it contributes in {@code provides.stepKinds}; the table here is read from the KNOWN-but-not-INSTALLED
 * manifests, so an absent module can still be named. A kind nobody declares is unknown, not absent: it names no module.
 *
 * <p>A Pipeline reaches a kind through its node type ({@code parser.asn1}) or a processor id itself
 * ({@code parser.asn1.ber}); {@code frontend: asn1} lifts to the {@code parser.asn1} node, which the catalog maps to
 * the {@code parser.asn1.ber} processor, so the sugar needs no mapping of its own.
 */
public final class StepKindModules {
    private StepKindModules() {}

    private static volatile Map<String, ModuleManifest> absentOwners;

    /** Test seam: a stand-in table of absent owners (lower-case kind to manifest); {@code null} restores the real one. */
    public static void absentOwnersForTest(Map<String, ModuleManifest> owners) {
        absentOwners = owners;
    }

    /** Kind (lower-case) to the first manifest declaring it in {@code provides.stepKinds}. */
    public static Map<String, ModuleManifest> ownersOf(List<ModuleManifest> manifests) {
        Map<String, ModuleManifest> out = new LinkedHashMap<>();
        for (ModuleManifest m : manifests)
            for (String k : m.provides().stepKinds()) out.putIfAbsent(k.toLowerCase(Locale.ROOT), m);
        return out;
    }

    private static Map<String, ModuleManifest> absent() {
        Map<String, ModuleManifest> o = absentOwners;
        if (o == null) {
            ClassLoader cl = StepKindModules.class.getClassLoader();
            absentOwners = o = ownersOf(KnownModules.absent(KnownModules.load(cl).manifests(),
                    ModuleManifests.load(cl).manifests()));
        }
        return o;
    }

    /** The id of the absent module that declares the processor id {@code stepKind}; {@code null} when none does. */
    public static String absentModuleOfStepKind(String stepKind) {
        if (stepKind == null) return null;
        ModuleManifest m = absent().get(stepKind.toLowerCase(Locale.ROOT));
        return m == null ? null : m.id();
    }

    /** The id of the absent module behind a node type or processor id: the id itself, else a processor mapping onto the node type. */
    public static String absentModuleOfNodeType(String nodeType) {
        if (nodeType == null || absent().isEmpty()) return null;
        String direct = absentModuleOfStepKind(nodeType);
        if (direct != null) return direct;
        for (ProcessorCatalog.Processor p : ProcessorCatalog.PROCESSORS) {
            if (!nodeType.equals(p.nodeType())) continue;
            String m = absentModuleOfStepKind(p.id());
            if (m != null) return m;
        }
        return null;
    }

    private static final String INGESTER_USE = "ingester/";

    /**
     * The step kind a node needs from an absent module, or {@code null}: its node type, or - for a {@code parser} whose
     * {@code use:} names a pack's ingester ({@code frontend: asn1} lifts to {@code ingester/...Asn1RecordIngester}) - the
     * processor id that pack delivers.
     */
    static String absentKindOf(PipelineNode n) {
        if (absentModuleOfNodeType(n.type()) != null) return n.type();
        String use = n.use();
        if (use != null && use.startsWith(INGESTER_USE)) {
            String id = ProcessorCatalog.packIngesters().get(use.substring(INGESTER_USE.length()));
            if (id != null && absentModuleOfStepKind(id) != null) return id;
        }
        return null;
    }

    /** The first absent module any node of {@code g} needs; {@code null} when every node's kind is available or unclaimed. */
    public static String absentModuleOf(PipelineGraph g) {
        String kind = absentKindOf(g);
        return kind == null ? null : absentModuleOfNodeType(kind);
    }

    private static final java.util.regex.Pattern UNKNOWN_KIND =
            java.util.regex.Pattern.compile("unknown [^']*kind '([^']+)'");

    /**
     * The kind a load error refuses (the loader's "unknown steps[] kind 'k'" message), when a known-but-uninstalled module
     * declares it as the processor id {@code k} or {@code transform.k}; {@code null} when the message names no kind or nobody
     * declares it.
     */
    public static String absentKindOfLoadMessage(String message) {
        if (message == null) return null;
        java.util.regex.Matcher m = UNKNOWN_KIND.matcher(message);
        if (!m.find()) return null;
        String kind = m.group(1);
        if (absentModuleOfNodeType(kind) != null) return kind;
        return absentModuleOfNodeType("transform." + kind) != null ? "transform." + kind : null;
    }

    /** The one sentence: a kind provided by a module that is not installed. */
    public static String reason(String kind, String module) {
        return "step kind '" + kind + "' is provided by the module '" + module + "', which is not installed here, so this "
                + "pipeline cannot run (its config is kept and works once the module is installed)";
    }

    /** The step kind of {@code g} behind {@link #absentModuleOf(PipelineGraph)}, for the reason text. */
    public static String absentKindOf(PipelineGraph g) {
        for (PipelineNode n : g.nodes()) {
            String k = absentKindOf(n);
            if (k != null) return k;
        }
        return null;
    }
}
