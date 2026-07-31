package com.gamma.asn.facade;

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
import com.gamma.asn.schema.ast.ModuleAst;
import com.gamma.asn.transform.LegacyTransformEngine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Public bytes-to-records facade over the new decoder stack: compile a grammar once, then decode
 * any number of {@link ByteSource}s against it. This is the composition {@code ParityCheck.checkFile}
 * (asn-golden) exercises ad hoc for the parity harness — promoted here as a real, reusable API so a
 * future {@code com.gamma.parse.ParserPlugin} adapter (or any other caller) doesn't have to re-derive
 * it. One instance is immutable and safe to reuse/share across files and threads (REDESIGN.md §4.4);
 * {@link SchemaBinder} instances are still created per {@link ByteSource} since they cache decoders
 * keyed by that source.
 */
public final class Asn1Decoder {

    private final CompiledSchema schema;
    private final DecoderRegistry registry;

    private Asn1Decoder(CompiledSchema schema, DecoderRegistry registry) {
        this.schema = schema;
        this.registry = registry;
    }

    /** Compile a grammar (strict) against the default (universal + telecom) decoder registry. */
    public static Asn1Decoder compile(String grammarText, String rootTypeName) {
        List<ModuleAst> modules = Asn1Parser.parse(grammarText);
        CompiledSchema schema = SchemaCompiler.compile(modules, rootTypeName);
        return new Asn1Decoder(schema, DecoderRegistry.withDefaults());
    }

    /** Lenient variant: parse/compile errors collect into {@code warnings} instead of throwing. */
    public static Asn1Decoder compileLenient(String grammarText, String rootTypeName, List<String> warnings) {
        List<ModuleAst> modules = Asn1Parser.parseLenient(grammarText, warnings);
        CompiledSchema schema = SchemaCompiler.compileLenient(modules, rootTypeName, warnings);
        return new Asn1Decoder(schema, DecoderRegistry.withDefaults());
    }

    /** Wrap an already-compiled schema/registry (e.g. a customer registry with extra vendor decoders). */
    public static Asn1Decoder of(CompiledSchema schema, DecoderRegistry registry) {
        return new Asn1Decoder(schema, registry);
    }

    /** The schema this decoder binds records against. */
    public CompiledSchema schema() {
        return schema;
    }

    /** The decoder registry (universal types + any vendor decoders) this decoder resolves leaves with. */
    public DecoderRegistry registry() {
        return registry;
    }

    /**
     * Decode every record in {@code src} into a schema-bound {@link NamedNode} tree, lazily — the
     * returned stream drives one {@link RecordReader} under the given wire framing/strictness/recovery.
     * The caller owns {@code src} (open/close it, typically via try-with-resources); this method does
     * not close it.
     */
    public Stream<NamedNode> decode(ByteSource src, Framing framing, Strictness strictness,
                                     RecoveryPolicy policy, RecordReader.ErrorListener errors) {
        RecordReader reader = new RecordReader(src, framing, strictness, policy, errors);
        SchemaBinder binder = new SchemaBinder(schema, src, registry);
        Iterator<NamedNode> it = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return reader.hasNext();
            }

            @Override
            public NamedNode next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return binder.bind(reader.next());
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false);
    }

    /** Convenience: bare back-to-back TLVs (no framing), strict BER, stop on the first error. */
    public Stream<NamedNode> decode(ByteSource src) {
        return decode(src, Framing.none(), Strictness.BER, RecoveryPolicy.STOP_FILE, error -> { });
    }

    /**
     * The full bytes-to-rows pipeline: decode every record, convert each to the legacy record-map
     * shape ({@link RecordMapper}), and flatten through {@code engine} ({@link LegacyTransformEngine})
     * keyed by {@code recordType}. Collected eagerly (a transform engine's cartesian joins mean a
     * lazy per-record stream buys nothing here).
     */
    public List<Map<String, Object>> decodeToRows(ByteSource src, Framing framing, Strictness strictness,
                                                   RecoveryPolicy policy, RecordReader.ErrorListener errors,
                                                   String recordType, LegacyTransformEngine engine) {
        List<Map<String, Object>> rows = new ArrayList<>();
        decode(src, framing, strictness, policy, errors)
                .forEach(node -> rows.addAll(engine.transform(recordType, RecordMapper.toMap(node))));
        return rows;
    }
}
