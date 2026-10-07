package com.gamma.parse.testkit;

import com.gamma.config.spec.FieldSpec;
import com.gamma.config.spec.FieldType;
import com.gamma.parse.ParseResult;
import com.gamma.parse.ParserPlugin;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves the ParserPlugin TCK can fail (MODULE-REORG-1 P5b): broken in-test parsers must go red, a sound one green. */
class ParserPluginTckSelfTest {

    @FunctionalInterface
    private interface Preview { ParseResult run(byte[] sample) throws Exception; }

    /** A parser whose misbehaviour is chosen by the test. */
    private static class Plug implements ParserPlugin {
        final String id;
        final Preview preview;
        Plug(String id, Preview preview) { this.id = id; this.preview = preview; }
        @Override public String id() { return id; }
        @Override public String label() { return "Label"; }
        @Override public boolean hierarchical() { return false; }
        @Override public List<FieldSpec> grammarSchema() { return List.of(FieldSpec.of("a.b", "AB", FieldType.STRING, "d")); }
        @Override public ParseResult preview(byte[] sample, Map<String, Object> grammar) throws Exception { return preview.run(sample); }
    }

    private static final Preview STRICT = s -> {
        if (s == null || s.length == 0) throw new IllegalArgumentException("sample content is required");
        return new ParseResult.Tree(0, List.of());
    };

    private static ParserPluginContract contract(ParserPlugin p) {
        return new ParserPluginContract() {
            @Override protected ParserPlugin plugin() { return p; }
        };
    }

    @Test
    void aSoundParserPasses() {
        ParserPluginContract c = contract(new Plug("good", STRICT));
        assertDoesNotThrow(() -> {
            c.idIsALowerCaseToken();
            c.idIsServedByExactlyOneParser();
            c.grammarSchemaIsWellFormed();
            c.theDeclaredIngesterResolves();
            c.hostileSamplesAreRefusedOrParsedNeverCrash();
            c.suggestNeverThrows();
        });
    }

    @Test
    void aBadIdIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(new Plug("Xml", STRICT)).idIsALowerCaseToken());
    }

    @Test
    void aDuplicateIdIsCaught() {
        ParserPlugin a = new Plug("dup", STRICT);
        ParserPluginContract c = new ParserPluginContract() {
            @Override protected ParserPlugin plugin() { return a; }
            @Override protected Iterable<ParserPlugin> registered() { return List.of(a, new Plug("dup", STRICT)); }
        };
        assertThrows(AssertionFailedError.class, c::idIsServedByExactlyOneParser);
    }

    @Test
    void aBadGrammarSchemaIsCaught() {
        ParserPlugin dupFields = new Plug("dups", STRICT) {
            @Override public List<FieldSpec> grammarSchema() {
                return List.of(FieldSpec.of("x", "X", FieldType.STRING, "d"), FieldSpec.of("x", "X again", FieldType.STRING, "d"));
            }
        };
        assertThrows(AssertionFailedError.class, () -> contract(dupFields).grammarSchemaIsWellFormed());
        ParserPlugin blankPath = new Plug("blank", STRICT) {
            @Override public List<FieldSpec> grammarSchema() { return List.of(FieldSpec.of(" ", "X", FieldType.STRING, "d")); }
        };
        assertThrows(AssertionFailedError.class, () -> contract(blankPath).grammarSchemaIsWellFormed());
    }

    @Test
    void anIngesterThatDoesNotResolveIsCaught() {
        ParserPlugin ghost = new Plug("ghost", STRICT) {
            @Override public Optional<String> ingesterClass() { return Optional.of("com.example.NoSuchIngester"); }
        };
        assertThrows(AssertionFailedError.class, () -> contract(ghost).theDeclaredIngesterResolves());
    }

    @Test
    void anErrorOnHostileInputIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(new Plug("deep", s -> {
            if (s != null && s.length > 100_000) throw new StackOverflowError();
            return new ParseResult.Tree(0, List.of());
        })).hostileSamplesAreRefusedOrParsedNeverCrash());
    }

    @Test
    void aHangOnHostileInputIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(new Plug("hang", s -> {
            Thread.sleep(60_000);
            return new ParseResult.Tree(0, List.of());
        })).hostileSamplesAreRefusedOrParsedNeverCrash());
    }

    @Test
    void aNullResultIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(new Plug("null", s -> null)).hostileSamplesAreRefusedOrParsedNeverCrash());
    }

    @Test
    void aSuggestThatThrowsOrReturnsNullIsCaught() {
        ParserPlugin throwing = new Plug("sthrow", STRICT) {
            @Override public Map<String, Object> suggest(byte[] sample) { throw new IllegalStateException("x"); }
        };
        assertThrows(AssertionFailedError.class, () -> contract(throwing).suggestNeverThrows());
        ParserPlugin nulls = new Plug("snull", STRICT) {
            @Override public Map<String, Object> suggest(byte[] sample) { return null; }
        };
        assertThrows(AssertionFailedError.class, () -> contract(nulls).suggestNeverThrows());
    }
}
