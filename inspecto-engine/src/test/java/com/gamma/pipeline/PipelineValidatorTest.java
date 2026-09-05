package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.gamma.etl.TestConfigs.csv;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PipelineValidator} (T14): the structural gate over the Flow IR — DAG over {@code data} edges,
 * no dangling endpoints, no duplicate ids, no same-graph {@code on_commit}, at least one entry node.
 */
class PipelineValidatorTest {

    private static Set<String> codes(PipelineValidator.Result r) {
        return r.issues().stream().map(PipelineValidator.Issue::code).collect(Collectors.toSet());
    }

    /** acquisition -> parser -> sink, with the parser's unmatched branch to a quarantine sink: a valid flow. */
    private static PipelineGraph linearValid() {
        return new PipelineGraph("good", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("p", "parser"),
                        PipelineNode.of("sink", "sink.persistent"),
                        PipelineNode.of("dead", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "p"),
                        PipelineEdge.data("p", "sink"),
                        new PipelineEdge("p", PipelineRel.UNMATCHED, "dead")));   // parser emits unmatched (§contract)
    }

    @Test
    void validLinearGraphHasNoErrors() {
        PipelineValidator.Result r = PipelineValidator.validate(linearValid());
        assertTrue(r.ok(), () -> "expected ok, got " + r.issues());
        assertTrue(r.errors().isEmpty());
        assertDoesNotThrow(() -> PipelineValidator.validateOrThrow(linearValid()));
    }

    @Test
    void detectsDataCycle() {
        PipelineGraph g = new PipelineGraph("loop", true,
                List.of(PipelineNode.of("a", "transform.sql"), PipelineNode.of("b", "transform.sql")),
                List.of(PipelineEdge.data("a", "b"), PipelineEdge.data("b", "a")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertFalse(r.ok());
        assertTrue(codes(r).contains(PipelineValidator.CYCLE));
        // the message names the offending nodes
        assertTrue(r.errors().stream().anyMatch(i -> i.message().contains("a") && i.message().contains("b")));
    }

    @Test
    void controlEdgesAreExcludedFromTheCycleCheck() {
        // a failure-> b, b failure-> a : a control cycle, NOT a data cycle — must not be flagged CYCLE
        // (acquisition emits failure, per the node-output contract)
        PipelineGraph g = new PipelineGraph("ctrl", true,
                List.of(PipelineNode.of("a", "acquisition"), PipelineNode.of("b", "acquisition")),
                List.of(new PipelineEdge("a", PipelineRel.FAILURE, "b"), new PipelineEdge("b", PipelineRel.FAILURE, "a")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertFalse(codes(r).contains(PipelineValidator.CYCLE));
        // ...but with no inbound-free node, it has no trigger
        assertTrue(codes(r).contains(PipelineValidator.NO_ENTRY));
    }

    @Test
    void rejectsSameGraphOnCommitButAllowsCrossFlowTarget() {
        // on_commit to a LOCAL node -> rejected (cross-flow only)
        PipelineGraph local = new PipelineGraph("f", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "sink"), new PipelineEdge("sink", PipelineRel.ON_COMMIT, "acq")));
        assertTrue(codes(PipelineValidator.validate(local)).contains(PipelineValidator.ON_COMMIT_SAME_GRAPH));

        // on_commit to ANOTHER flow (not a local node) -> fine, and not a dangling-to error
        PipelineGraph cross = new PipelineGraph("f", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "sink"), new PipelineEdge("sink", PipelineRel.ON_COMMIT, "downstream_flow")));
        PipelineValidator.Result r = PipelineValidator.validate(cross);
        assertTrue(r.ok(), () -> "cross-flow on_commit should be valid, got " + r.issues());
    }

    @Test
    void rejectsDanglingEndpoints() {
        PipelineGraph g = new PipelineGraph("dangle", true,
                List.of(PipelineNode.of("a", "acquisition")),
                List.of(PipelineEdge.data("a", "ghost"), PipelineEdge.data("nobody", "a")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(codes(r).containsAll(Set.of(PipelineValidator.DANGLING_TO, PipelineValidator.DANGLING_FROM)));
        assertFalse(r.ok());
    }

    @Test
    void flagsDuplicateNodeId() {
        PipelineGraph g = new PipelineGraph("dup", true,
                List.of(PipelineNode.of("a", "acquisition"), PipelineNode.of("a", "parser"), PipelineNode.of("b", "sink.persistent")),
                List.of(PipelineEdge.data("a", "b")));
        assertTrue(codes(PipelineValidator.validate(g)).contains(PipelineValidator.DUPLICATE_NODE));
    }

    @Test
    void emptyGraphIsAWarningNotAnError() {
        PipelineValidator.Result r = PipelineValidator.validate(new PipelineGraph("empty", false, List.of(), List.of()));
        assertTrue(r.ok());                                          // a warning does not block
        assertTrue(codes(r).contains(PipelineValidator.EMPTY_GRAPH));
        assertEquals(1, r.warnings().size());
    }

    @Test
    void validateOrThrowReportsEveryError() {
        PipelineGraph g = new PipelineGraph("bad", true,
                List.of(PipelineNode.of("a", "transform.sql"), PipelineNode.of("b", "transform.sql")),
                List.of(PipelineEdge.data("a", "b"), PipelineEdge.data("b", "a")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineValidator.validateOrThrow(g));
        assertTrue(ex.getMessage().contains("CYCLE"));
    }

    // ── T9: node-output contract (emit/accept wiring) ─────────────────────────────

    @Test
    void rejectsRelationANodeDoesNotEmit() {
        // transform.map emits only data — an 'invalid' branch is illegal (only validate emits invalid)
        PipelineGraph g = new PipelineGraph("emit", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("m", "transform.sql"),
                        PipelineNode.of("x", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "m"), new PipelineEdge("m", PipelineRel.INVALID, "x")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(codes(r).contains(PipelineValidator.ILLEGAL_EMIT));
        assertFalse(r.ok());
    }

    @Test
    void routeBranchesAreLegalOnlyFromARouter() {
        // a router emits route:* — fine
        PipelineGraph ok = new PipelineGraph("router", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("r", "transform.route"),
                        PipelineNode.of("a", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "r"), new PipelineEdge("r", PipelineRel.route("emea"), "a")));
        assertTrue(PipelineValidator.validate(ok).ok(), () -> "" + PipelineValidator.validate(ok).issues());

        // a plain map does NOT emit named routes
        PipelineGraph bad = new PipelineGraph("router", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("m", "transform.sql"),
                        PipelineNode.of("a", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "m"), new PipelineEdge("m", PipelineRel.route("emea"), "a")));
        assertTrue(codes(PipelineValidator.validate(bad)).contains(PipelineValidator.ILLEGAL_EMIT));
    }

    @Test
    void rejectsDataEdgeIntoNodeThatAcceptsNoData() {
        // 'gap' accepts only gap, not data — a data edge into it is illegal
        PipelineGraph g = new PipelineGraph("accept", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("g", "gap")),
                List.of(PipelineEdge.data("acq", "g")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(codes(r).contains(PipelineValidator.ILLEGAL_ACCEPT));
        // ...but the parser's unmatched control edge into a sink is fine (handler governed by emitter)
        assertTrue(PipelineValidator.validate(linearValid()).ok());
    }

    // ── neighbour pairing on outcome/route edges (A6, pipeline-multiplicity plan) ──────
    // The emit side is checked for every edge, but the accept side used to be checked only for
    // data edges — so an outcome routed into a node that can neither accept the relationship nor
    // consume it as rows passed silently. The handler exemption stays: a sink/alert taking a
    // reject stream as rows (accepts data) needs no per-outcome listing.

    @Test
    void anOutcomeEdgeIntoTheEntryNodeRefusesWithIllegalPairing() {
        // acquisition emits failure, so the emit side is legal — but the second acquisition node
        // accepts NOTHING inbound; wiring a failure stream into a pipeline entry is nonsense.
        PipelineGraph g = new PipelineGraph("pairing", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("acq2", "acquisition"),
                        PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "sink"),
                        new PipelineEdge("acq", PipelineRel.FAILURE, "acq2")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertFalse(r.ok());
        assertTrue(codes(r).contains(PipelineValidator.ILLEGAL_PAIRING));
        assertTrue(r.errors().stream().anyMatch(i -> i.message().contains("acq2")
                        && i.message().contains(PipelineRel.FAILURE)),
                () -> "" + r.issues());
    }

    @Test
    void aRouteBranchIntoANodeThatConsumesNeitherTheRouteNorRowsRefuses() {
        // route:* branches carry rows; 'gap' accepts only the gap relationship, never rows.
        PipelineGraph g = new PipelineGraph("pairing-route", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("r", "transform.route"),
                        PipelineNode.of("g", "gap")),
                List.of(PipelineEdge.data("acq", "r"),
                        new PipelineEdge("r", PipelineRel.route("emea"), "g")));
        assertTrue(codes(PipelineValidator.validate(g)).contains(PipelineValidator.ILLEGAL_PAIRING));
    }

    @Test
    void theHandlerExemptionSurvives_outcomesIntoRowConsumersAndDeclaredAcceptorsPass() {
        // failure → alert (declared acceptor), gap → gap node (declared), failure → sink (row
        // consumer), unmatched → sink (row consumer, already in linearValid): all legal pairings.
        PipelineGraph g = new PipelineGraph("handlers", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("al", "alert"),
                        PipelineNode.of("gd", "gap"), PipelineNode.of("q", "sink.persistent"),
                        PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "sink"),
                        new PipelineEdge("acq", PipelineRel.FAILURE, "al"),
                        new PipelineEdge("acq", PipelineRel.GAP, "gd"),
                        new PipelineEdge("acq", PipelineRel.FAILURE, "q")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(r.ok(), () -> "" + r.issues());
        assertTrue(PipelineValidator.validate(linearValid()).ok());
    }

    /** The A6 verify criterion's other half: a wiring-valid chain with N transforms of one kind saves. */
    @Test
    void aWiringValidChainWithRepeatedTransformKindsHasNoErrors() {
        PipelineGraph g = new PipelineGraph("n-transforms", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("p", "parser"),
                        PipelineNode.of("f1", "transform.filter"), PipelineNode.of("d1", "transform.dedup"),
                        PipelineNode.of("f2", "transform.filter"), PipelineNode.of("d2", "transform.dedup"),
                        PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "p"), PipelineEdge.data("p", "f1"),
                        PipelineEdge.data("f1", "d1"), PipelineEdge.data("d1", "f2"),
                        PipelineEdge.data("f2", "d2"), PipelineEdge.data("d2", "sink")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(r.ok(), () -> "" + r.issues());
    }

    @Test
    void unregisteredTypeIsAWarningAndItsWiringIsNotChecked() {
        PipelineGraph g = new PipelineGraph("plugin", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("p", "transform.bespoke-plugin")),
                List.of(new PipelineEdge("acq", PipelineRel.DATA, "p")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(codes(r).contains(PipelineValidator.UNKNOWN_TYPE));
        assertTrue(r.ok());   // a warning does not block; wiring around the unknown type is skipped
    }

    @Test
    void unknownUseKindIsAnError() {
        PipelineGraph g = new PipelineGraph("bad-use", true,
                List.of(new PipelineNode("p", "parser.dsv", Map.of(), "bogus/thing")),
                List.of());
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(codes(r).contains(PipelineValidator.UNKNOWN_USE_KIND));
        assertTrue(r.errors().stream().anyMatch(i -> i.code().equals(PipelineValidator.UNKNOWN_USE_KIND)));
    }

    @Test
    void knownUseKindDoesNotFlag() {
        PipelineGraph g = new PipelineGraph("good-use", true,
                List.of(new PipelineNode("p", "parser.dsv", Map.of(), "grammar/pipe-delimited")),
                List.of());
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertFalse(codes(r).contains(PipelineValidator.UNKNOWN_USE_KIND));
    }

    // ── use: TARGET existence (the refusal that was unbuilt until now) ─────────────────
    // The kind-prefix check above only proves 'grammar' is a component type. These prove the NAMED
    // component is checked too, but only when a registry is supplied — a registry-less validate
    // cannot tell a typo from a component it merely cannot see.

    /** A well-formed kind whose named component is absent is an error when a registry is supplied. */
    @Test
    void danglingUseRefIsAnErrorWhenARegistryIsSupplied(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("grammars"));
        Files.writeString(root.resolve("grammars/pipe.toon"), "name: pipe-delimited\ndelimiter: \"|\"\n");
        ComponentRegistry reg = ComponentRegistry.scan(root);

        PipelineGraph g = new PipelineGraph("typo", true,
                List.of(new PipelineNode("p", "parser.dsv", Map.of(), "grammar/pipe-delimted")),
                List.of());

        PipelineValidator.Result r = PipelineValidator.validate(g, reg);
        assertTrue(codes(r).contains(PipelineValidator.UNKNOWN_USE_REF),
                () -> "a typo'd grammar name must be refused, got " + r.issues());
        assertFalse(r.ok(), "the dangling ref blocks the save");
        assertTrue(r.errors().stream().anyMatch(i -> i.message().contains("pipe-delimted")),
                "the message names the component the author actually typed");
    }

    /** …and the correctly-spelled one against the same registry is clean. */
    @Test
    void resolvableUseRefIsClean(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("grammars"));
        Files.writeString(root.resolve("grammars/pipe.toon"), "name: pipe-delimited\ndelimiter: \"|\"\n");
        ComponentRegistry reg = ComponentRegistry.scan(root);

        PipelineGraph g = new PipelineGraph("good", true,
                List.of(new PipelineNode("p", "parser.dsv", Map.of(), "grammar/pipe-delimited")),
                List.of());

        assertFalse(codes(PipelineValidator.validate(g, reg)).contains(PipelineValidator.UNKNOWN_USE_REF));
    }

    /** Without a registry the check is skipped entirely — the pre-existing contract, unchanged. */
    @Test
    void registrylessValidateStaysSilentAboutUseTargets() {
        PipelineGraph g = new PipelineGraph("typo", true,
                List.of(new PipelineNode("p", "parser.dsv", Map.of(), "grammar/nothing-registered")),
                List.of());

        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertFalse(codes(r).contains(PipelineValidator.UNKNOWN_USE_REF),
                "a registry-less validate must not invent a dangling-ref error");
    }

    /** A bad KIND reports once, not twice — the name check is meaningless for a non-component kind. */
    @Test
    void badKindIsReportedWithoutAlsoReportingADanglingRef(@TempDir Path root) {
        ComponentRegistry reg = ComponentRegistry.scan(root);
        PipelineGraph g = new PipelineGraph("bad-use", true,
                List.of(new PipelineNode("p", "parser.dsv", Map.of(), "bogus/thing")),
                List.of());

        PipelineValidator.Result r = PipelineValidator.validate(g, reg);
        assertTrue(codes(r).contains(PipelineValidator.UNKNOWN_USE_KIND));
        assertFalse(codes(r).contains(PipelineValidator.UNKNOWN_USE_REF),
                "one typo must not read as two separate faults");
    }

    // ── B5: transform.sql is legal-but-flagged, same spirit as the EXPR mapping-rule warning ──────

    @Test
    void transformSqlNodeGetsExactlyOneUnauditedWarningAnchoredToTheNode() {
        PipelineGraph g = new PipelineGraph("sql-step", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("s", "transform.sql"),
                        PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "s"), PipelineEdge.data("s", "sink")));
        PipelineValidator.Result r = PipelineValidator.validate(g);

        List<PipelineValidator.Issue> sqlWarnings = r.issues().stream()
                .filter(i -> i.code().equals(PipelineValidator.SQL_STEP_UNAUDITED)).toList();
        assertEquals(1, sqlWarnings.size(), () -> "" + r.issues());
        PipelineValidator.Issue warning = sqlWarnings.get(0);
        assertEquals(PipelineValidator.Severity.WARNING, warning.severity());
        assertTrue(warning.message().contains("'s'"), warning.message());
        assertTrue(warning.message().contains("'sql'"), warning.message());
        assertTrue(warning.message().contains("cast-failure audit"), warning.message());
    }

    @Test
    void theUnauditedSqlWarningAloneDoesNotBlockSave() {
        PipelineGraph g = new PipelineGraph("sql-step", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("s", "transform.sql"),
                        PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "s"), PipelineEdge.data("s", "sink")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(r.ok(), () -> "a WARNING-only result must still validate ok, got " + r.issues());
        assertTrue(r.errors().isEmpty());
        assertEquals(1, r.warnings().size());
    }

    @Test
    void anActualErrorOnAGraphWithASqlStepStillBlocksSave() {
        // the transform.sql node's own WARNING must not mask an unrelated ERROR elsewhere in the graph
        PipelineGraph g = new PipelineGraph("sql-step-plus-error", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("s", "transform.sql")),
                List.of(PipelineEdge.data("acq", "s"), PipelineEdge.data("s", "ghost")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertFalse(r.ok(), () -> "a dangling edge must still refuse, got " + r.issues());
        assertTrue(codes(r).contains(PipelineValidator.DANGLING_TO));
        assertTrue(codes(r).contains(PipelineValidator.SQL_STEP_UNAUDITED));
    }

    /** The warning fires for a LIFTED sql step too — the stored {@code kind: sql} spelling, not only a hand-built graph. */
    @Test
    void aLiftedSqlStepStillGetsTheUnauditedWarning(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("s.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        Path toon = dir.resolve("sq_pipeline.toon");
        Files.writeString(toon, """
                name: sq
                active: false
                dirs:
                  poll: %s
                  database: %s
                processing:
                  schema_file: %s
                steps[1]:
                  - sql:
                      sql: SELECT * FROM input
                """.formatted(slash(dir.resolve("in")), slash(dir.resolve("db")), slash(schema)));
        PipelineValidator.Result r = PipelineValidator.validate(PipelineLift.lift(PipelineConfig.load(toon.toString())));
        assertTrue(r.ok(), () -> "a sql step is legal: " + r.errors());
        assertEquals(1, r.issues().stream().filter(i -> i.code().equals(PipelineValidator.SQL_STEP_UNAUDITED)).count(),
                () -> "" + r.issues());
    }

    private static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }

    @Test
    void aRealLiftedPipelineValidatesClean(@TempDir Path dir) throws Exception {
        // the ultimate gate: every edge the legacy lift produces honours the node-output contract
        PipelineConfig cfg = csv(dir, PipelineConfigBatchTest.miniSchema()).load();
        PipelineValidator.Result r = PipelineValidator.validate(PipelineLift.lift(cfg));
        assertTrue(r.ok(), () -> "lifted pipeline should validate clean, got " + r.errors());
    }
}
