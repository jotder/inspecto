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
                "toy", "neither a core Entity Type nor one the pack lists");
    }

    /**
     * ENTITY-TYPE-CATALOG-1: the overlay lends an Entity Type its term's definition and the load validates the overlay, so a
     * term definition at the pack's own bound (400) still loads - no pack rule change.
     */
    @Test
    void anEntityTypeTermDefinitionAtThePackBoundStillLoads() throws IOException {
        String def = "d".repeat(400);
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar1"), "toy", f -> f.equals("terms")
                ? "terms[1]{key,label,abbreviation,definition}:\n  entity_type.account,Ledger,\"\",\"" + def + "\"\n" : null));
        assertEquals(List.of(), c.refused(), "a 400-character term definition is within the pack's bound");
        EntityTypes.EntityType account = c.find("toy").overlay(EntityTypes.DEFAULTS).stream()
                .filter(t -> t.id().equals("account")).findFirst().orElseThrow();
        assertEquals(def, account.definition());
    }

    @Test
    void aTermNamingNoCoreConceptRefusesThePackWhole() throws IOException {
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar1"), "toy",
                f -> f.equals("terms") ? "terms[1]{key,label,abbreviation,definition}:\n  concept.galaxy,Galaxies,\"\",\"New concept.\"\n" : null));
        assertRefused(c, "toy", "names no core concept");
    }

    // ── ENTITY-TYPE-CATALOG-1 P1: a pack adds types under a family ─────────────────────────────────────────────

    private static final String STARSHIP_TERMS = "terms[2]{key,label,abbreviation,definition}:\n  concept.node,Things,\"\",\"What is drawn.\"\n"
            + "  entity_type.starship,Starship,SS,\"A vessel that travels between stars.\"\n";

    private static String starship(String extra) {
        return "entity_types[1]:\n  - id: starship\n    normaliser: upper-trim\n    masked: false\n    classifications[1]: STARSHIP\n" + extra;
    }

    @Test
    void aPackAddsATypeUnderAFamilyWithItsTermShapeAndExample() throws IOException {
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar1"), "toy", f -> switch (f) {
            case "terms" -> STARSHIP_TERMS;
            case "types" -> starship("    family: device\n    shape: digits(6)\n    example: \"104233\"\n");
            case "manifest" -> manifest("toy") + "  suggested_lists[1]{title,purpose,entity_type,why}:\n    Fleet,watch,starship,\"Ours.\"\n";
            default -> null;
        }));
        assertEquals(List.of(), c.refused());
        List<EntityTypes.EntityType> types = c.find("toy").overlay(EntityTypes.DEFAULTS);
        assertEquals(EntityTypes.DEFAULTS.size() + 1, types.size(), "the core types, then the added one");
        EntityTypes.EntityType ss = types.get(types.size() - 1);
        assertEquals(new EntityTypes.EntityType("starship", "Starship", "upper-trim", false, List.of("STARSHIP"), "device",
                "A vessel that travels between stars.", "104233", "digits(6)"), ss);
        assertEquals("starship", c.find("toy").profile().suggestedLists().get(0).entityType(), "a suggestion may name the pack's own type");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> wire = (List<Map<String, Object>>) c.find("toy").toMap().get("entityTypes");
        assertEquals("digits(6)", wire.get(wire.size() - 1).get("shape"));
    }

    @Test
    void anAddedTypeWithoutAFamilyOrATermIsRefused() throws IOException {
        assertRefused(load(write(tmp.resolve("jar1"), "toy", f -> f.equals("types") ? starship("")
                : f.equals("terms") ? STARSHIP_TERMS : null)), "toy", "must name its family");
        assertRefused(load(write(tmp.resolve("jar2"), "toy", f -> f.equals("types") ? starship("    family: device\n") : null)),
                "toy", "needs a term 'entity_type.starship'");
        assertRefused(load(write(tmp.resolve("jar3"), "toy", f -> f.equals("types") ? starship("    family: vehicle\n")
                : f.equals("terms") ? STARSHIP_TERMS : null)), "toy", "family must be one of");
    }

    @Test
    void aShapeOutsideTheClosedVocabularyIsRefused() throws IOException {
        assertRefused(load(write(tmp.resolve("jar1"), "toy", f -> f.equals("types") ? starship("    family: device\n    shape: \"[A-Z]+\"\n")
                : f.equals("terms") ? STARSHIP_TERMS : null)), "toy", "value shape must be one of");
    }

    @Test
    void aRestatedCoreTypeKeepsItsFamilyAndMayStateItsShape() throws IOException {
        assertRefused(load(write(tmp.resolve("jar1"), "toy", f -> f.equals("types")
                ? "entity_types[1]:\n  - id: account\n    family: party\n    normaliser: upper-trim\n    masked: true\n    classifications[1]: ACCOUNT\n"
                : null)), "toy", "a pack keeps it there");
        IndustryPacks.Catalogue c = load(write(tmp.resolve("jar2"), "toy", f -> f.equals("types")
                ? "entity_types[1]:\n  - id: account\n    normaliser: upper-trim\n    masked: true\n    classifications[1]: ACCOUNT\n"
                + "    shape: iban\n    example: GB82WEST12345698765432\n" : null));
        EntityTypes.EntityType account = c.find("toy").overlay(EntityTypes.DEFAULTS).stream()
                .filter(t -> t.id().equals("account")).findFirst().orElseThrow();
        assertEquals("iban", account.shape());
        assertEquals("GB82WEST12345698765432", account.example());
        assertEquals("account", account.family());
    }

    @Test
    void twoPacksThatDisagreeOnASharedTypeOrClaimOneClassificationDoNotBothInstall() throws IOException {
        Path a = write(tmp.resolve("jarA"), "alpha", f -> f.equals("types") ? starship("    family: device\n")
                : f.equals("terms") ? STARSHIP_TERMS : null);
        Path b = write(tmp.resolve("jarB"), "beta", f -> f.equals("types") ? starship("    family: place\n")
                : f.equals("terms") ? STARSHIP_TERMS : null);
        IndustryPacks.Catalogue c = load(a, b);
        assertNotNull(c.find("alpha"));
        assertRefused(c, "beta", "does not read together");

        Path d = write(tmp.resolve("jarD"), "delta", f -> f.equals("types")
                ? "entity_types[1]:\n  - id: dock\n    family: place\n    normaliser: default\n    masked: false\n    classifications[1]: STARSHIP\n"
                : f.equals("terms") ? "terms[1]{key,label,abbreviation,definition}:\n  entity_type.dock,Dock,\"\",\"Where ships wait.\"\n" : null);
        assertRefused(load(a, d), "delta", "is claimed by both");

        Path g = write(tmp.resolve("jarG"), "gamma", f -> f.equals("types") ? starship("    family: device\n    shape: digits(4)\n")
                : f.equals("terms") ? "terms[1]{key,label,abbreviation,definition}:\n  entity_type.starship,Spaceship,\"\",\"A ship.\"\n" : null);
        IndustryPacks.Catalogue both = load(a, g);
        assertEquals(List.of(), both.refused(), "the same family, normaliser and masking read together");
        List<EntityTypes.EntityType> merged = IndustryPack.overlay(List.of(both.find("gamma"), both.find("alpha")), EntityTypes.DEFAULTS);
        EntityTypes.EntityType ss = merged.stream().filter(t -> t.id().equals("starship")).findFirst().orElseThrow();
        assertEquals("Spaceship", ss.label(), "the first pack wins the label");
        assertEquals("digits(4)", ss.shape());
        assertEquals(1, merged.stream().filter(t -> t.id().equals("starship")).count(), "one id, one type");
        // the same id with other classifications: refused too, so any subset and order of installed packs reads together
        Path h = write(tmp.resolve("jarH"), "eta", f -> f.equals("types")
                ? "entity_types[1]:\n  - id: starship\n    family: device\n    normaliser: upper-trim\n    masked: false\n    classifications[1]: SHIP\n"
                : f.equals("terms") ? STARSHIP_TERMS : null);
        assertRefused(load(a, h), "eta", "classifications");
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
    void extraPacksJoinTheDomainProfilesTypesAndTheProfileWinsALabel() throws IOException {
        Path file = tmp.resolve(LinkAnalysisSettings.FILE);
        Files.writeString(file, "domain_profile: telecom\nextra_packs[2]: mobile-money,atlantis\n");
        LinkAnalysisSettings s = LinkAnalysisSettings.read(file);
        assertEquals(List.of("mobile-money", "atlantis"), s.extraPacks());
        assertEquals(List.of("telecom", "mobile-money"), s.activePacks().stream().map(IndustryPack::id).toList(),
                "an extra pack this build does not ship is skipped");
        assertEquals("telecom", s.activePack().id(), "the Domain Profile alone gives the vocabulary");
        assertEquals("MSISDN", label(s, "msisdn"));
        assertEquals("Wallet", label(s, "wallet"), "the extra pack relabels its own types");

        LinkAnalysisSettings.writeDomainProfile(file, "telecom", List.of());
        assertEquals(List.of(), LinkAnalysisSettings.read(file).extraPacks(), "[] clears them");
        LinkAnalysisSettings.writeDomainProfile(file, "telecom", List.of("mobile-money"));
        LinkAnalysisSettings.writeDomainProfile(file, null);
        assertEquals(List.of("mobile-money"), LinkAnalysisSettings.read(file).extraPacks(), "a profile-only write keeps them");
        assertEquals("Wallet", label(LinkAnalysisSettings.read(file), "wallet"), "an extra pack applies without a Domain Profile");
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
