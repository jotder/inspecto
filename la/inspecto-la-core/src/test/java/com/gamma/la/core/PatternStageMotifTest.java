package com.gamma.la.core;

import com.gamma.graph.TemporalMotifMatcher;
import com.gamma.la.core.PatternQueryCompiler.Stage;
import com.gamma.la.core.PatternQueryCompiler.Threshold;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA's stage → the platform {@link TemporalMotifMatcher} stage: every field carried, the node kind folded to {@code admitsNodes}. */
class PatternStageMotifTest {

    private static Stage stage(String nodeKind) {
        return new Stage(true, 3, "transfer", nodeKind, new Threshold("AMOUNT", 900.0, 1000.0), 24.0, true, 48.0, true);
    }

    @Test
    void everyMatcherFieldIsCarried() {
        assertEquals(new TemporalMotifMatcher.Stage(true, 3, 24.0, true, 48.0, true, true), stage(null).motif());
        Stage plain = new Stage(false, 2, null, null, null, null, false, null, false);
        assertEquals(new TemporalMotifMatcher.Stage(false, 2, null, false, null, false, true), plain.motif());
    }

    @Test
    void onlyNoKindOrTheEntityKindAdmitsNodes() {
        assertTrue(stage(null).motif().admitsNodes());
        assertTrue(stage(PatternQueryCompiler.ENTITY_KIND).motif().admitsNodes());
        assertFalse(stage("account").motif().admitsNodes(), "every projected node is an entity");
    }
}
