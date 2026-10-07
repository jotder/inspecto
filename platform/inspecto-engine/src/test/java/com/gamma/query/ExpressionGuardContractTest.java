package com.gamma.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cross-language contract for a calculated column's expression grammar: every vocabulary
 * {@link ExpressionGuard} enforces must equal the one the Angular authoring form gives feedback from.
 *
 * <p><b>Why it is needed.</b> {@code calculated-column-guard.ts} is a mirror of this class, written so an
 * author sees an illegal expression inline instead of after a save-then-query round trip. It is explicitly
 * NOT authoritative — {@link DatasetRelation#withCalculated} re-validates and is the only enforcement that
 * matters for safety — but an out-of-date mirror is still a real defect in both directions: too permissive
 * and the form greenlights an expression the engine then refuses, too strict and it refuses one the engine
 * would happily run. Six separate keyword sets plus a token alphabet were being kept in step by hand, which
 * is precisely the shape this repo has been bitten by before (the {@code DERIVED_USE} map drifted three
 * times before it was pinned).
 *
 * <p><b>Why a committed file rather than generation</b> — the same reasoning as
 * {@code MeasureGrammarContractTest}: both sides compare to ONE checked-in artifact (this test against the
 * constants below, the TS mirror by <em>importing</em> the very same JSON), so a divergence shows up as a
 * reviewable diff on the contract file instead of being absorbed by whichever side ran a generator. The TS
 * side importing it means drift there is structurally impossible; this test is what keeps THIS side honest.
 *
 * <p>⚠ Sets are compared as sets, deliberately. Unlike the measure grammar — whose list is joined verbatim
 * into a user-facing message, making order user-visible — both guards sort before rendering, so a
 * reordering here is not a behavioural change. The contract file is kept alphabetical for readability only.
 */
class ExpressionGuardContractTest {

    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/contracts/expression-guard.contract.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Walk up from the module's CWD to the repo root, so the path works under surefire and an IDE alike. */
    private static Path contractPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CONTRACT);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + CONTRACT + " from " + Path.of("").toAbsolutePath());
    }

    private static Map<?, ?> contract() throws IOException {
        return JSON.readValue(contractPath().toFile(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> published(String key) throws IOException {
        Object v = contract().get(key);
        assertNotNull(v, "contract file has no `" + key + "` key");
        return Set.copyOf((List<String>) v);
    }

    private static String disagree(String key) {
        return "ExpressionGuard." + key + " and " + CONTRACT + " disagree — the authoring form and the "
                + "engine would then accept different expressions; decide which is right, then update both";
    }

    @Test
    void theDeniedKeywordsMatchTheCommittedContract() throws IOException {
        assertEquals(ExpressionGuard.DENIED, published("denied"), disagree("DENIED"));
    }

    @Test
    void theFlowKeywordsMatchTheCommittedContract() throws IOException {
        assertEquals(ExpressionGuard.FLOW_KEYWORDS, published("flowKeywords"), disagree("FLOW_KEYWORDS"));
    }

    @Test
    void theCallableFunctionsMatchTheCommittedContract() throws IOException {
        assertEquals(ExpressionGuard.FUNCTIONS, published("functions"), disagree("FUNCTIONS"));
    }

    /** ⚠ A name here but not in FUNCTIONS is callable ONLY with a trailing {@code OVER (…)}. */
    @Test
    void theWindowFunctionsMatchTheCommittedContract() throws IOException {
        assertEquals(ExpressionGuard.WINDOW_FUNCTIONS, published("windowFunctions"), disagree("WINDOW_FUNCTIONS"));
    }

    @Test
    void theWindowKeywordsMatchTheCommittedContract() throws IOException {
        assertEquals(ExpressionGuard.WINDOW_KEYWORDS, published("windowKeywords"), disagree("WINDOW_KEYWORDS"));
    }

    @Test
    void theCastTargetTypesMatchTheCommittedContract() throws IOException {
        assertEquals(ExpressionGuard.TYPES, published("types"), disagree("TYPES"));
    }

    /**
     * The token alphabet is the whole first rule — anything it cannot lex is rejected outright — so a
     * mirror that lexes even one extra character shape gives feedback on a different language.
     * {@code Pattern.pattern()} and JS {@code RegExp.source} produce the identical string for this
     * expression, which is why one contract value can serve both.
     */
    @Test
    void theTokenAlphabetMatchesTheCommittedContract() throws IOException {
        assertEquals(contract().get("token"), ExpressionGuard.TOKEN.pattern(), disagree("TOKEN"));
    }

    @Test
    void theLengthCapMatchesTheCommittedContract() throws IOException {
        assertEquals(contract().get("maxLength"), ExpressionGuard.MAX_LENGTH, disagree("MAX_LENGTH"));
    }
}
