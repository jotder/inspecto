package com.gamma.module;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleManifestsTest {

    private static final String OPS = """
            ---
            id: ops
            title: Case management
            buildRole: implementation
            offeringRole: optional
            bindingTime: boot
            provides:
              features[1]: ops
              contracts[2]: case-store,note-store
            requires:
              modules[1]: engine
              contracts[0]:
            """;

    private static ModuleManifest m(String id, List<String> reqModules, List<String> provContracts, List<String> reqContracts) {
        return new ModuleManifest(id, id, "implementation", "optional", "boot",
                new ModuleManifest.Provides(List.of(), provContracts, List.of(), List.of(), List.of(), List.of()),
                new ModuleManifest.Requires(reqModules, reqContracts), null);
    }

    /** A class loader whose resources are one module.toon per directory — what the classpath looks like. */
    private static ClassLoader loaderOf(Path root, String... files) throws Exception {
        URL[] urls = new URL[files.length];
        for (int i = 0; i < files.length; i++) {
            Path dir = root.resolve("cp" + i);
            Path f = dir.resolve(ModuleManifests.RESOURCE);
            Files.createDirectories(f.getParent());
            Files.writeString(f, files[i]);
            urls[i] = dir.toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    @Test
    void jobTypesAreParsedAndAbsentMeansNone() {
        ModuleManifest with = ModuleManifests.parse("""
                id: x
                buildRole: implementation
                offeringRole: optional
                bindingTime: boot
                provides:
                  features[1]: x
                  jobTypes[2]: x.run,x.sweep
                """);
        assertEquals(List.of("x.run", "x.sweep"), with.provides().jobTypes());
        ModuleManifest without = ModuleManifests.parse("""
                id: y
                buildRole: implementation
                offeringRole: optional
                bindingTime: boot
                provides:
                  features[1]: y
                """);
        assertEquals(List.of(), without.provides().jobTypes());
    }

    @Test
    void validManifestParsesWithArraysAndEmptyArray() {
        ModuleManifest ops = ModuleManifests.parse(OPS.replace("---\n", ""));
        assertEquals("ops", ops.id());
        assertEquals("implementation", ops.buildRole());
        assertEquals(List.of("ops"), ops.provides().features());
        assertEquals(List.of("case-store", "note-store"), ops.provides().contracts());
        assertEquals(List.of("engine"), ops.requires().modules());
        assertEquals(List.of(), ops.requires().contracts(), "an empty TOON array must read as an empty list");
        assertEquals(List.of(), ops.provides().capabilities(), "an absent key must read as an empty list");
        assertNull(ops.entitlementKey());
    }

    @Test
    void routesWithRegexMetacharactersAndSpacesRoundTripInOrder() {
        List<String> routes = List.of("GET /events", "GET /events/views", "POST /events/views/([^/]+)/delete",
                "POST /exchange/grants/([^/]+)/(approve|deny|revoke)", "GET /events/([^/]+)");
        String quoted = routes.stream().map(r -> "\"" + r + "\"").collect(java.util.stream.Collectors.joining(","));
        ModuleManifest m = ModuleManifests.parse(OPS.replace("  contracts[2]: case-store,note-store",
                "  routes[" + routes.size() + "]: " + quoted).replace("---\n", ""));
        assertEquals(routes, m.provides().routes(), "a catch-all must stay where the manifest put it");
    }

    @Test
    void emptySectionsAndMissingTitleAreTolerated() {
        ModuleManifest m = ModuleManifests.parse("id: util\nbuildRole: foundation\nofferingRole: base\nbindingTime: build\n"
                + "provides:\nrequires:\n");
        assertEquals("util", m.title(), "title defaults to the id");
        assertEquals(ModuleManifest.Provides.NONE, m.provides());
        assertEquals(ModuleManifest.Requires.NONE, m.requires());
    }

    @Test
    void commentLineAboveAnArrayIsNotSkippedByToonSoItFailsSoftWithADiagnostic(@TempDir Path tmp) throws Exception {
        // JToon has no comment syntax (ToonHelper.decode javadoc): a '#' line is data. The loader must not die on it.
        String commented = OPS.replace("  features[1]: ops", "  # the features\n  features[1]: ops");
        ModuleManifests.Loaded l = ModuleManifests.load(loaderOf(tmp, commented));
        // Either outcome is acceptable to the loader's contract; what is NOT acceptable is an exception or a silent drop.
        assertTrue(l.manifests().size() == 1 || !l.diagnostics().isEmpty(),
                "a '#' line must either parse or be reported, never vanish silently");
    }

    @Test
    void badFileYieldsADiagnosticAndTheGoodFileStillLoads(@TempDir Path tmp) throws Exception {
        String bad = "id: broken\nbuildRole: wizard\nofferingRole: base\nbindingTime: build\n";
        ModuleManifests.Loaded l = ModuleManifests.load(loaderOf(tmp, bad, OPS));
        assertEquals(List.of("ops"), l.manifests().stream().map(ModuleManifest::id).toList(), l.diagnostics().toString());
        assertEquals(1, l.diagnostics().size());
        assertTrue(l.diagnostics().get(0).contains("buildRole must be one of"), l.diagnostics().get(0));
    }

    @Test
    void undecodableAndIdlessFilesAreDiagnosticsNotExceptions(@TempDir Path tmp) throws Exception {
        ModuleManifests.Loaded l = ModuleManifests.load(loaderOf(tmp, "items[3]: a,b\n", "title: no id\n"));
        assertEquals(0, l.manifests().size());
        assertEquals(2, l.diagnostics().size());
    }

    @Test
    void duplicateIdKeepsTheFirstAndReportsTheSecond(@TempDir Path tmp) throws Exception {
        ModuleManifests.Loaded l = ModuleManifests.load(loaderOf(tmp, OPS, OPS.replace("Case management", "Impostor")));
        assertEquals(1, l.manifests().size());
        assertEquals("Case management", l.manifests().get(0).title());
        assertTrue(l.diagnostics().get(0).contains("duplicate module id 'ops'"), l.diagnostics().get(0));
    }

    @Test
    void oneResourceMayHoldSeveralManifestsSeparatedByDashLines(@TempDir Path tmp) throws Exception {
        // what a shade AppendingTransformer produces
        String merged = OPS + OPS.replace("id: ops", "id: events").replace("features[1]: ops", "features[1]: events");
        ModuleManifests.Loaded l = ModuleManifests.load(loaderOf(tmp, merged));
        assertEquals(List.of("ops", "events"), l.manifests().stream().map(ModuleManifest::id).toList());
        assertTrue(l.diagnostics().isEmpty(), l.diagnostics().toString());
    }

    @Test
    void transitiveInertness() {
        var st = ModuleActivator.resolve(List.of(
                m("a", List.of("b"), List.of(), List.of()),
                m("b", List.of("c"), List.of(), List.of()),
                m("c", List.of("missing"), List.of(), List.of()),
                m("d", List.of(), List.of(), List.of())));
        assertEquals(ModuleStatus.State.INERT, st.get(0).state());
        assertEquals(ModuleStatus.State.INERT, st.get(1).state());
        assertEquals(ModuleStatus.State.INERT, st.get(2).state());
        assertEquals(ModuleStatus.State.ACTIVE, st.get(3).state());
        assertEquals(List.of("requires module 'missing', which is not installed"), st.get(2).reasons());
        assertEquals(List.of("requires module 'c', which is inert"), st.get(1).reasons());
        assertEquals(List.of(), st.get(3).reasons());
    }

    @Test
    void contractSatisfiedAndUnsatisfied() {
        var st = ModuleActivator.resolve(List.of(
                m("idp", List.of(), List.of("identity"), List.of()),
                m("maker", List.of(), List.of(), List.of("identity")),
                m("lonely", List.of(), List.of(), List.of("telepathy"))));
        assertEquals(ModuleStatus.State.ACTIVE, st.get(1).state());
        assertEquals(ModuleStatus.State.INERT, st.get(2).state());
        assertEquals(List.of("requires contract 'telepathy', which no active module provides"), st.get(2).reasons());
    }

    @Test
    void aContractProvidedOnlyByAnInertModuleIsNotSatisfied() {
        var st = ModuleActivator.resolve(List.of(
                m("idp", List.of("gone"), List.of("identity"), List.of()),
                m("maker", List.of(), List.of(), List.of("identity"))));
        assertEquals(ModuleStatus.State.INERT, st.get(0).state());
        assertEquals(ModuleStatus.State.INERT, st.get(1).state());
    }

    @Test
    void aCycleBetweenPresentModulesStaysActive() {
        var st = ModuleActivator.resolve(List.of(m("x", List.of("y"), List.of(), List.of()), m("y", List.of("x"), List.of(), List.of())));
        assertTrue(st.stream().allMatch(s -> s.state() == ModuleStatus.State.ACTIVE));
    }
}
