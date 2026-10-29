package com.gamma.intelligence.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GlossaryLoader} against the real {@code docs/GLOSSARY.md} (INTELLIGENCE-GLOSSARY-ANNOTATED-1): every entry
 * arrives whole — the first line alone stopped mid-sentence ("…a database, cloud") — and annotated entries
 * ({@code **Entity / Link** *(business graphs only)* —}) are no longer skipped.
 */
class GlossaryLoaderTest {

    private static final Map<String, String> GLOSSARY = GlossaryLoader.load();

    /** Each term's definition must reach past the line its first line stopped at, to the sentence's own end. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Dataset     | The umbrella for any queryable relation the BI layer can bind to: Table | Derived Table | Reference Dataset | View.",
            "Connection  | Named endpoint + credentials for reaching a remote system (SFTP/FTP/FTPS, a database, cloud storage).",
            "Table       | A Hive-style root directory of Parquet files, partitioned by date / partition key and split by event type.",
            "Widget      | A Visualization Type + Config + a binding to a Dataset's resultset metadata — i.e. the configured, renderable instance.",
            "Expectation | A data-quality rule that validates records against a Schema (non-null, range, regex, referential).",
    })
    void multiLineEntriesArriveWhole(String term, String firstSentence) {
        String definition = GLOSSARY.get(term);
        assertNotNull(definition, term);
        assertTrue(definition.startsWith(firstSentence), term + " was: " + definition);
    }

    @Test
    void connectionKeepsItsLaterSentencesToo() {
        assertTrue(GLOSSARY.get("Connection").endsWith("Holds *how to reach it*, never *what to collect*."),
                GLOSSARY.get("Connection"));
    }

    @Test
    void singleLineEntriesAreCompleteSentences() {
        for (String term : List.of("Investigation", "Graph Run")) {
            String definition = GLOSSARY.get(term);
            assertNotNull(definition, term);
            assertTrue(definition.endsWith("."), term + " was: " + definition);
        }
        assertTrue(GLOSSARY.get("Investigation").startsWith("The Link Analysis enquiry object: an ordered, replayable log"));
        assertTrue(GLOSSARY.get("Graph Run").startsWith("One execution of one Link Analysis graph algorithm (of 29)"));
    }

    @Test
    void annotatedEntriesAreParsedAndAnswerForEachSlashedTerm() {
        String expected = "An Entity is a business node (a caller, an account); a Link is a business edge between Entities "
                + "(a call, a transaction) carrying typed attributes (call-type, duration). ⛔ Never use Entity/Link for "
                + "artifacts (Component/Part) or assets (Asset/Lineage).";
        assertEquals(expected, GLOSSARY.get("Entity / Link"));
        assertEquals(expected, GLOSSARY.get("Entity"));
        assertEquals(expected, GLOSSARY.get("Link"));
        // An annotation holding its own dash and a link with nested brackets still parses.
        assertTrue(GLOSSARY.get("Anomaly Model").startsWith("The authored"), GLOSSARY.get("Anomaly Model"));
        assertTrue(GLOSSARY.get("Segment").startsWith("One record kind a plugin ingester emits, with its own flat"),
                GLOSSARY.get("Segment"));
    }

    @Test
    void everyEntryEndsAsASentence() {
        // No definition may stop mid-sentence: each ends with a full stop, a closing bracket or emphasis after one.
        for (Map.Entry<String, String> e : GLOSSARY.entrySet()) {
            assertTrue(e.getValue().matches("(?s).*[.!?…:][)*_\"”’`]*$"),
                    () -> e.getKey() + " ends mid-sentence: …" + e.getValue().substring(Math.max(0, e.getValue().length() - 80)));
        }
    }

    @Test
    void anEntryRunsToTheEndOfItsParagraph() {
        Map<String, String> parsed = GlossaryLoader.parse(List.of(
                "## 1. Heading",
                "**Alpha** *(added, see [x](y.md) — z)* — First line of alpha",
                "continues here \\| and **ends**. A wrapped line may start with a bold term:",
                "**Beta** — and it is still alpha's text.",
                "",
                "**Gamma** — Gamma line.",
                "",
                "Not part of gamma.",
                "",
                "**Delta / Epsilon** - Short.",
                "## Next",
                "**Gamma**, mid-sentence bold is not an entry."));
        assertEquals("First line of alpha continues here | and ends. A wrapped line may start with a bold term: "
                + "Beta — and it is still alpha's text.", parsed.get("Alpha"));
        assertNull(parsed.get("Beta"));
        assertEquals("Gamma line.", parsed.get("Gamma"));
        assertEquals("Short.", parsed.get("Delta"));
        assertEquals("Short.", parsed.get("Epsilon"));
        assertEquals(5, parsed.size());
    }

    @Test
    void aHardWrappedBoldTermInsideAnEntryIsNotANewEntry() {
        // GLOSSARY.md's Annotation entry wraps onto a line starting "**Annotation Target** — a `(targetKind, …"
        assertTrue(GLOSSARY.get("Annotation").endsWith("never \"metadata\" (that is Catalog vocabulary, §3)."),
                GLOSSARY.get("Annotation"));
        assertTrue(GLOSSARY.get("Annotation Target").startsWith("What an Annotation is attached to"),
                GLOSSARY.get("Annotation Target"));
    }
}
