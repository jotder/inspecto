package com.gamma.service;

import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.control.ReactorModules;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-1 P4e} (P4-3 b) - who owns each registry kind, and what an import does with it.
 *
 * <p><b>What the characterisation found.</b> The registry roster ({@link ComponentStore#WRITABLE_TYPES}, with its
 * directories in {@code ComponentRegistry.TYPE_BY_DIR}) is one hand-kept ENGINE list, not a per-module one, so the
 * importable-kind roster of {@link ImportPaths} does not depend on which modules are installed: a registry entry of a
 * kind an ABSENT module owns ({@code risk-score}, {@code reconciliation}) is accepted and carried as inert config -
 * nothing is dropped and nothing is refused for the module's absence. What an import refuses it refuses for every
 * install: the Lens access config (a narrower gate owns it), the governance kinds (their own {@code canAdminister}
 * routes) and the suffix-scanned ops configs.
 *
 * <p>The kinds a module owns are DECLARED ({@code provides.configKinds}); this guard keeps the declaration honest:
 * a declared kind is a real roster kind, one module declares it, and its import verdict is the one pinned here.
 * Manifests are read from the SOURCE tree, so a module that is not on this class path is checked too.
 */
class ConfigKindOwnershipTest {

    /** The kinds an import never writes, on any install (see {@link ImportPaths#REGISTRY_DIRS}). */
    private static final Set<String> NEVER_IMPORTED = Set.of("access-catalog", "access-profile",
            "workflow", "sla-policy", "escalation-rule");

    private static Map<String, String> declaredOwners() throws IOException {
        Map<String, String> owner = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (Path module : ReactorModules.withMainJava(ReactorModules.topLevelModules())) {
            Path mf = module.resolve("src/main/resources").resolve(ModuleManifests.RESOURCE);
            if (!Files.isRegularFile(mf)) continue;
            ModuleManifest m = ModuleManifests.parse(Files.readString(mf).replaceFirst("(?m)^---[ \\t]*\\r?\\n", "").strip());
            for (String kind : m.provides().configKinds()) {
                String prior = owner.put(kind, m.id());
                if (prior != null) problems.add("kind '" + kind + "' is declared by both " + prior + " and " + m.id());
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return owner;
    }

    @Test
    void everyDeclaredConfigKindIsARealRosterKindWithOneOwner() throws IOException {
        Map<String, String> owners = declaredOwners();
        assertTrue(!owners.isEmpty(), "no module declares a config kind - the declaration is the point of P4e");
        List<String> problems = new ArrayList<>();
        for (var e : owners.entrySet())
            if (!ComponentStore.WRITABLE_TYPES.contains(e.getKey()))
                problems.add(e.getValue() + " declares configKind '" + e.getKey() + "' which is not in ComponentStore.WRITABLE_TYPES");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theModuleOwnedKindsAreTheOnesTheirModulesAuthor() throws IOException {
        Map<String, String> owners = declaredOwners();
        assertEquals("scoring", owners.get("risk-score"));
        assertEquals("reconciliation", owners.get("reconciliation"));
        assertEquals("ops", owners.get("workflow"));
        assertEquals("ops", owners.get("sla-policy"));
        assertEquals("ops", owners.get("escalation-rule"));
    }

    @Test
    void anImportOfAKindIsJudgedTheSameWhetherOrNotItsOwnerIsInstalled() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));   // a path that need not exist: layers 1-3 only
        Set<String> accepted = new TreeSet<>(), refused = new TreeSet<>();
        for (String kind : ComponentStore.WRITABLE_TYPES) {
            String dir = ComponentRegistry.dirForType(kind).orElseThrow();
            String suffix = ComponentRegistry.CSV_KINDS.contains(kind) ? ".csv" : ".toon";
            (ImportPaths.refusal(root, "registry/" + dir + "/x" + suffix) == null ? accepted : refused).add(kind);
        }
        // the kinds of an absent module are accepted (inert config), never refused for the module's absence
        for (String kind : List.of("risk-score", "reconciliation")) assertTrue(accepted.contains(kind), kind + " in " + accepted);
        assertEquals(NEVER_IMPORTED, refused, "what an import refuses, it refuses on every install");
        assertNull(ImportPaths.refusal(root, "registry/risk-scores/r.toon"), "a risk-score model carried to a target without scoring");
        assertNull(ImportPaths.refusal(root, "registry/reconciliations/r.toon"));
    }

    @Test
    void theSuffixScannedOpsConfigsAreRefusedOnEveryInstallAndTheRefusalDoesNotPretendItIsAboutAnAbsentModule() {
        for (String f : List.of("a_caserule.toon", "a_tag.toon", "a_tagrule.toon", "a_workflow.toon", "a_escalation.toon"))
            for (String path : List.of(f, "registry/decision-rules/" + f, "sub/" + f)) {
                String why = ImportPaths.shapeRefusal(path, true);   // package-private: this test lives beside it
                assertTrue(why != null && why.contains("suffix scan"), path + " -> " + why);
            }
    }
}
