package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q5 (operator 2026-09-23): the DuckDB parse tree is a surface we CONSUME but do not own, so the
 * contract pins ONLY the node types and keys the SPA actually reads ({@code inspecto/query/sql-ast.ts}),
 * never the whole shape. This side proves the real engine, on the route's own sealed connection
 * ({@link SqlAst}), still emits every one of them; {@code sql-ast.spec.ts} proves the reader handles
 * exactly the listed types. A DuckDB bump that renames a node type or moves a key fails HERE, loudly,
 * instead of silently degrading every stored predicate to "cannot be shown".
 *
 * <p>⚠ Deliberately NOT pinned: {@code query_location}. The SPA never reads it — a start offset with no
 * end cannot drive anything (design T4), and a CAST node carries {@code 2^64-1} there, which JavaScript
 * cannot even represent exactly. Pinning a key nobody reads would make an irrelevant engine change a
 * build failure.
 */
class SqlAstContractTest {

    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/contracts/sql-ast.contract.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode contract() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CONTRACT);
            if (Files.exists(candidate)) return JSON.readTree(candidate.toFile());
        }
        throw new AssertionError("cannot locate " + CONTRACT + " from " + Path.of("").toAbsolutePath());
    }

    @Test
    void everyContractedNodeTypeIsEmittedWithEveryKeyTheSpaReads() throws Exception {
        JsonNode nodes = contract().get("nodes");
        assertTrue(nodes.size() > 0, "the contract lists no nodes");
        for (JsonNode entry : nodes) {
            String example = entry.get("example").asText();
            String type = entry.get("type").asText();
            JsonNode ast = parsed(example);
            List<JsonNode> found = new ArrayList<>();
            collect(ast, type, found);
            assertFalse(found.isEmpty(), "DuckDB no longer emits a '" + type + "' node for `" + example + "`: " + ast);
            JsonNode hit = found.stream().filter(n -> readsAll(n, entry.get("reads"))).findFirst().orElse(null);
            assertNotNull(hit, "no '" + type + "' node for `" + example + "` carries every key the SPA reads "
                    + entry.get("reads") + ": " + found);
            assertEquals(entry.get("class").asText(), hit.path("class").asText(),
                    "'" + type + "' changed class for `" + example + "`");
        }
    }

    @Test
    void everyContractedLikeFunctionNameIsEmitted() throws Exception {
        for (JsonNode entry : contract().get("likeFunctions")) {
            String name = entry.get("name").asText();
            String example = entry.get("example").asText();
            List<JsonNode> found = new ArrayList<>();
            collect(parsed(example), "FUNCTION", found);
            assertTrue(found.stream().anyMatch(n -> name.equals(n.path("function_name").asText())),
                    "`" + example + "` no longer parses to function '" + name + "': " + found);
        }
    }

    private static JsonNode parsed(String predicate) throws Exception {
        SqlAst.Result r = SqlAst.parse(predicate, SqlAst.Fragment.PREDICATE);
        assertTrue(r.ok(), "`" + predicate + "` no longer parses: " + r.message());
        return r.ast();
    }

    private static void collect(JsonNode n, String type, List<JsonNode> out) {
        if (n.isObject() && type.equals(n.path("type").asText()) && n.has("class")) out.add(n);
        for (JsonNode child : n) collect(child, type, out);
    }

    private static boolean readsAll(JsonNode node, JsonNode reads) {
        for (JsonNode path : reads) {
            JsonNode at = node;
            for (String step : path.asText().split("\\.")) at = at.path(step);
            if (at.isMissingNode()) return false;
        }
        return true;
    }
}
