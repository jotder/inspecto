package com.gamma.ops;

import com.gamma.decision.ConsequenceProvider;
import com.gamma.decision.Consequences;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CreateIncidentConsequenceTest {

    @Test
    void theOpsModuleContributesCreateIncidentThroughTheServiceLoader() {
        ConsequenceProvider p = Consequences.load(getClass().getClassLoader()).find("create-incident").orElseThrow();
        assertEquals("object", p.group());
        assertEquals(java.util.List.of("objects"), p.requires());
    }
}
