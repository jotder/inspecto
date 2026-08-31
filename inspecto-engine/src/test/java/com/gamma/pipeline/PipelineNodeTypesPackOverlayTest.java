package com.gamma.pipeline;

import com.gamma.pipeline.exec.PipelineNodeExecutor;
import com.gamma.pipeline.exec.PipelineNodeExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pack overlay on both node registries (pipeline spec gap 7): a hot-deployed pack may contribute a
 * node type and/or its executor, keyed by the owning jar, and an unload takes back exactly that pack's
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

    private record ContributedExec(String type) implements PipelineNodeExecutor {
        @Override
        public java.util.List<com.gamma.pipeline.exec.RowShaper.Relation> shape(
                java.sql.Connection conn, PipelineNode node, String input, String outPrefix,
                com.gamma.pipeline.exec.RowShaper.ReferenceResolver references) {
            return java.util.List.of();
        }
    }

    @AfterEach
    void cleanup() {
        PipelineNodeTypes.deregister(OWNER);
        PipelineNodeTypes.deregister(OTHER);
        PipelineNodeExecutors.deregister(OWNER);
        PipelineNodeExecutors.deregister(OTHER);
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
    void aPackExecutorRegistersAndUnloadsToo() {
        assertTrue(PipelineNodeExecutors.get("transform.acme").isEmpty());
        PipelineNodeExecutors.register(new ContributedExec("transform.acme"), OWNER);
        assertTrue(PipelineNodeExecutors.get("transform.acme").isPresent());
        PipelineNodeExecutors.deregister(OWNER);
        assertTrue(PipelineNodeExecutors.get("transform.acme").isEmpty());
    }

    /**
     * The executor registry deliberately DOES let a pack specialise a built-in verb — that is what
     * {@code RowShaper} consults it for — while the descriptor registry refuses the same thing. The two
     * rules differ on purpose, so both are pinned here; if they are ever unified, one of these fails.
     */
    @Test
    void anExecutorMaySpecialiseABuiltInVerbAndTheUnloadRestoresIt() {
        String type = "transform.dedup";
        boolean hadOne = PipelineNodeExecutors.get(type).isPresent();
        PipelineNodeExecutors.register(new ContributedExec(type), OWNER);
        assertTrue(PipelineNodeExecutors.get(type).isPresent());
        PipelineNodeExecutors.deregister(OWNER);
        assertEquals(hadOne, PipelineNodeExecutors.get(type).isPresent(), "unload restores the prior state");
    }

    /** The contracts are generated in a JVM with no packs, so an overlay must never be in force there. */
    @Test
    void theStockCatalogIsUnchangedWithNoPackLoaded() {
        int builtins = BuiltinNodeType.values().length;
        assertTrue(PipelineNodeTypes.catalog().size() >= builtins);
        assertTrue(PipelineNodeTypes.all().stream().noneMatch(t -> t.startsWith("transform.acme")));
    }
}
