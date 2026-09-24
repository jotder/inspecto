package com.gamma.parse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        assertEquals(List.of("delimited", "fixedwidth", "json", "parquet", "xlsx", "text_regex", "xml", "asn1"), ids);
    }

    @Test
    void ingestabilityTracksWhetherAParserCanActuallyLoadToTables() {
        assertTrue(Parsers.ingestable(Parsers.get("delimited").orElseThrow()));
        assertTrue(Parsers.ingestable(Parsers.get("json").orElseThrow()));
        // Both shipped plugins are hierarchical, and since the tree→segments bridge landed both also
        // ingest — each naming its OWN ingester, which is what the flag actually tracks.
        ParserPlugin xml = Parsers.get("xml").orElseThrow();
        assertTrue(xml.hierarchical());
        assertTrue(Parsers.ingestable(xml), "XmlRecordIngester flattens the record tree onto segments");
        assertEquals("com.gamma.ingester.XmlRecordIngester", xml.ingesterClass().orElseThrow());
        ParserPlugin asn1 = Parsers.get("asn1").orElseThrow();
        assertTrue(asn1.hierarchical());
        assertTrue(Parsers.ingestable(asn1), "Asn1RecordIngester flattens onto segment schemas");
        assertEquals("com.gamma.ingester.Asn1RecordIngester", asn1.ingesterClass().orElseThrow());
    }

    /**
     * No shipped plugin is preview-only any more, so the negative arm is pinned with a stub — the
     * mechanism (an absent {@code ingesterClass} means not ingestable) must stay tested even once
     * every deployed parser happens to satisfy it, or a regression would go unseen.
     */
    @Test
    void aPluginThatNamesNoIngesterIsNotIngestable() {
        ParserPlugin previewOnly = new ParserPlugin() {
            @Override public String id() { return "preview_only"; }
            @Override public String label() { return "Preview only"; }
            @Override public boolean hierarchical() { return true; }
            @Override public List<com.gamma.config.spec.FieldSpec> grammarSchema() { return List.of(); }
            @Override public ParseResult preview(byte[] sample, Map<String, Object> grammar) {
                return new ParseResult.Tree(0, List.of());
            }
        };
        assertTrue(previewOnly.ingesterClass().isEmpty(), "the SPI default");
        assertFalse(Parsers.ingestable(previewOnly), "tree data cannot load to Tables with no ingester");
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

    // ── the pack overlay (parser-plugins-trust-design.md slice P2, operator D1 2026-09-25) ──────────

    /** A minimal parser a Job Pack might contribute, optionally naming an ingester. */
    private static ParserPlugin stub(String id, String ingester) {
        return new ParserPlugin() {
            @Override public String id() { return id; }
            @Override public String label() { return "Stub " + id; }
            @Override public boolean hierarchical() { return false; }
            @Override public List<com.gamma.config.spec.FieldSpec> grammarSchema() { return List.of(); }
            @Override public ParseResult preview(byte[] sample, Map<String, Object> grammar) {
                return new ParseResult.Tree(0, List.of());
            }
            @Override public java.util.Optional<String> ingesterClass() { return java.util.Optional.ofNullable(ingester); }
        };
    }

    private static List<String> ids() {
        return Parsers.catalog().stream().map(ParserPlugin::id).toList();
    }

    @Test
    void aPackParserJoinsTheCatalogAfterTheBuiltinsAndDeregisterRestoresIt() {
        List<String> before = ids();
        try {
            Parsers.register(stub("acme_cdr", "com.acme.AcmeIngester"), "acme-1.jar");
            List<String> after = ids();
            assertEquals(before.size() + 1, after.size());
            assertEquals(before, after.subList(0, before.size()), "catalog order kept: built-ins, classpath, then packs");
            assertEquals("acme_cdr", after.get(after.size() - 1));
            assertEquals(java.util.Optional.of("acme-1.jar"), Parsers.ownerOf("acme_cdr"));
            assertEquals("pack:acme-1.jar", Parsers.sourceOf("acme_cdr"));
            assertEquals("builtin", Parsers.sourceOf("delimited"));
            assertEquals("classpath", Parsers.sourceOf("xml"));
            assertEquals("acme_cdr", Parsers.forIngester("com.acme.AcmeIngester").orElseThrow().id());
        } finally {
            Parsers.deregister("acme-1.jar");
        }
        assertEquals(before, ids(), "deregister takes the pack's parser back and nothing else");
        assertTrue(Parsers.get("acme_cdr").isEmpty());
        assertTrue(Parsers.ownerOf("acme_cdr").isEmpty());
    }

    @Test
    void aPackParserCollidingWithABuiltinOrAClasspathParserIsRefused() {
        List<String> before = ids();
        ParserPlugin builtinDelimited = Parsers.get("delimited").orElseThrow();
        IllegalStateException b = assertThrows(IllegalStateException.class,
                () -> Parsers.register(stub("delimited", null), "evil.jar"));
        assertTrue(b.getMessage().contains("'delimited'"), b.getMessage());
        assertThrows(IllegalStateException.class, () -> Parsers.register(stub("asn1", null), "evil.jar"),
                "a classpath (ServiceLoader) parser is as un-replaceable as a built-in");
        assertSame(builtinDelimited, Parsers.get("delimited").orElseThrow(), "the built-in still answers");
        assertEquals(before, ids());
        assertTrue(Parsers.ownerOf("delimited").isEmpty());
    }

    @Test
    void aSecondPackClaimingATakenIdOrIngesterIsRefusedAndTheFirstPackWins() {
        ParserPlugin first = stub("acme_cdr", "com.acme.AcmeIngester");
        try {
            Parsers.register(first, "acme-1.jar");
            assertThrows(IllegalStateException.class,
                    () -> Parsers.register(stub("acme_cdr", "com.other.Ingester"), "other.jar"), "same id");
            assertThrows(IllegalStateException.class,
                    () -> Parsers.register(stub("other_cdr", "com.acme.AcmeIngester"), "other.jar"),
                    "same ingester FQCN: which pack's loader resolves it would depend on load order");
            assertThrows(IllegalStateException.class,
                    () -> Parsers.register(stub("xml_too", "com.gamma.ingester.XmlRecordIngester"), "other.jar"),
                    "an ingester a classpath parser already names");
            assertSame(first, Parsers.get("acme_cdr").orElseThrow());
            assertTrue(Parsers.get("other_cdr").isEmpty());
            assertTrue(Parsers.get("xml_too").isEmpty());
            // The positive probe for the refusals above: the SAME second pack with free names registers.
            Parsers.register(stub("other_cdr", "com.other.Ingester"), "other.jar");
            assertEquals(java.util.Optional.of("other.jar"), Parsers.ownerOf("other_cdr"));
        } finally {
            Parsers.deregister("acme-1.jar");
            Parsers.deregister("other.jar");
        }
    }

    @Test
    void aPackParserWithAnInvalidIdIsRefused() {
        assertThrows(IllegalStateException.class, () -> Parsers.register(stub("Bad-Id", null), "p.jar"));
        assertTrue(Parsers.ownerOf("Bad-Id").isEmpty());
    }
}
