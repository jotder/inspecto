package com.gamma.pipeline;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.etl.TransformCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link MappingRules} — the authoring-time gate for a mapping component's rules (S6b). */
class MappingRulesTest {

    private static Map<String, Object> rule(String target, String source, String type) {
        return Map.of("targetColumn", target, "sourceExpression", source, "transformType", type);
    }

    private static List<String> paths(List<Finding> findings) {
        return findings.stream().map(Finding::fieldPath).toList();
    }

    @Test
    void cleanRulesProduceNoFindings() {
        assertEquals(List.of(), MappingRules.validate(List.of(
                rule("MSISDN", "msisdn", "DIRECT"),
                rule("IMSI", "imsi", ""))));
    }

    @Test
    void emptyRuleSetIsAnError() {
        List<Finding> f = MappingRules.validate(List.of());
        assertEquals(1, f.size());
        assertEquals(Severity.ERROR, f.get(0).severity());
        assertEquals("", f.get(0).fieldPath(), "a whole-set finding carries no cell anchor");
        assertEquals(f, MappingRules.validate(null), "null reads the same as empty");
    }

    @Test
    void blankTargetColumnIsAnError() {
        assertEquals(List.of("rules[0].targetColumn"),
                paths(MappingRules.validate(List.of(rule("", "msisdn", "DIRECT")))));
    }

    @Test
    void blankSourceExpressionIsAnError() {
        assertEquals(List.of("rules[0].sourceExpression"),
                paths(MappingRules.validate(List.of(rule("MSISDN", "  ", "DIRECT")))));
    }

    @Test
    void duplicateTargetColumnIsAnErrorOnTheSecondRuleAndNamesTheFirst() {
        List<Finding> f = MappingRules.validate(List.of(
                rule("MSISDN", "a", "DIRECT"), rule("MSISDN", "b", "DIRECT")));
        assertEquals(List.of("rules[1].targetColumn"), paths(f));
        assertTrue(f.get(0).message().contains("rule 1"), f.get(0).message());
    }

    @Test
    void unknownTransformTypeIsAnErrorAndSuppressesTheTypeSpecificChecks() {
        List<Finding> f = MappingRules.validate(List.of(rule("MSISDN", "a", "EXPER")));
        assertEquals(List.of("rules[0].transformType"), paths(f));
        assertTrue(f.get(0).message().contains("EXPER"), "the typo is echoed back");
    }

    @Test
    void theAcceptedVocabularyIsTransformCompilersOwnSet() {
        for (String type : TransformCompiler.TRANSFORM_TYPES) {
            String target = "FILENAME_DATE".equals(type) ? "EVENT_DATE" : "COL";
            String source = "CONCAT_DT".equals(type) ? "d|t" : "src";
            List<Finding> f = MappingRules.validate(List.of(rule(target, source, type)));
            if ("EXPR".equals(type)) {
                // legal, but flagged — the one type-specific case that IS a finding, deliberately WARNING
                assertEquals(1, f.size(), "compiler accepts " + type + " but the validator does not");
                assertEquals(Severity.WARNING, f.get(0).severity());
            } else {
                assertEquals(List.of(), f, "compiler accepts " + type + " but the validator does not");
            }
        }
    }

    @Test
    void transformTypeIsCaseInsensitiveLikeTheCompiler() {
        List<Finding> f = MappingRules.validate(List.of(rule("MSISDN", "a", "expr")));
        assertEquals(1, f.size());
        assertEquals(Severity.WARNING, f.get(0).severity(), "still recognised as EXPR, lowercased");
    }

    @Test
    void blankTransformTypeMeansDirect() {
        assertEquals(List.of(), MappingRules.validate(List.of(rule("MSISDN", "a", ""))));
        assertEquals(List.of(), MappingRules.validate(
                List.of(Map.of("targetColumn", "MSISDN", "sourceExpression", "a"))));
    }

    @Test
    void concatDtWithoutTheSeparatorIsAnError() {
        // TransformCompiler.concatDt reads split part 2 unconditionally — without '|' that throws.
        assertEquals(List.of("rules[0].sourceExpression"),
                paths(MappingRules.validate(List.of(rule("EVENT_TS", "dateCol", "CONCAT_DT")))));
        assertEquals(List.of(), MappingRules.validate(List.of(rule("EVENT_TS", "d|t", "CONCAT_DT"))));
    }

    @Test
    void filenameDateOnlyTargetsEventDate() {
        assertEquals(List.of("rules[0].targetColumn"),
                paths(MappingRules.validate(List.of(rule("SOME_COL", "f|p|%Y%m%d", "FILENAME_DATE")))));
        assertEquals(List.of(),
                MappingRules.validate(List.of(rule("EVENT_DATE", "f|p|%Y%m%d", "FILENAME_DATE"))));
    }

    @Test
    void anExprsSqlIsNotValidatedButIsFlaggedAsUnaudited() {
        // Deliberate: TransformCompiler emits EXPR verbatim and documents it as operator-trusted — the
        // bad SQL itself draws no ERROR. It does draw the one WARNING every EXPR rule draws: this bypasses
        // the batch's cast-failure audit (sql-only-transform-feasibility.md §5/§6 step 1).
        List<Finding> f = MappingRules.validate(List.of(rule("X", "SELECT ( not sql", "EXPR")));
        assertEquals(1, f.size());
        assertEquals(Severity.WARNING, f.get(0).severity());
        assertEquals("rules[0].transformType", f.get(0).fieldPath());
        assertTrue(f.get(0).message().contains("cast-failure audit"), f.get(0).message());
    }

    @Test
    void everyRuleIsReportedNotJustTheFirst() {
        assertEquals(List.of("rules[0].targetColumn", "rules[1].sourceExpression"),
                paths(MappingRules.validate(List.of(rule("", "a", ""), rule("B", "", "")))));
    }

    @Test
    void valuesAreTrimmedAndNonStringsCoerced() {
        assertEquals(List.of(), MappingRules.validate(List.of(
                Map.of("targetColumn", " MSISDN ", "sourceExpression", " msisdn ", "transformType", " "))));
        // a CSV import can hand back a number; it must read as its text, not blow up
        assertEquals(List.of(), MappingRules.validate(List.of(
                Map.of("targetColumn", "COL1", "sourceExpression", 42))));
    }
}
