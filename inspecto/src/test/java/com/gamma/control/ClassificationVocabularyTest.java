package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.EntityTypes.EntityType;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.ClassificationVocabulary;
import com.gamma.util.ColumnClassification;
import com.gamma.util.StructureCsv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ClassificationVocabulary} (CLASSIFICATION-UNMASKED-CLASS-1): which column classifications a Space knows, and
 * the finding a {@code schema} or {@code pipeline} draft earns for one it does not. The doors that call it are driven
 * over real HTTP in {@link ControlApiClassificationVocabularyTest}.
 */
class ClassificationVocabularyTest {

    private static final List<EntityType> DEFAULTS = EntityTypes.DEFAULTS;

    @Test
    void everySensitiveClassAndTheNotMaskedClassAreKnown() {
        for (String cls : ColumnClassification.SENSITIVE) assertTrue(ClassificationVocabulary.known(cls, DEFAULTS), cls);
        for (String cls : ColumnClassification.NOT_MASKED) assertTrue(ClassificationVocabulary.known(cls, DEFAULTS), cls);
        assertTrue(ClassificationVocabulary.known("INTERNAL", DEFAULTS), "the stated 'needs no masking'");
    }

    @Test
    void noClassificationIsAllowed() {
        assertTrue(ClassificationVocabulary.known(null, DEFAULTS), "absent");
        assertTrue(ClassificationVocabulary.known("", DEFAULTS), "empty");
        assertTrue(ClassificationVocabulary.known("   ", DEFAULTS), "blank");
    }

    @Test
    void aClassAnEntityTypeClaimsAndATypeIdAreKnown() {
        assertFalse(ColumnClassification.SENSITIVE.contains("WALLET"), "known only through its Entity Type");
        assertTrue(ClassificationVocabulary.known("WALLET", DEFAULTS), "the wallet type claims it");
        assertTrue(ClassificationVocabulary.known("TILL", DEFAULTS), "a second class of an unmasked type");
        assertTrue(ClassificationVocabulary.known("id_document", DEFAULTS), "a type id that is not one of its classes");
    }

    @Test
    void caseAndSurroundingSpacesDoNotMatter() {
        assertTrue(ClassificationVocabulary.known("msisdn", DEFAULTS));
        assertTrue(ClassificationVocabulary.known("  Pii ", DEFAULTS));
        assertTrue(ClassificationVocabulary.known(" internal", DEFAULTS));
        assertTrue(ClassificationVocabulary.known(" wallet ", DEFAULTS));
        assertTrue(ClassificationVocabulary.known("Id_Document", DEFAULTS));
    }

    @Test
    void aClassNobodyKnowsIsRefused() {
        assertFalse(ClassificationVocabulary.known("CONFIDENTIAL", DEFAULTS), "the class three shipped templates used");
        assertFalse(ClassificationVocabulary.known("confidential", DEFAULTS));
        assertFalse(ClassificationVocabulary.known("TOP_SECRET_ISH", DEFAULTS), "a made-up word");
        assertFalse(ClassificationVocabulary.known("PUBLIC", DEFAULTS), "not a stated 'needs no masking' either");
    }

    @Test
    void aSchemaFieldWithAnUnknownClassEarnsOneErrorNamingTheColumn() {
        Map<String, Object> draft = Map.of("raw", Map.of("name", "calls", "format", "CSV", "fields", List.of(
                Map.of("name", "A_NUMBER", "selector", "0", "type", "VARCHAR", "classification", "MSISDN"),
                Map.of("name", "CELL", "selector", "1", "type", "VARCHAR", "classification", "CONFIDENTIAL"),
                Map.of("name", "DURATION", "selector", "2", "type", "INTEGER", "classification", "INTERNAL"),
                Map.of("name", "NOTE", "selector", "3", "type", "VARCHAR"))));

        List<Finding> out = ClassificationVocabulary.findings("schema", draft, null);

        assertEquals(1, out.size(), out.toString());
        assertEquals(Severity.ERROR, out.get(0).severity());
        assertEquals(FindingCodes.ERR_UNKNOWN_CLASSIFICATION, out.get(0).code());
        assertEquals("raw.fields[1].classification", out.get(0).fieldPath(), "the row's index, as the sibling checks say it");
        assertTrue(out.get(0).message().contains("'CELL'") && out.get(0).message().contains("'CONFIDENTIAL'"),
                out.get(0).message());
    }

    @Test
    void aPipelineStepFieldWithAnUnknownClassEarnsOneErrorNamingTheColumn() {
        Map<String, Object> draft = Map.of("name", "P", "steps", List.of(
                Map.of("sql", Map.of("fields", List.of(
                        Map.of("name", "CALLER", "expr", "A_NUMBER", "classification", "MSISDN"),
                        Map.of("name", "SITE", "expr", "CELL", "classification", "CONFIDENTIAL"))))));

        List<Finding> out = ClassificationVocabulary.findings("pipeline", draft, null);

        assertEquals(1, out.size(), out.toString());
        assertEquals(Severity.ERROR, out.get(0).severity());
        assertEquals(FindingCodes.ERR_UNKNOWN_CLASSIFICATION, out.get(0).code());
        assertEquals("steps[0].sql.fields[1].classification", out.get(0).fieldPath());
        assertTrue(out.get(0).message().contains("'SITE'"), out.get(0).message());
    }

    @Test
    void aKnownDraftAndAnyOtherConfigTypeSayNothing() {
        Map<String, Object> clean = Map.of("raw", Map.of("name", "calls", "fields", List.of(
                Map.of("name", "A_NUMBER", "classification", "msisdn"), Map.of("name", "NOTE", "classification", ""))));
        assertTrue(ClassificationVocabulary.findings("schema", clean, null).isEmpty());

        Map<String, Object> unknown = Map.of("raw", Map.of("name", "calls", "fields", List.of(
                Map.of("name", "CELL", "classification", "CONFIDENTIAL"))));
        assertEquals(1, ClassificationVocabulary.findings("schema", unknown, null).size(), "the probe is refused as a schema");
        assertTrue(ClassificationVocabulary.findings("grammar", unknown, null).isEmpty(), "not a gated config type");
        assertTrue(ClassificationVocabulary.findings("expectation", unknown, null).isEmpty());
        assertTrue(ClassificationVocabulary.findings("schema", null, null).isEmpty(), "no draft, nothing to say");
    }

    @Test
    void requireRefusesAComponentWithAnUnknownClassAndPassesAKnownOne() {
        Map<String, Object> bad = Map.of("physicalRef", "calls/database", "columns", List.of(
                Map.of("name", "a_party", "type", "string", "classification", "MSISDN"),
                Map.of("name", "cell", "type", "string", "classification", "CONFIDENTIAL")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClassificationVocabulary.require(null, "dataset", "calls", bad));
        assertTrue(e.getMessage().startsWith(FindingCodes.ERR_UNKNOWN_CLASSIFICATION), e.getMessage());
        assertTrue(e.getMessage().contains("'cell'"), e.getMessage());

        assertDoesNotThrow(() -> ClassificationVocabulary.require(null, "dataset", "calls", Map.of("physicalRef", "calls/database",
                "columns", List.of(
                        Map.of("name", "a_party", "type", "string", "classification", "MSISDN"),
                        Map.of("name", "cell", "type", "string", "classification", "CELL_ID"),
                        Map.of("name", "n", "type", "number")))));
    }

    /**
     * A component keeps an unknown class it already stores: only a class the save introduces is refused. The tolerance
     * is that one stored component's (its type and id), by class, in any case.
     */
    @Test
    void requireKeepsAClassTheStoredComponentAlreadyCarriesAndRefusesANewOne(@TempDir Path root) throws Exception {
        new ComponentStore(root.resolve("registry")).write("dataset", "legacy", Map.of("physicalRef", "calls/database",
                "columns", List.of(Map.of("name", "cell", "type", "string", "classification", "CONFIDENTIAL"))));
        Map<String, Object> same = Map.of("physicalRef", "calls/database", "description", "edited", "columns", List.of(
                Map.of("name", "cell", "type", "string", "classification", " confidential ")));
        Map<String, Object> second = Map.of("physicalRef", "calls/database", "columns", List.of(
                Map.of("name", "cell", "type", "string", "classification", "CONFIDENTIAL"),
                Map.of("name", "site", "type", "string", "classification", "SECRETISH")));

        assertDoesNotThrow(() -> ClassificationVocabulary.require(root, "dataset", "legacy", same));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClassificationVocabulary.require(root, "dataset", "legacy", second));
        assertTrue(e.getMessage().contains("'SECRETISH'") && !e.getMessage().contains("'CONFIDENTIAL'"),
                "only the class the save introduces is named: " + e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> ClassificationVocabulary.require(root, "dataset", "another", same),
                "another Dataset does not inherit it");
        assertThrows(IllegalArgumentException.class, () -> ClassificationVocabulary.require(root, "schema", "legacy", same),
                "nor a component of another kind with the same id");
        assertThrows(IllegalArgumentException.class, () -> ClassificationVocabulary.require(root, "dataset", null, same),
                "no id: nothing is stored");
        assertThrows(IllegalArgumentException.class, () -> ClassificationVocabulary.require(null, "dataset", "legacy", same),
                "no Space root: nothing is stored");
    }

    /** A Space's own Entity Types widen the vocabulary for that Space alone; a stated list replaces the built-in ones. */
    @Test
    void aSpacesOwnEntityTypeMakesItsClassKnownThereAndNowhereElse(@TempDir Path root, @TempDir Path other) throws Exception {
        List<EntityType> own = List.of(new EntityType("badge", "Staff badge", "upper-trim", true, List.of("BADGE_NO")));
        new LinkAnalysisSettings(null, null, null, null, null, null, own, null, null, null, null, null, null, null, null)
                .write(root.resolve(LinkAnalysisSettings.FILE));

        assertEquals(own, ClassificationVocabulary.typesOf(root));
        assertEquals(EntityTypes.DEFAULTS, ClassificationVocabulary.typesOf(other), "no link-analysis.toon: the built-in types");
        assertEquals(EntityTypes.DEFAULTS, ClassificationVocabulary.typesOf(null), "no Space root: the built-in types");

        assertTrue(ClassificationVocabulary.known("BADGE_NO", ClassificationVocabulary.typesOf(root)));
        assertTrue(ClassificationVocabulary.known("badge", ClassificationVocabulary.typesOf(root)), "its id");
        assertFalse(ClassificationVocabulary.known("BADGE_NO", ClassificationVocabulary.typesOf(other)));
        assertFalse(ClassificationVocabulary.known("BADGE_NO", DEFAULTS));
        assertFalse(ClassificationVocabulary.known("WALLET", ClassificationVocabulary.typesOf(root)),
                "the stated list replaced the built-in type that claimed it");
        assertTrue(ClassificationVocabulary.known("MSISDN", ClassificationVocabulary.typesOf(root)), "SENSITIVE needs no type");

        Map<String, Object> draft = Map.of("raw", Map.of("name", "staff", "fields", List.of(
                Map.of("name", "BADGE", "classification", "BADGE_NO"))));
        assertTrue(ClassificationVocabulary.findings("schema", draft, root).isEmpty(), "known in the Space that defines it");
        assertEquals(1, ClassificationVocabulary.findings("schema", draft, other).size(), "refused in a Space that does not");
    }

    /**
     * {@code known} states the Entity Type rule itself, because {@code com.gamma.service} does not import this package;
     * {@link OnboardMatches#typeOf} is the reader it mirrors, and the two must not drift.
     */
    @Test
    void theEntityTypeRuleAgreesWithTheOnboardingReader() {
        for (String cls : List.of("WALLET", " till ", "id_document", "Id_Document", "CONFIDENTIAL", "wallets")) {
            String norm = ColumnClassification.normalise(cls);
            assertFalse(ColumnClassification.SENSITIVE.contains(norm) || ColumnClassification.NOT_MASKED.contains(norm),
                    cls + " is known through an Entity Type or not at all");
            assertEquals(OnboardMatches.typeOf(cls, DEFAULTS) != null, ClassificationVocabulary.known(cls, DEFAULTS), cls);
        }
        assertNotNull(OnboardMatches.typeOf("WALLET", DEFAULTS), "a claimed class");
        assertNotNull(OnboardMatches.typeOf("id_document", DEFAULTS), "a type id");
        assertNull(OnboardMatches.typeOf("CONFIDENTIAL", DEFAULTS), "an unknown class");
        assertNull(OnboardMatches.typeOf("  ", DEFAULTS), "blank: no Entity Type reads it");
        assertTrue(ClassificationVocabulary.known("  ", DEFAULTS), "and the vocabulary allows it as 'none stated'");
    }

    /**
     * MASKING-UNION-RESIDUALS-1: a vocabulary entry names the type its class names by the one rule ({@link EntityTypes#typeNamedBy}),
     * the type that CLAIMS it before an earlier type whose id it is, as onboarding reads it; masked by the one masking rule.
     */
    @Test
    void anEntryNamesTheClaimingTypeBeforeAnEarlierTypeWhoseIdItIs() {
        List<EntityType> types = List.of(new EntityType("wallet", "Wallet", "upper-trim", true, List.of("MOBILE_MONEY")),
                new EntityType("account", "Account", "upper-trim", false, List.of("WALLET")));
        ClassificationVocabulary.Entry wallet = ClassificationVocabulary.vocabulary(types).stream()
                .filter(e -> e.cls().equals("WALLET")).findFirst().orElseThrow();
        assertEquals("account", wallet.entityType());
        assertEquals("account", OnboardMatches.typeOf("WALLET", types));
        assertTrue(wallet.masked(), "still the id of a masked type: it masks");
        assertEquals(ClassificationVocabulary.SOURCE_ENTITY_TYPE, wallet.source());
        // with no claim, the type whose id it is
        List<EntityType> alone = List.of(types.get(0));
        assertEquals("wallet", ClassificationVocabulary.vocabulary(alone).stream()
                .filter(e -> e.cls().equals("WALLET")).findFirst().orElseThrow().entityType());
        assertEquals("wallet", OnboardMatches.typeOf(" Wallet ", alone));
    }

    // ── findingsWritten: the files a bulk import landed (CLASSIFICATION-BULK-IMPORT-UNGATED-1) ──

    private static final String SCHEMA = """
            raw:
              name: calls
              format: CSV
              fields[2]{name,selector,type,classification}:
                A_NUMBER,"0",VARCHAR,MSISDN
                CELL,"1",VARCHAR,%s
            """;

    private static Path put(Path root, String rel, String text) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, text);
    }

    /** A schema file is whatever a Pipeline names, so every written {@code .toon} outside the registry is judged. */
    @Test
    void aWrittenSchemaFileUnderAnyNameAndAPipelineStepAreJudgedAndNamedByFile(@TempDir Path root) throws Exception {
        put(root, "calls/calls_schema.toon", SCHEMA.formatted("CONFIDENTIAL"));
        put(root, "calls/calls_v2.toon", SCHEMA.formatted("SECRETISH"));
        put(root, "calls/clean_schema.toon", SCHEMA.formatted("INTERNAL"));
        put(root, "calls/calls_pipeline.toon", ConfigCodec.toToon(Map.of("name", "CALLS", "steps", List.of(
                Map.of("sql", Map.of("fields", List.of(
                        Map.of("name", "SITE", "expr", "CELL", "classification", "CONFIDENTIAL"))))))));
        put(root, "calls/untouched_schema.toon", SCHEMA.formatted("CONFIDENTIAL"));
        String broken = "raw:\n  fields[2]{name,classification}:\n    CELL,CONFIDENTIAL\n";
        assertThrows(RuntimeException.class, () -> ConfigCodec.toMap(broken), "the probe: this file does not parse");
        put(root, "calls/broken_schema.toon", broken);

        List<Finding> out = ClassificationVocabulary.findingsWritten(root, List.of("calls/calls_schema.toon",
                "calls/calls_v2.toon", "calls/clean_schema.toon", "calls/calls_pipeline.toon", "calls/broken_schema.toon"));

        assertEquals(List.of("calls/calls_schema.toon: raw.fields[1].classification",
                        "calls/calls_v2.toon: raw.fields[1].classification",
                        "calls/calls_pipeline.toon: steps[0].sql.fields[0].classification"),
                out.stream().map(Finding::fieldPath).toList(),
                "one per unknown class, in written order; a file not written, a known class and an unparseable file say nothing");
        for (Finding f : out) {
            assertEquals(Severity.ERROR, f.severity());
            assertEquals(FindingCodes.ERR_UNKNOWN_CLASSIFICATION, f.code());
        }
        assertTrue(out.get(0).message().contains("'CELL'") && out.get(0).message().contains("'CONFIDENTIAL'"), out.get(0).message());
        assertTrue(ClassificationVocabulary.findingsWritten(root, List.of("calls/clean_schema.toon")).isEmpty());
    }

    /** The fields of a split schema live in its sibling structure CSV: either file written, the merged schema is judged. */
    @Test
    void aSchemaIsJudgedMergedWithItsSiblingStructureCsv(@TempDir Path root) throws Exception {
        String csv = StructureCsv.encode(List.of(
                Map.of("name", "A_NUMBER", "type", "VARCHAR", "selector", "0", "classification", "MSISDN"),
                Map.of("name", "CELL", "type", "VARCHAR", "selector", "1", "classification", "CONFIDENTIAL")));
        put(root, "calls/calls_schema.toon", "raw:\n  name: calls\n  format: CSV\n");
        put(root, "calls/calls_structure.csv", csv);
        put(root, "bare/legacy.toon", "partitionKey: D\n");
        put(root, "bare/legacy_structure.csv", csv);
        put(root, "orphan/none_structure.csv", csv);

        for (String written : List.of("calls/calls_schema.toon", "calls/calls_structure.csv")) {
            List<Finding> out = ClassificationVocabulary.findingsWritten(root, List.of(written));
            assertEquals(List.of("calls/calls_schema.toon: raw.fields[1].classification"),
                    out.stream().map(Finding::fieldPath).toList(), written);
        }
        assertEquals(1, ClassificationVocabulary.findingsWritten(root,
                List.of("calls/calls_schema.toon", "calls/calls_structure.csv")).size(), "both written: judged once");
        assertEquals(List.of("bare/legacy.toon: raw.fields[1].classification"),
                ClassificationVocabulary.findingsWritten(root, List.of("bare/legacy_structure.csv")).stream()
                        .map(Finding::fieldPath).toList(), "a schema with no _schema suffix and no raw block of its own");
        assertTrue(ClassificationVocabulary.findingsWritten(root, List.of("orphan/none_structure.csv")).isEmpty(),
                "a structure CSV with no schema file beside it is read by nothing");

        Files.writeString(root.resolve("calls/calls_structure.csv"), csv.replace("CONFIDENTIAL", "INTERNAL"));
        assertTrue(ClassificationVocabulary.findingsWritten(root, List.of("calls/calls_structure.csv")).isEmpty());
    }

    /** In the registry only a Dataset and a schema carry column classes; nothing stored excuses the file itself. */
    @Test
    void aWrittenRegistryDatasetOrSchemaIsJudgedAndAnyOtherKindIsNot(@TempDir Path root) throws Exception {
        String dataset = """
                physicalRef: calls/database
                columns[2]{name,type,classification}:
                  a_party,string,MSISDN
                  cell,string,%s
                """;
        put(root, "registry/datasets/calls.toon", dataset.formatted("CONFIDENTIAL"));
        put(root, "registry/datasets/clean.toon", dataset.formatted("INTERNAL"));
        put(root, "registry/schemas/calls.toon", SCHEMA.formatted("CONFIDENTIAL"));
        put(root, "registry/widgets/map.toon", "title: Map\nclassification: quantile\n");

        List<Finding> out = ClassificationVocabulary.findingsWritten(root, List.of("registry/datasets/calls.toon",
                "registry/datasets/clean.toon", "registry/schemas/calls.toon", "registry/widgets/map.toon"));

        assertEquals(List.of("registry/datasets/calls.toon: columns[1].classification",
                "registry/schemas/calls.toon: raw.fields[1].classification"), out.stream().map(Finding::fieldPath).toList());
        assertThrows(IllegalArgumentException.class, () -> ClassificationVocabulary.require(root, "dataset", null,
                Map.of("classification", "quantile")), "the probe: the widget's word is unknown, so it was skipped, not passed");
    }

    /** The Entity Types are read from the root after the writes, so a bundle's own link-analysis.toon counts. */
    @Test
    void writtenFilesAreJudgedAgainstTheEntityTypesNowOnDisk(@TempDir Path root) throws Exception {
        put(root, "staff/staff_schema.toon", SCHEMA.formatted("BADGE_NO"));
        assertEquals(1, ClassificationVocabulary.findingsWritten(root, List.of("staff/staff_schema.toon")).size(),
                "no Entity Type claims it yet");

        List<EntityType> own = List.of(new EntityType("badge", "Staff badge", "upper-trim", true, List.of("BADGE_NO")));
        new LinkAnalysisSettings(null, null, null, null, null, null, own, null, null, null, null, null, null, null, null)
                .write(root.resolve(LinkAnalysisSettings.FILE));
        assertTrue(ClassificationVocabulary.findingsWritten(root,
                List.of(LinkAnalysisSettings.FILE, "staff/staff_schema.toon")).isEmpty(), "the Space's own type claims it");
    }
}
