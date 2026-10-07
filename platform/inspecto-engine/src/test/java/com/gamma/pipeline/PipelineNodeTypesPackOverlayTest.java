package com.gamma.pipeline;

import com.gamma.pipeline.exec.StepContext;
import com.gamma.pipeline.exec.StepExecutor;
import com.gamma.pipeline.exec.StepExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pack overlay on the node-type and Step registries (pipeline spec gap 7, S2-3): a hot-deployed pack
 * may contribute a node type and/or the Step that runs it, keyed by the owning jar, and an unload takes back exactly that pack's
 * contributions.
 *
 * <p>⚠ These tests mutate process-wide static registries, so every one of them deregisters in
 * {@link #cleanup()} — a leaked overlay would make an unrelated suite see a node type that does not exist
 * in a stock build, which is precisely the drift the contract tests exist to catch.
 */
class PipelineNodeTypesPackOverlayTest {

    private static final String OWNER = "acme.test.jar";
    private static final String OTHER = "other.test.jar";

    /** A minimal contributed type — only {@code type()} is required; the rest defaults. */
    private record Contributed(String type) implements PipelineNodeType {}

    private record ContributedStep(String type) implements StepExecutor {
        @Override public void execute(StepContext ctx) {}
    }

    @AfterEach
    void cleanup() {
        PipelineNodeTypes.deregister(OWNER);
        PipelineNodeTypes.deregister(OTHER);
        StepExecutors.deregister(OWNER);
        StepExecutors.deregister(OTHER);
    }

    @Test
    void aPackTypeBecomesKnownAndAnUnloadTakesItBack() {
        assertFalse(PipelineNodeTypes.isKnown("transform.acme"), "the stock build must not know it");

        PipelineNodeTypes.register(new Contributed("transform.acme"), OWNER);
        assertTrue(PipelineNodeTypes.isKnown("transform.acme"));
        assertTrue(PipelineNodeTypes.get("transform.acme").isPresent());
        assertTrue(PipelineNodeTypes.all().contains("transform.acme"));
        assertEquals(OWNER, PipelineNodeTypes.ownerOf("transform.acme").orElseThrow());

        PipelineNodeTypes.deregister(OWNER);
        assertFalse(PipelineNodeTypes.isKnown("transform.acme"), "an unload must take the type back");
        assertTrue(PipelineNodeTypes.ownerOf("transform.acme").isEmpty());
    }

    @Test
    void theBuiltInsAndTheirOwnerlessnessSurviveAnOverlay() {
        PipelineNodeTypes.register(new Contributed("transform.acme"), OWNER);
        // Every built-in is still there, and none of them acquires a pack owner.
        for (BuiltinNodeType b : BuiltinNodeType.values()) {
            assertTrue(PipelineNodeTypes.isKnown(b.type()), b.type());
            assertTrue(PipelineNodeTypes.ownerOf(b.type()).isEmpty(), b.type() + " must stay ownerless");
        }
    }

    @Test
    void aPackMayNotRedefineABuiltIn() {
        String builtin = BuiltinNodeType.values()[0].type();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PipelineNodeTypes.register(new Contributed(builtin), OWNER));
        assertTrue(ex.getMessage().contains("built-in"), ex.getMessage());
        // …and the refusal left nothing behind, so the pack rejection is clean.
        assertTrue(PipelineNodeTypes.ownerOf(builtin).isEmpty());
    }

    @Test
    void twoPacksCannotOwnTheSameType() {
        PipelineNodeTypes.register(new Contributed("transform.acme"), OWNER);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PipelineNodeTypes.register(new Contributed("transform.acme"), OTHER));
        assertTrue(ex.getMessage().contains(OWNER), ex.getMessage());
        assertEquals(OWNER, PipelineNodeTypes.ownerOf("transform.acme").orElseThrow(), "first pack keeps it");
    }

    @Test
    void reRegisteringTheSameOwnerIsAReload() {
        PipelineNodeTypes.register(new Contributed("transform.acme"), OWNER);
        PipelineNodeTypes.register(new Contributed("transform.acme"), OWNER);   // must not throw
        assertTrue(PipelineNodeTypes.isKnown("transform.acme"));
    }

    @Test
    void deregisteringAnUnknownOwnerIsANoOp() {
        int before = PipelineNodeTypes.all().size();
        PipelineNodeTypes.deregister("never-loaded.jar");
        PipelineNodeTypes.deregister(null);
        assertEquals(before, PipelineNodeTypes.all().size());
    }

    @Test
    void aPackStepRegistersAndUnloadsToo() {
        assertTrue(StepExecutors.get("transform.acme").isEmpty());
        PipelineNodeTypes.register(new Contributed("transform.acme"), OWNER);
        StepExecutors.register(new ContributedStep("transform.acme"), OWNER, null);
        assertTrue(StepExecutors.get("transform.acme").isPresent());
        StepExecutors.deregister(OWNER);
        assertTrue(StepExecutors.get("transform.acme").isEmpty());
    }

    /**
     * S2-0: a pack may NOT specialise a built-in verb. {@code RowShaper} consults the Step registry before its
     * built-in chain, so a pack Step for {@code transform.dedup} would change how every pipeline dedups. Both
     * registries refuse a built-in; a classpath provider (an edition) still may.
     */
    @Test
    void aPackStepMayNotSpecialiseABuiltInVerb() {
        String type = "transform.dedup";
        boolean hadOne = StepExecutors.get(type).isPresent();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> StepExecutors.register(new ContributedStep(type), OWNER, null));
        assertTrue(ex.getMessage().contains("built-in"), ex.getMessage());
        assertEquals(hadOne, StepExecutors.get(type).isPresent(), "the refusal left nothing behind");
    }

    /** S2-0: a Step must run a node type the SAME pack declared — not another pack's, not nobody's. */
    @Test
    void aPackStepMustRunItsOwnPacksNodeType() {
        assertThrows(IllegalStateException.class,
                () -> StepExecutors.register(new ContributedStep("transform.acme"), OWNER, null),
                "no node type declared at all");
        PipelineNodeTypes.register(new Contributed("transform.acme"), OTHER);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> StepExecutors.register(new ContributedStep("transform.acme"), OWNER, null),
                "declared by a different pack");
        assertTrue(ex.getMessage().contains(OWNER), ex.getMessage());
        assertTrue(StepExecutors.get("transform.acme").isEmpty());
    }

    /** The contracts are generated in a JVM with no packs, so an overlay must never be in force there. */
    @Test
    void theStockCatalogIsUnchangedWithNoPackLoaded() {
        int builtins = BuiltinNodeType.values().length;
        assertTrue(PipelineNodeTypes.catalog().size() >= builtins);
        assertTrue(PipelineNodeTypes.all().stream().noneMatch(t -> t.startsWith("transform.acme")));
    }

    // ── S2-1: execution mode ─────────────────────────────────────────────────────────────────

    private record Moded(String type, ExecutionMode m) implements PipelineNodeType {
        @Override public java.util.Optional<ExecutionMode> mode() { return java.util.Optional.of(m); }
    }

    @Test
    void everyBuiltInIsLowered() {
        for (BuiltinNodeType b : BuiltinNodeType.values())
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(ExecutionMode.LOWERED), b.mode(), b.type());
    }

    @Test
    void aPackTypeDeclaringLoweredIsRefusedAndExecutedIsAccepted() {
        try {
            IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> PipelineNodeTypes.register(new Moded("transform.acme_low", ExecutionMode.LOWERED), OWNER));
            org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("LOWERED"), e.getMessage());
            org.junit.jupiter.api.Assertions.assertFalse(PipelineNodeTypes.isKnown("transform.acme_low"));

            PipelineNodeTypes.register(new Moded("transform.acme_exec", ExecutionMode.EXECUTED), OWNER);
            org.junit.jupiter.api.Assertions.assertTrue(PipelineNodeTypes.isKnown("transform.acme_exec"));
            // undeclared still LOADS — it is refused at arming (PipelineValidatorModeTest), not here
            PipelineNodeTypes.register(new Contributed("transform.acme_undeclared"), OWNER);
            org.junit.jupiter.api.Assertions.assertTrue(PipelineNodeTypes.isKnown("transform.acme_undeclared"));
        } finally {
            PipelineNodeTypes.deregister(OWNER);
        }
    }
}
