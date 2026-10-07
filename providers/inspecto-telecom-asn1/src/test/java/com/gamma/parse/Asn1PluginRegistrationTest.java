package com.gamma.parse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ASN.1 plugin is discovered through the engine's {@code ParserPlugin} ServiceLoader seam from THIS module's
 * own services file, and keeps the persisted ingester class name — a Pipeline's TOON names
 * {@code com.gamma.ingester.Asn1RecordIngester}, so moving the plugin out of the engine must not move that string.
 * (Moved out of the engine's ParsersTest with the plugin, MODULE-REORG-1 P7.)
 */
class Asn1PluginRegistrationTest {

    @Test
    void theServiceLoaderRegistersAsn1BesideTheEngineParsers() {
        // Built-ins first, then providers in classpath discovery order - which of xml / asn1 comes first depends on
        // the classpath (module classes before the engine jar in a test run; inspecto.jar first in a bundle).
        List<String> ids = Parsers.catalog().stream().map(ParserPlugin::id).toList();
        assertEquals(List.of("delimited", "fixedwidth", "json", "parquet", "xlsx", "text_regex"), ids.subList(0, 6));
        assertEquals(java.util.Set.of("xml", "asn1"), new java.util.HashSet<>(ids.subList(6, ids.size())));
        assertEquals("classpath", Parsers.sourceOf("asn1"));
    }

    @Test
    void asn1IsHierarchicalIngestableAndNamesTheUnchangedIngesterClass() {
        ParserPlugin asn1 = Parsers.get("asn1").orElseThrow();
        assertTrue(asn1.hierarchical());
        assertTrue(Parsers.ingestable(asn1), "Asn1RecordIngester flattens onto segment schemas");
        assertEquals("com.gamma.ingester.Asn1RecordIngester", asn1.ingesterClass().orElseThrow());
        assertEquals(asn1, Parsers.forIngester("com.gamma.ingester.Asn1RecordIngester").orElseThrow());
    }

    @Test
    void aPackCannotReplaceTheClasspathAsn1Parser() {
        ParserPlugin impostor = new Asn1ParserPlugin();
        assertThrows(IllegalStateException.class, () -> Parsers.register(impostor, "evil.jar"),
                "a classpath (ServiceLoader) parser is as un-replaceable as a built-in");
    }
}
