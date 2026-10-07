package com.gamma.asn.schema;

import com.gamma.asn.schema.ast.ModuleAst;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The grammars in config/ are hand-doctored, not clean X.680 (REDESIGN.md §2/§7) — e.g.
 * tap 3.12.asn has a botched global replace and nrtrde_2.1.asn lowercases keywords.
 * They must load via the lenient path with every skipped construct reported; Phase 2
 * repairs the files properly.
 */
class RealGrammarsTest {

    private static final Path CONFIG = Path.of("..", "..", "config");

    private static String read(String relative) throws IOException {
        assumeTrue(Boolean.getBoolean("asn.corpus.tests"),
                "corpus-backed tests are opt-in; enable with -Dasn.corpus.tests=true");
        Path p = CONFIG.resolve(relative);
        assumeTrue(Files.exists(p), "sample grammar not present: " + p);
        return Files.readString(p, StandardCharsets.ISO_8859_1);
    }

    @Test
    void tap312LoadsLeniently() throws IOException {
        List<String> warnings = new ArrayList<>();
        ModuleAst module = Asn1Parser.parseLenient(read("generic/tap/tap 3.12.asn"), warnings).getFirst();
        assertEquals("TAP-0312", module.name());
        assertTrue(module.types().size() > 250, "only " + module.types().size() + " types");

        CompiledSchema schema = SchemaCompiler.compileLenient(
                List.of(module), "DataInterChange", warnings);
        assertEquals(CompiledType.Kind.CHOICE, schema.root().kind());
        // TransferBatch ::= [1] SEQUENCE — implicit context tag in an IMPLICIT TAGS module
        CompiledType transferBatch = schema.types().get("TransferBatch");
        assertNotNull(transferBatch);
        assertEquals(new TagKey(com.gamma.asn.core.TagClass.CONTEXT, 1), transferBatch.tag());
        assertEquals(CompiledType.Kind.SEQUENCE, transferBatch.kind());

        // the known dirt is contained: a handful of warnings, not silence and not collapse
        assertTrue(warnings.size() < 30, warnings.size() + " warnings: " + warnings);
    }

    @Test
    void nrtrdeLoadsLeniently() throws IOException {
        List<String> warnings = new ArrayList<>();
        ModuleAst module = Asn1Parser.parseLenient(read("generic/nrtrde/nrtrde_2.1.asn"), warnings).getFirst();
        assertTrue(module.types().size() > 10, "only " + module.types().size() + " types");
        assertTrue(module.types().containsKey("Nrtrde"), "types: " + module.types().keySet());
        SchemaCompiler.compileLenient(List.of(module), "Nrtrde", warnings);
        assertTrue(warnings.size() < 30, warnings.size() + " warnings: " + warnings);
    }
}
