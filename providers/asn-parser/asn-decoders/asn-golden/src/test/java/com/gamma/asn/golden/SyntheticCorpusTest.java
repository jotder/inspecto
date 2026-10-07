package com.gamma.asn.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.asn.core.ByteSource;
import com.gamma.asn.core.Framing;
import com.gamma.asn.core.RecordReader;
import com.gamma.asn.core.RecoveryPolicy;
import com.gamma.asn.core.Strictness;
import com.gamma.asn.schema.Asn1Parser;
import com.gamma.asn.schema.CompiledSchema;
import com.gamma.asn.schema.DecoderRegistry;
import com.gamma.asn.schema.NamedNode;
import com.gamma.asn.schema.SchemaBinder;
import com.gamma.asn.schema.SchemaCompiler;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DATA-GOV-1's committed complement (2026-09-06): the synthetic corpus under {@code asn-parser/corpus-synthetic/}
 * runs on EVERY reactor build — no opt-in flag, no real file. Each case = a hand-written grammar, a hex-text BER
 * file, a framing spec and the {@code RecordMapper} maps the new stack must produce for it.
 *
 * <p>Regenerate {@code expected.jsonl} after an intentional decoder change with
 * {@code -Dasn.synthetic.write=true}, then review the diff — a changed expectation is a behaviour change.
 */
class SyntheticCorpusTest {

    private static final Path BASE = Path.of("..", "..", "corpus-synthetic");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> everySyntheticCaseDecodesToItsExpectedRecords() throws IOException {
        assertTrue(Files.isDirectory(BASE), "synthetic corpus missing: " + BASE.toAbsolutePath().normalize());
        List<Path> cases;
        try (Stream<Path> s = Files.list(BASE)) {
            cases = s.filter(p -> Files.isRegularFile(p.resolve("case.json"))).sorted().toList();
        }
        assertFalse(cases.isEmpty(), "no case directories under " + BASE);
        return cases.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> runCase(dir)));
    }

    private static void runCase(Path dir) throws IOException {
        JsonNode spec = JSON.readTree(Files.readString(dir.resolve("case.json"), StandardCharsets.UTF_8));
        String grammar = Files.readString(dir.resolve("grammar.asn"), StandardCharsets.UTF_8);
        byte[] bytes = parseHex(Files.readString(dir.resolve("data.hex"), StandardCharsets.UTF_8));

        CompiledSchema schema = SchemaCompiler.compile(Asn1Parser.parse(grammar), spec.path("root").asText());
        DecoderRegistry registry = DecoderRegistry.withDefaults();
        Set<Integer> padding = new java.util.HashSet<>();
        spec.path("padding").forEach(n -> padding.add(n.asInt()));
        JsonNode rh = spec.path("recordHeaderLength");
        Framing framing = Framing.of(new Framing.FramingSpec(
                spec.path("fileHeaderLength").asLong(0), 0, padding,
                rh.isNull() || rh.isMissingNode() ? null : Framing.RecordHeaderSpec.skipOnly(rh.asInt())));

        List<String> errors = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        try (ByteSource src = ByteSource.of(bytes)) {
            RecordReader reader = new RecordReader(src, framing, Strictness.BER, RecoveryPolicy.STOP_FILE,
                    e -> errors.add("record " + e.recordIndex() + " @" + e.fileOffset() + ": " + e.message()));
            SchemaBinder binder = new SchemaBinder(schema, src, registry);
            while (reader.hasNext()) {
                NamedNode node = binder.bind(reader.next());
                actual.add(JSON.writeValueAsString(RecordMapper.toMap(node)));
            }
        }
        assertEquals(List.of(), errors, "the synthetic file must decode without reader errors");
        assertFalse(actual.isEmpty(), "no records decoded from " + dir.getFileName());

        Path expectedFile = dir.resolve("expected.jsonl");
        if (Boolean.getBoolean("asn.synthetic.write")) {
            Files.writeString(expectedFile, String.join("\n", actual) + "\n", StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(expectedFile), "no expected.jsonl for " + dir.getFileName()
                + " — generate it once with -Dasn.synthetic.write=true and review it");
        List<String> expected = Files.readAllLines(expectedFile, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).toList();
        assertEquals(expected, actual, "decoded records differ from expected.jsonl for " + dir.getFileName());
    }

    /** Hex text: {@code #} comments to end of line, all whitespace ignored. */
    static byte[] parseHex(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            int hash = line.indexOf('#');
            sb.append((hash >= 0 ? line.substring(0, hash) : line).replaceAll("\\s+", ""));
        }
        return HexFormat.of().parseHex(sb.toString());
    }
}
