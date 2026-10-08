package com.gamma.pipeline;

import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MODULE-REORG-P4-1: a step kind a known-but-uninstalled module declares ({@code provides.stepKinds}) names that module;
 * a kind nobody declares is unknown, not absent, and names none.
 */
class StepKindModulesTest {

    private static final ModuleManifest ASN1 = ModuleManifests.parse("""
            id: telecom-asn1
            buildRole: implementation
            offeringRole: provider
            bindingTime: boot
            provides:
              stepKinds[1]: parser.asn1.ber
            """);
    private static final ModuleManifest ZZ = ModuleManifests.parse("""
            id: zz-pack
            buildRole: implementation
            offeringRole: provider
            bindingTime: boot
            provides:
              stepKinds[1]: zz.absent-kind
            """);

    @AfterEach
    void restore() {
        StepKindModules.absentOwnersForTest(null);
    }

    private static void absent(ModuleManifest... ms) {
        StepKindModules.absentOwnersForTest(StepKindModules.ownersOf(List.of(ms)));
    }

    @Test
    void theAsnFrontendNodeNamesTheTelecomModuleWhenItIsAbsent() {
        absent(ASN1);
        assertEquals("telecom-asn1", StepKindModules.absentModuleOfNodeType("parser.asn1"));
        assertEquals("telecom-asn1", StepKindModules.absentModuleOfStepKind("parser.asn1.ber"));
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                new PipelineNode("p", "parser.asn1", null, null, Map.of(), null)), List.of());
        assertEquals("telecom-asn1", StepKindModules.absentModuleOf(g));
        assertTrue(StepKindModules.reason("parser.asn1", "telecom-asn1")
                .contains("provided by the module 'telecom-asn1', which is not installed"));
    }

    @Test
    void aParserLiftedFromTheAsnFrontendIsAttributedThroughItsIngesterUse() {
        absent(ASN1);
        PipelineGraph g = new PipelineGraph("x", true, List.of(new PipelineNode("parse", "parser", null, null, Map.of(),
                "ingester/com.gamma.telecom.asn1.Asn1RecordIngester")), List.of());
        assertEquals("telecom-asn1", StepKindModules.absentModuleOf(g));
        assertEquals("parser.asn1.ber", StepKindModules.absentKindOf(g));
        PipelineGraph other = new PipelineGraph("x", true, List.of(new PipelineNode("parse", "parser", null, null, Map.of(),
                "ingester/com.example.OtherIngester")), List.of());
        assertNull(StepKindModules.absentModuleOf(other), "an ingester no pack claims names no module");
    }

    @Test
    void aKindNobodyDeclaresIsUnknownNotAbsent() {
        absent(ASN1);
        assertNull(StepKindModules.absentModuleOfNodeType("transform.nobody-declares-this"));
        assertNull(StepKindModules.absentModuleOfNodeType("parser.delimited"));
        assertNull(StepKindModules.absentModuleOfNodeType(null));
    }

    @Test
    void anInstalledModuleNamesNothing() {
        absent();   // the owner table holds only ABSENT modules: an installed one is simply not in it
        assertNull(StepKindModules.absentModuleOfNodeType("parser.asn1"));
    }

    @Test
    void aLoadErrorNamingAnAbsentKindIsAttributedAndAnotherIsNot() {
        absent(ZZ);
        String msg = "unknown steps[] kind 'zz.absent-kind' - expected one of [dedup]";
        assertEquals("zz.absent-kind", StepKindModules.absentKindOfLoadMessage(msg));
        assertNull(StepKindModules.absentKindOfLoadMessage("unknown steps[] kind 'typo' - expected one of [dedup]"));
        assertNull(StepKindModules.absentKindOfLoadMessage("steps: must be a LIST"));
    }

    @Test
    void putGraphUnsupportedNodeNamesTheModuleThroughTheManifestPath() {
        absent(ZZ);
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                new PipelineNode("z", "zz.absent-kind", null, null, Map.of(), null),
                new PipelineNode("q", "zz.typo", null, null, Map.of(), null)), List.of());
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), false));
        Map<String, String> byNode = new LinkedHashMap<>();
        ex.refusals().forEach(r -> byNode.put(r.nodeId(), r.message()));
        assertTrue(byNode.get("z").contains("module 'zz-pack', which is not installed"), byNode.toString());
        assertTrue(byNode.get("q").contains("no installed module registers that node type"), byNode.toString());
    }
}
