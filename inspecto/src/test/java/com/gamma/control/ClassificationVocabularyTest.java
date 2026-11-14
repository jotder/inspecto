package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.EntityTypes.EntityType;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.ColumnClassification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
