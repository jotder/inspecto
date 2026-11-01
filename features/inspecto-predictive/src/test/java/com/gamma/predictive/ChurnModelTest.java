package com.gamma.predictive;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code churn-model} kind's fail-closed parse: defaults, and every refusal naming its field. */
class ChurnModelTest {

    private static String refusal(Consumer<Map<String, Object>> edit) {
        Map<String, Object> m = new LinkedHashMap<>(ChurnCorpus.model());
        edit.accept(m);
        return assertThrows(IllegalArgumentException.class, () -> ChurnModel.fromMap("c", m)).getMessage();
    }

    private static String inputRefusal(int i, Consumer<Map<String, Object>> edit) {
        return refusal(m -> {
            List<Object> inputs = new ArrayList<>((List<?>) m.get("inputs"));
            @SuppressWarnings("unchecked")
            Map<String, Object> in = new LinkedHashMap<>((Map<String, Object>) inputs.get(i));
            edit.accept(in);
            inputs.set(i, in);
            m.put("inputs", inputs);
        });
    }

    @Test
    void parsesTheCorpusModelAndItsDefaults() {
        ChurnModel m = ChurnModel.fromMap("churn", ChurnCorpus.model());
        assertEquals("churn_scores_churn", m.scoresDataset());
        assertEquals("churn_scores_churn_latest", m.latestDataset());
        assertEquals(List.of("quiet", "orders", "spend"), m.inputs().stream().map(ChurnModel.Input::name).toList());
        assertEquals(ChurnModel.Transform.CAP, m.inputs().get(0).transform());
        assertEquals(Double.valueOf(0.0), m.inputs().get(1).valueWhenAbsent(), "count over no rows is 0");
        assertEquals(Double.valueOf(120.0), m.inputs().get(0).valueWhenAbsent(), "recency takes its declared missing");

        Map<String, Object> bare = new LinkedHashMap<>(ChurnCorpus.model());
        bare.remove("minHistoryDays");
        bare.remove("elevatedThreshold");
        bare.remove("highThreshold");
        bare.remove("window");
        ChurnModel d = ChurnModel.fromMap("d", bare);
        assertEquals(30, d.minHistoryDays());
        assertEquals(60, d.elevatedThreshold());
        assertEquals(80, d.highThreshold());
        assertNull(d.window(), "absent = all history up to as_of");
    }

    @Test
    void everyRefusalNamesItsField() {
        assertTrue(refusal(m -> m.put("weights", 1)).contains("unknown key(s) [weights]"));
        assertTrue(refusal(m -> m.remove("intercept")).contains("churn-model.intercept is required"));
        assertTrue(refusal(m -> m.put("intercept", "NaN")).contains("churn-model.intercept must be a finite number"));
        assertTrue(refusal(m -> m.put("inputs", List.of())).contains("inputs must be a non-empty list"));
        assertTrue(refusal(m -> m.put("highThreshold", 50)).contains("0 < elevatedThreshold < highThreshold <= 100"));
        assertTrue(refusal(m -> m.put("minHistoryDays", 0)).contains("minHistoryDays must be a whole number in 1..3660"));
        assertTrue(refusal(m -> m.put("window", 4000)).contains("window must be a whole number in 1..3660"));
        assertTrue(refusal(m -> m.put("key", "a;b")).contains("churn-model.key"));
        assertThrows(IllegalArgumentException.class, () -> ChurnModel.fromMap("bad-id", ChurnCorpus.model()),
                "the id names output Datasets: no hyphen");

        assertTrue(inputRefusal(0, in -> in.put("weight", 1)).contains("inputs[0] has unknown key(s) [weight]"));
        assertTrue(inputRefusal(0, in -> in.remove("cap")).contains("inputs[0].cap is required by transform cap"));
        assertTrue(inputRefusal(1, in -> in.put("cap", 5)).contains("inputs[1].cap is required by transform cap and refused"));
        assertTrue(inputRefusal(2, in -> in.remove("scale")).contains("inputs[2].mean and .scale"));
        assertTrue(inputRefusal(2, in -> in.put("scale", 0)).contains("inputs[2].scale must be > 0"));
        assertTrue(inputRefusal(1, in -> in.put("missing", 3)).contains("inputs[1].missing is refused on count"));
        assertTrue(inputRefusal(1, in -> in.put("transform", "sqrt")).contains("inputs[1].transform must be none, log, cap or z"));
        assertTrue(inputRefusal(1, in -> in.put("measure", "median(amount)")).contains("inputs[1].measure must be count"));
        assertTrue(inputRefusal(1, in -> in.put("measure", "sum(a b)")).contains("inputs[1].measure field"));
        assertTrue(inputRefusal(1, in -> in.remove("coefficient")).contains("inputs[1].coefficient is required"));
        assertTrue(inputRefusal(1, in -> in.put("coefficient", 1e9)).contains("inputs[1].coefficient must be a finite number"));
        assertTrue(inputRefusal(1, in -> in.put("name", "quiet")).contains("inputs[1].name 'quiet' is not unique"));
    }
}
