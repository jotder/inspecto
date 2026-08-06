package com.gamma.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cross-language contract for the {@code transform.summarize} measure grammar: the aggregates this
 * engine accepts must equal the set the Angular authoring form validates against.
 *
 * <p><b>Why it is needed.</b> A pipeline carrying {@code processing.summarize} never parses its measures —
 * the block is authoring-only until the branch-aware executor arms it, and the only code that reads the
 * {@code count | agg(field)} shorthand is {@link com.gamma.job.MaterializeTask}, a separate maintenance
 * Job on its own schedule. So a bad measure is caught nowhere near where it was written, which is why the
 * UI validates it up front. That UI validation is only as correct as its copy of this list.
 *
 * <p><b>Why a committed file rather than generation</b> — the same reasoning as
 * {@code NodeAttributesContractTest}: both sides compare to ONE checked-in artifact (this test against
 * {@link MeasureCompiler#AGGS}, {@code measure-grammar.spec.ts} against the same JSON), so a divergence
 * shows up as a reviewable diff on the contract file instead of being absorbed by whichever side ran a
 * generator.
 */
class MeasureGrammarContractTest {

    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/mock/measure-grammar.contract.json";

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

    @SuppressWarnings("unchecked")
    @Test
    void theAggregatesTheEngineAcceptsMatchTheCommittedContract() throws IOException {
        Map<String, Object> contract = JSON.readValue(contractPath().toFile(), Map.class);
        List<String> published = (List<String>) contract.get("aggregations");

        assertNotNull(published, "contract file has no `aggregations` key");
        // Order-sensitive: the UI joins this list verbatim into its error message ("use count,
        // countDistinct, …"), so a reordering is a user-visible change and should be a deliberate diff.
        assertEquals(MeasureCompiler.AGGS, published,
                "MeasureCompiler.AGGS and " + CONTRACT + " disagree — decide which is right, then update both");
    }

    /** The contract is only meaningful if these names really are what {@code parse} accepts. */
    @Test
    void everyContractAggregateIsAcceptedAndAnythingElseIsRefused() {
        for (String agg : MeasureCompiler.AGGS) {
            Map<String, Object> body = Map.of(
                    "dataset", "orders",
                    "measures", List.of(Map.of("agg", agg, "field", "amount")));
            assertDoesNotThrow(() -> MeasureCompiler.compile(MeasureCompiler.parse(body, 100, 1000)),
                    "contract lists '" + agg + "' but the compiler refuses it");
        }

        Map<String, Object> unknown = Map.of(
                "dataset", "orders",
                "measures", List.of(Map.of("agg", "median", "field", "amount")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MeasureCompiler.parse(unknown, 100, 1000));
        assertTrue(e.getMessage().contains("median"), () -> "unhelpful refusal: " + e.getMessage());
    }
}
