package com.gamma.telecom.asn1;

import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import com.gamma.pipeline.ProcessorCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MODULE-REORG-P4-1: this module's {@code provides.stepKinds} equals the catalog processors whose {@code Pack} marker
 * names it. The marker (in code) is the truth; the manifest field makes it readable for an ABSENT module through
 * known-modules, so a Pipeline using {@code frontend: asn1} can name the module an install is missing.
 */
class Asn1StepKindManifestTest {

    private static final String MODULE_ARTIFACT = "inspecto-telecom-asn1";

    @Test
    void declaredStepKindsEqualTheCatalogProcessorsThisModuleIsMarkedAsProviding() {
        ModuleManifest m = ModuleManifests.load(getClass().getClassLoader()).manifests().stream()
                .filter(x -> "telecom-asn1".equals(x.id())).findFirst().orElseThrow();
        TreeSet<String> marked = new TreeSet<>();
        for (Map.Entry<String, String> e : ProcessorCatalog.packModules().entrySet())
            if (MODULE_ARTIFACT.equals(e.getValue())) marked.add(e.getKey());
        assertEquals(List.copyOf(marked), List.copyOf(new TreeSet<>(m.provides().stepKinds())),
                "provides.stepKinds must list exactly the ProcessorCatalog ids marked as provided by " + MODULE_ARTIFACT);
    }

    /** The ingester class the catalog's Pack marker names is the one the plugin really persists (a hand-kept string, pinned). */
    @Test
    void thePackMarkerNamesTheIngesterTheParserPluginPersists() {
        assertEquals(new Asn1ParserPlugin().ingesterClass().orElseThrow(),
                ProcessorCatalog.packIngesters().entrySet().stream()
                        .filter(e -> "parser.asn1.ber".equals(e.getValue())).findFirst().orElseThrow().getKey());
    }
}
