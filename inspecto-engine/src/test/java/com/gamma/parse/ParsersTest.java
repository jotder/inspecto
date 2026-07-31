package com.gamma.parse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link Parsers} registry + the built-in adapters: the catalog carries the four engine
 * frontends plus the ServiceLoader-discovered XML and ASN.1 plugins, every built-in's preview runs
 * the real DuckDB read specs, and self-description (grammar schemas) is present and sane.
 */
class ParsersTest {

    @Test
    void catalogCarriesBuiltinsThenDiscoveredPlugins() {
        List<String> ids = Parsers.catalog().stream().map(ParserPlugin::id).toList();
        assertEquals(List.of("delimited", "fixedwidth", "json", "text_regex", "xml", "asn1"), ids);
    }

    @Test
    void builtinsAreIngestableThePreviewOnlyPluginsAreNot() {
        assertTrue(Parsers.ingestable(Parsers.get("delimited").orElseThrow()));
        assertTrue(Parsers.ingestable(Parsers.get("json").orElseThrow()));
        for (String id : List.of("xml", "asn1")) {
            ParserPlugin p = Parsers.get(id).orElseThrow();
            assertTrue(p.hierarchical());
            assertFalse(Parsers.ingestable(p), "tree data cannot load to Tables before the flatten config");
        }
    }

    @Test
    void unknownIdIsEmpty() {
        assertTrue(Parsers.get("made_up_format").isEmpty());
    }

    @Test
    void everyParserDeclaresANonEmptyGrammarSchemaWithPaths() {
        for (ParserPlugin p : Parsers.catalog()) {
            assertFalse(p.grammarSchema().isEmpty(), p.id() + " has no grammar schema");
            for (var f : p.grammarSchema()) {
                assertFalse(f.path().isBlank(), p.id() + " has a blank field path");
                assertFalse(f.label().isBlank(), p.id() + "." + f.path() + " has a blank label");
            }
        }
    }

    @Test
    void delimitedPreviewRunsTheEngineReadAndReturnsATable() throws Exception {
        ParserPlugin p = Parsers.get("delimited").orElseThrow();
        ParseResult r = p.preview("id,qty\n1001,3\n1002,5\n".getBytes(StandardCharsets.UTF_8),
                Map.of("delimited", Map.of("has_header", true)));
        ParseResult.Table t = assertInstanceOf(ParseResult.Table.class, r);
        assertEquals(List.of("id", "qty"), t.columns());
        assertEquals(2, t.rowCount());
        assertEquals("1001", String.valueOf(t.rows().get(0).get("id")));
    }

    @Test
    void jsonPreviewDiscoversTopLevelKeysAsColumns() throws Exception {
        ParserPlugin p = Parsers.get("json").orElseThrow();
        ParseResult r = p.preview("{\"a\": 1, \"b\": {\"c\": 2}}\n".getBytes(StandardCharsets.UTF_8),
                Map.of("json", Map.of("format", "newline")));
        ParseResult.Table t = assertInstanceOf(ParseResult.Table.class, r);
        assertEquals(List.of("a", "b"), t.columns());
        assertEquals(1, t.rowCount());
    }

    @Test
    void textRegexPreviewProjectsNamedGroups() throws Exception {
        ParserPlugin p = Parsers.get("text_regex").orElseThrow();
        ParseResult r = p.preview("INFO started\nWARN slow\n".getBytes(StandardCharsets.UTF_8),
                Map.of("text_regex", Map.of("pattern", "(?P<level>[A-Z]+) (?P<msg>.+)")));
        ParseResult.Table t = assertInstanceOf(ParseResult.Table.class, r);
        assertEquals(List.of("level", "msg"), t.columns());
        assertEquals(2, t.rowCount());
    }

    @Test
    void anUnknownEncodingIsACallerError() {
        ParserPlugin p = Parsers.get("delimited").orElseThrow();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> p.preview("a,b\n".getBytes(StandardCharsets.UTF_8), Map.of("encoding", "NOPE-8")));
        assertTrue(e.getMessage().contains("unknown encoding"));
    }
}
