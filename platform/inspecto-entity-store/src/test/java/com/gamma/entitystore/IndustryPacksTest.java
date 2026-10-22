package com.gamma.entitystore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Industry pack format and its fail-closed discovery (LA-EDITIONS-1 §3, LDP-D7). */
class IndustryPacksTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();

    @TempDir
    Path tmp;

    @Test
    void theBundledPacksAllLoadAndNoneIsRefused() {
        IndustryPacks.Catalogue c = IndustryPacks.bundled();
        assertEquals(List.of(), c.refused(), "a shipped pack must never be refused");
        assertEquals(List.of("telecom", "mobile-money"), c.installed().stream().map(IndustryPack::id).toList());
        IndustryPack telecom = c.find("telecom");
        assertEquals("MSISDN", telecom.term("entity_type.msisdn").label());
        assertEquals("msisdn", telecom.profile().investigate().get("entity_type"));
        assertEquals("Wallet", c.find("mobile-money").term("entity_type.wallet").label());
        assertNull(c.find("generic"), "generic is the neutral vocabulary, never a pack");
    }

    @Test
    void aPackRelabelsTheCoreEntityTypesAndNeverAddsOne() {
        IndustryPack telecom = IndustryPacks.bundled().find("telecom");
        List<EntityTypes.EntityType> types = telecom.overlay(EntityTypes.DEFAULTS);
        assertEquals(EntityTypes.DEFAULTS.stream().map(EntityTypes.EntityType::id).toList(),
                types.stream().map(EntityTypes.EntityType::id).toList());
        assertEquals("MSISDN", types.stream().filter(t -> t.id().equals("msisdn")).findFirst().orElseThrow().label());
        assertEquals("Account", types.stream().filter(t -> t.id().equals("account")).findFirst().orElseThrow().label(),
                "a type the pack has no term for keeps its neutral label");
    }

    @Test
    void eachPackReferencesShippedSpaceTemplatesAndPatternPacks() throws IOException {
        String builtIns = Files.readString(REPO.resolve("inspecto-ui/projects/link-analysis/src/link-analysis/pattern-packs.ts"));
        for (IndustryPack p : IndustryPacks.bundled().installed()) {
            for (String t : p.spaceTemplates())
                assertTrue(Files.isRegularFile(REPO.resolve("spaces/_templates/" + t + "/template.toon")),
                        p.id() + " names Space Template " + t + ", which does not ship");
            Object dataset = p.profile().investigate() == null ? null : p.profile().investigate().get("dataset");
            if (dataset != null) {
                // the investigate binding names a Dataset COMPONENT id one of the pack's own Space Templates ships
                boolean shipped = p.spaceTemplates().stream().anyMatch(t ->
                        Files.isRegularFile(REPO.resolve("spaces/_templates/" + t + "/config/registry/datasets/" + dataset + ".toon")));
                assertTrue(shipped, p.id() + " investigates over Dataset " + dataset + ", which none of " + p.spaceTemplates() + " ships");
            }
            for (String pp : p.patternPacks()) {
                boolean registry;
                try (var dirs = Files.list(REPO.resolve("spaces/_templates"))) {
                    registry = dirs.anyMatch(d -> Files.isRegularFile(d.resolve("config/registry/pattern-packs/" + pp + ".toon")));
                }
                assertTrue(registry || builtIns.contains("id: '" + pp + "'"),
                        p.id() + " names pattern pack " + pp + ", which neither a template nor the built-in packs ship");
            }
        }
    }

    @Test
    void everyShippedTemplateNamesAnInstalledPackAndThePacksLeadTemplatesSelectIt() throws IOException {
        try (var dirs = Files.list(REPO.resolve("spaces/_templates"))) {
            for (Path d : dirs.toList()) {
                Path file = d.resolve("config/" + LinkAnalysisSettings.FILE);
                String stated = LinkAnalysisSettings.read(file).domainProfile();
                if (stated != null)
                    assertNotNull(IndustryPacks.bundled().find(stated), d.getFileName() + " selects pack " + stated + ", which is not installed");
            }
        }
        for (IndustryPack p : IndustryPacks.bundled().installed()) {
            String lead = p.spaceTemplates().get(0);
            assertEquals(p.id(), LinkAnalysisSettings.read(REPO.resolve("spaces/_templates/" + lead + "/config/" + LinkAnalysisSettings.FILE))
                    .domainProfile(), "the pack's first Space Template selects the pack");
        }
    }

    @Test
    void theMeasureKeysAreTheValueMeasures() {
        // la-api's ValueMeasures.ALERTABLE is pinned to this set by ValueMeasuresPackKeysTest there.
        assertEquals(6, IndustryPack.MEASURES.size());
    }

    // ── fail closed ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aGoodPackOnAnotherClasspathLoads() throws IOException {
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar1"), "toy", f -> null));
        assertEquals(List.of(), c.refused());
        assertEquals("Things", c.find("toy").term("concept.node").label());
    }

    @Test
    void theShippedPacksSuggestTheirFirstListsAndTheWireCarriesThem() {
        IndustryPack telecom = IndustryPacks.bundled().find("telecom");
        IndustryPack.SuggestedList first = telecom.profile().suggestedLists().get(0);
        assertEquals(new IndustryPack.SuggestedList("Call centres", "exclusion", "msisdn",
                "Call centres and other busy numbers call almost everyone; leaving them out keeps the graph readable."), first);
        assertEquals("Merchants", IndustryPacks.bundled().find("mobile-money").profile().suggestedLists().get(0).title());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> wire = (List<Map<String, Object>>) ((Map<String, Object>) telecom.toMap().get("profile")).get("suggestedLists");
        assertEquals(Map.of("title", "Call centres", "purpose", "exclusion", "entityType", "msisdn", "why", first.why()), wire.get(0));
    }

    @Test
    void aPackWithoutSuggestedListsSuggestsNone() throws IOException {
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar1"), "toy", f -> null));
        assertEquals(List.of(), c.find("toy").profile().suggestedLists());
    }

    @Test
    void aSuggestedListWithAnUnknownPurposeOrEntityTypeRefusesThePack() throws IOException {
        IndustryPacks.Catalogue good = load(write(tmp.resolve("jar0"), "toy", f -> f.equals("manifest")
                ? manifest("toy") + "  suggested_lists[1]{title,purpose,entity_type,why}:\n    Busy ones,exclusion,account,\"Too busy.\"\n" : null));
        assertEquals("Busy ones", good.find("toy").profile().suggestedLists().get(0).title());
        assertRefused(load(write(tmp.resolve("jar1"), "toy", f -> f.equals("manifest")
                ? manifest("toy") + "  suggested_lists[1]{title,purpose,entity_type,why}:\n    Busy ones,ignore,account,\"Too busy.\"\n" : null)),
                "toy", "purpose must be one of");
        assertRefused(load(write(tmp.resolve("jar2"), "toy", f -> f.equals("manifest")
                ? manifest("toy") + "  suggested_lists[1]{title,purpose,entity_type,why}:\n    Busy ones,exclusion,starship,\"Too busy.\"\n" : null)),
                "toy", "is not a core Entity Type");
    }

    @Test
    void aTermNamingNoCoreConceptRefusesThePackWhole() throws IOException {
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar1"), "toy",
                f -> f.equals("terms") ? "terms[1]{key,label,abbreviation,definition}:\n  concept.galaxy,Galaxies,\"\",\"New concept.\"\n" : null));
        assertRefused(c, "toy", "names no core concept");
    }

    @Test
    void anEntityTypeTheCoreDoesNotHaveIsRefused() throws IOException {
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar1"), "toy", f -> f.equals("types")
                ? "entity_types[1]:\n  - id: starship\n    normaliser: default\n    masked: false\n    classifications[1]: STARSHIP\n" : null));
        assertRefused(c, "toy", "is not a core Entity Type");
    }

    @Test
    void aNormaliserOutsideTheClosedSetIsRefused() throws IOException {
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar1"), "toy", f -> f.equals("types")
                ? "entity_types[1]:\n  - id: msisdn\n    normaliser: soundex\n    masked: true\n    classifications[1]: MSISDN\n" : null));
        assertRefused(c, "toy", "normaliser must be one of");
    }

    @Test
    void aMissingFileOrAnUnparseableOneIsRefused() throws IOException {
        Path root = write(tmp.resolve("jar1"), "toy", f -> null);
        Files.delete(root.resolve(IndustryPacks.DIR + "toy/industry-pack-link-kinds.toon"));
        assertRefused(load(root), "toy", "has no industry-pack-link-kinds.toon");
        Path root2 = write(tmp.resolve("jar2"), "toy", f -> f.equals("manifest") ? "[2]: a,b\n" : null);
        assertRefused(load(root2), "toy", "does not parse");
    }

    @Test
    void anIdTwoIndexesDeclareIsRefusedNotPickedByOrder() throws IOException {
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar1"), "toy", f -> null),
                write(tmp.resolve("jar2"), "toy", f -> null));
        assertRefused(c, "toy", "declared by 2 pack indexes");
    }

    @Test
    void aManifestIdThatDiffersFromItsDirectoryOrNamesGenericIsRefused() throws IOException {
        assertRefused(load(write(tmp.resolve("jar1"), "toy", f -> f.equals("manifest") ? manifest("other") : null)),
                "toy", "differs from its directory");
        assertRefused(load(write(tmp.resolve("jar2"), "generic", f -> f.equals("manifest") ? manifest("generic") : null)),
                "generic", "reserved");
    }

    @Test
    void aRelabelToTheNeutralWordIsRefused() throws IOException {
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar1"), "toy",
                f -> f.equals("terms") ? "terms[1]{key,label,abbreviation,definition}:\n  concept.node,Nodes,\"\",\"Same.\"\n" : null));
        assertRefused(c, "toy", "relabels to its neutral label");
    }

    // ── the Space side ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aSpaceReadsItsDomainProfileAndAnUninstalledOneReadsNeutral() throws IOException {
        Path file = tmp.resolve(LinkAnalysisSettings.FILE);
        Files.writeString(file, "masking_mode: typed\ndomain_profile: telecom\n");
        LinkAnalysisSettings s = LinkAnalysisSettings.read(file);
        assertEquals("telecom", s.domainProfile());
        assertNotNull(s.activePack());
        assertEquals("MSISDN", label(s, "msisdn"));

        Files.writeString(file, "domain_profile: atlantis\n");
        LinkAnalysisSettings unknown = LinkAnalysisSettings.read(file);
        assertNull(unknown.activePack(), "a pack this build does not ship is never applied");
        assertEquals("Phone number", label(unknown, "msisdn"));
    }

    @Test
    void aStatedEntityTypeListStillWinsOverThePack() throws IOException {
        Path file = tmp.resolve(LinkAnalysisSettings.FILE);
        Files.writeString(file, "domain_profile: telecom\nentity_types[1]:\n  - id: msisdn\n    label: Line\n    normaliser: e164\n"
                + "    masked: true\n    classifications[1]: MSISDN\n");
        assertEquals("Line", label(LinkAnalysisSettings.read(file), "msisdn"));
    }

    @Test
    void writingTheDomainProfileKeepsEveryOtherKeyAndTheSettingsWriteKeepsIt() throws IOException {
        Path file = tmp.resolve(LinkAnalysisSettings.FILE);
        Files.writeString(file, "masking_mode: none\nsome_newer_key: 7\nindex:\n  enabled: true\n");
        LinkAnalysisSettings.writeDomainProfile(file, "mobile-money");
        LinkAnalysisSettings s = LinkAnalysisSettings.read(file);
        assertEquals("mobile-money", s.domainProfile());
        assertEquals("none", s.maskingMode());
        assertTrue(Files.readString(file).contains("some_newer_key"));

        // the settings PUT's record write models other keys only: the Domain Profile survives it
        new LinkAnalysisSettings(null, null, null, "typed", null, null, null, null, null, null, null, null, null, null, null, null)
                .write(file);
        assertEquals("mobile-money", LinkAnalysisSettings.read(file).domainProfile());

        LinkAnalysisSettings.writeDomainProfile(file, null);
        assertNull(LinkAnalysisSettings.read(file).domainProfile());
        assertEquals("typed", LinkAnalysisSettings.read(file).maskingMode());
    }

    @Test
    void anUnparseableSettingsDocumentIsRefusedNotReplaced() throws IOException {
        Path file = tmp.resolve(LinkAnalysisSettings.FILE);
        Files.writeString(file, "[2]: a,b\n");
        try {
            LinkAnalysisSettings.writeDomainProfile(file, "telecom");
            throw new AssertionError("an unparseable document must be refused");
        } catch (IOException expected) {
            assertEquals("[2]: a,b\n", Files.readString(file));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

    private static String label(LinkAnalysisSettings s, String id) {
        return s.effectiveEntityTypes().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow().label();
    }

    private static void assertRefused(IndustryPacks.Catalogue c, String id, String reasonFragment) {
        assertNull(c.find(id), id + " must not be installed");
        IndustryPacks.Refused r = c.refused().stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError(id + " is not reported refused: " + c.refused()));
        assertTrue(r.reason().contains(reasonFragment), r.reason());
    }

    private static IndustryPacks.Catalogue load(Path... roots) throws IOException {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) urls[i] = roots[i].toUri().toURL();
        try (URLClassLoader cl = new URLClassLoader(urls, null)) {
            return IndustryPacks.load(cl);
        }
    }

    private static String manifest(String id) {
        return "id: " + id + "\nname: Toy\ndescription: A test pack.\nspace_templates[0]:\npattern_packs[0]:\nprofile:\n"
                + "  measure_hints[1]: amount\n  time_hints[1]: date\n  suggested_tools[0]:\n";
    }

    /** A pack {@code id} under {@code root}; {@code override} maps a file role to replacement text, or null to keep the good one. */
    private static Path write(Path root, String id, UnaryOperator<String> override) throws IOException {
        Map<String, String> good = Map.of(
                "manifest", manifest(id),
                "terms", "terms[2]{key,label,abbreviation,definition}:\n  concept.node,Things,\"\",\"What is drawn.\"\n"
                        + "  link_kind.knows,Acquaintance,\"\",\"Two things know each other.\"\n",
                "types", "entity_types[1]:\n  - id: account\n    normaliser: upper-trim\n    masked: true\n    classifications[1]: ACCOUNT\n",
                "kinds", "link_kinds[1]: knows\n");
        Map<String, String> files = Map.of("manifest", "industry-pack.toon", "terms", "industry-pack-terms.toon",
                "types", "industry-pack-entity-types.toon", "kinds", "industry-pack-link-kinds.toon");
        Path dir = root.resolve(IndustryPacks.DIR + id);
        Files.createDirectories(dir);
        Files.writeString(root.resolve(IndustryPacks.INDEX), "packs[1]: " + id + "\n", StandardCharsets.UTF_8);
        for (var e : files.entrySet()) {
            String text = override.apply(e.getKey());
            Files.writeString(dir.resolve(e.getValue()), text != null ? text : good.get(e.getKey()), StandardCharsets.UTF_8);
        }
        return root;
    }
}
