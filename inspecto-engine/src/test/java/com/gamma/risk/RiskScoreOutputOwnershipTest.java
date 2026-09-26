package com.gamma.risk;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scores output can never destroy data it does not own: the name is derived (never authored), and the
 * writer refuses a directory it did not create, deleting only its own {@code scores-*.parquet} in one it did.
 */
class RiskScoreOutputOwnershipTest {

    private static RiskScoreModel model(String id) {
        return RiskScoreModel.fromMap(id, RiskCorpus.model());
    }

    private static List<RiskScorer.Scored> one(RiskScoreModel m) {
        return List.of(RiskScorer.score(m, "m1", Map.of("sim_swaps", 1.0), Map.of()));
    }

    @Test
    void theOutputNameIsDerivedAndCannotBeAuthored() {
        assertEquals("risk_scores_subs", model("subs").scoresDataset());
        assertEquals("risk_scores_subs_latest", model("subs").latestDataset());
        Map<String, Object> m = new java.util.LinkedHashMap<>(RiskCorpus.model());
        m.put("scoresDataset", "orders");
        String msg = assertThrows(IllegalArgumentException.class, () -> RiskScoreModel.fromMap("subs", m)).getMessage();
        assertTrue(msg.contains("not authorable"), msg);
        assertThrows(IllegalArgumentException.class, () -> model("a-b"), "'-' would make a-b and a_b share an output");
    }

    @Test
    void aDirectoryTheModelDidNotCreateIsRefusedAndLeftIntact(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        RiskScoreModel m = model("subs");
        Path foreign = Files.createDirectories(data.resolve("risk_scores_subs_latest"));
        Path precious = Files.writeString(foreign.resolve("data.parquet"), "someone else's rows");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RiskScoreEvaluator.write(data, m, "v", "r1", Instant.now(), one(m)));
        assertTrue(e.getMessage().contains("not created by this model"), e.getMessage());
        assertEquals("someone else's rows", Files.readString(precious), "nothing deleted");
    }

    @Test
    void anotherModelsOutputIsRefusedAndOwnOutputIsReplacedOnlyByName(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        RiskScoreModel m = model("subs");
        RiskScoreEvaluator.write(data, m, "v", "r1", Instant.now(), one(m));
        Path latest = data.resolve("risk_scores_subs_latest");
        Path stray = Files.writeString(latest.resolve("keep.parquet"), "not a scores file");
        RiskScoreEvaluator.write(data, m, "v", "r2", Instant.now(), one(m));
        assertTrue(Files.exists(stray), "only scores-*.parquet is swapped out");

        Files.writeString(latest.resolve(RiskScoreEvaluator.OWNER_MARKER), "other");
        assertThrows(IllegalStateException.class,
                () -> RiskScoreEvaluator.write(data, m, "v", "r3", Instant.now(), one(m)),
                "a directory marked by another model is not this model's");
    }
}
