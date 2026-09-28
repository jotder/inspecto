package com.gamma.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** S2-1: a contributed node type with no declared {@link ExecutionMode} is refused at arming. */
class PipelineValidatorModeTest {

    private static final String OWNER = "mode.test.jar";

    private record Undeclared(String type) implements PipelineNodeType {}

    private record Executed(String type) implements PipelineNodeType {
        @Override public Optional<ExecutionMode> mode() { return Optional.of(ExecutionMode.EXECUTED); }
    }

    private static PipelineGraph through(String type) {
        return new PipelineGraph("moded", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("n", type),
                        PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "n"), PipelineEdge.data("n", "sink")));
    }

    private static boolean flagged(PipelineGraph g) {
        return PipelineValidator.validate(g).errors().stream()
                .anyMatch(i -> PipelineValidator.NODE_MODE_UNDECLARED.equals(i.code()));
    }

    @Test
    void anUndeclaredContributedTypeIsRefusedAtArming() {
        PipelineNodeTypes.register(new Undeclared("transform.acme_nomode"), OWNER);
        try {
            PipelineGraph g = through("transform.acme_nomode");
            assertTrue(flagged(g), () -> "expected NODE_MODE_UNDECLARED, got " + PipelineValidator.validate(g).issues());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> PipelineValidator.validateOrThrow(g));
            assertTrue(e.getMessage().contains("NODE_MODE_UNDECLARED"), e.getMessage());
        } finally {
            PipelineNodeTypes.deregister(OWNER);
        }
    }

    @Test
    void anExecutedContributedTypeAndBuiltInsArmAsBefore() {
        PipelineNodeTypes.register(new Executed("transform.acme_exec_mode"), OWNER);
        try {
            assertFalse(flagged(through("transform.acme_exec_mode")));
            assertFalse(flagged(through("transform.filter")));
            assertDoesNotThrow(() -> PipelineValidator.validateOrThrow(through("transform.acme_exec_mode")));
        } finally {
            PipelineNodeTypes.deregister(OWNER);
        }
    }
}
