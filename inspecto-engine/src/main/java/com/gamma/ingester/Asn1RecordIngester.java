package com.gamma.ingester;

import com.gamma.asn.core.ByteSource;
import com.gamma.config.safety.PathJail;
import com.gamma.asn.core.Framing;
import com.gamma.asn.core.ParseError;
import com.gamma.asn.core.RecoveryPolicy;
import com.gamma.asn.core.Strictness;
import com.gamma.asn.facade.Asn1Decoder;
import com.gamma.asn.facade.RecordMapper;
import com.gamma.asn.schema.NamedNode;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RecordSink;
import com.gamma.etl.StreamingFileIngester;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link StreamingFileIngester} for ASN.1 BER/DER files, over the {@code asn-facade} decoder
 * ({@link Asn1Decoder}). This is what makes {@code com.gamma.parse.Asn1ParserPlugin} genuinely
 * {@code ingestable} rather than preview-only: records decode, flatten onto segment schemas, and
 * land in Tables through the ordinary {@code parsing.plugin} machinery.
 *
 * <h3>Segment selection</h3>
 * One segment key per record type. The decoded record's own name is matched against the keys of
 * {@code processing.segments} — for the vendor grammars in the corpus the root is a union
 * (a SET/SEQUENCE whose tagged components are the record types), so that name is the matched
 * <em>alternative</em> (e.g. {@code moCallRecord}); for a single-type grammar it is the root type
 * name. Records whose name is not a declared segment are counted as junk and skipped, exactly as
 * {@link TypedRecordIngester} treats an unknown type prefix.
 *
 * <h3>Columns</h3>
 * A segment's columns are its schema's {@code raw.fields} — but unlike the positional text
 * ingesters, {@code raw.fields[].selector} is a <b>dotted path</b> into the decoded record
 * ({@link RecordMapper#toMap}), e.g. {@code servedIMSI} or {@code recordExtensions.chargeAmount}.
 * A trailing {@code EVENT_TYPE} column is derived (the segment key), so schemas can partition by
 * record type without redeclaring it — the {@link TypedRecordIngester} convention.
 *
 * <p>⚠ A selector must name a <b>leaf</b>. Resolving to a container (a sub-record, or a repeated
 * field's list) yields {@code NULL}, not a stringified subtree — deliberately matching the legacy
 * transform engine, which likewise drops lists of scalars rather than inventing a join. A repeated
 * field is not one column; give it its own segment.
 *
 * <h3>Configuration</h3>
 * <pre>
 * parsing:
 *   frontend: plugin
 *   plugin:
 *     ingester: com.gamma.ingester.Asn1RecordIngester
 *     segments:
 *       moCallRecord: config/cdr/mo_call_schema.toon
 *       smsRecord:    config/cdr/sms_schema.toon
 *     ingester_config:
 *       grammar: config/cdr/mtnOCC.asn      # path to the ASN.1 module (or grammar_text: inline
 *                                           # X.680 source — what `frontend: asn1` synthesizes;
 *                                           # text wins when both are present)
 *       root_type: CallEventRecord          # required
 *       strictness: BER                     # BER (default) | DER | CER
 *       file_header_length: 0               # optional, see Asn1ParserPlugin
 *       record_header_length: 0             # optional
 * </pre>
 *
 * <h3>Failure handling</h3>
 * A malformed record makes the reader stop (these framings carry no length prefix, so there is no
 * reliable resync). Rather than silently ingesting the prefix, any parse error fails the file so
 * the framework quarantines it {@code QUARANTINED_UNREADABLE} — a half-loaded CDR file is worse
 * than a quarantined one. Zero emitted records ⇒ {@code QUARANTINED_MISMATCH}, as usual.
 */
public final class Asn1RecordIngester implements StreamingFileIngester {

    /** Public no-arg constructor required for reflective load by {@code ConsignmentIngestor}. */
    public Asn1RecordIngester() {}

    /** The derived column carrying the segment key, appended to every segment. */
    static final String EVENT_TYPE = "EVENT_TYPE";

    @Override
    public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
        Map<String, Object> ic = cfg.schemas().ingesterConfig();
        // `grammar_text` carries the module INLINE (what `frontend: asn1` synthesizes — the drawer
        // authors text, not a file); `grammar` stays the path-jailed file reference. Text wins when
        // both are present, matching the "parsing: keys win" overlay rule.
        String grammarText = str(ic.get("grammar_text"));
        String grammarPath = str(ic.get("grammar"));
        String rootType = str(ic.get("root_type"));
        if (grammarText.isEmpty() && grammarPath.isEmpty())
            throw new IllegalArgumentException(
                    "ingester_config.grammar_text (inline module) or ingester_config.grammar "
                    + "(path to the ASN.1 module) is required for Asn1RecordIngester");
        if (rootType.isEmpty())
            throw new IllegalArgumentException(
                    "ingester_config.root_type is required for Asn1RecordIngester");

        String moduleSource = grammarText;
        String moduleLabel  = "ingester_config.grammar_text";
        if (grammarText.isEmpty()) {
            // Jailed before the readability probe: an escaping ref must be refused outright, not reported
            // as "not readable", which leaks whether a path outside the roots exists.
            Path grammarFile = PathJail.requireUnderAny(
                    PathJail.allowedRoots(), grammarPath, "ingester_config.grammar");
            if (!Files.isReadable(grammarFile))
                throw new IllegalArgumentException("ingester_config.grammar not readable: " + grammarFile);
            moduleSource = Files.readString(grammarFile, StandardCharsets.UTF_8);
            moduleLabel  = grammarFile.toString();
        }
        Asn1Decoder decoder;
        try {
            decoder = Asn1Decoder.compile(moduleSource, rootType);
        } catch (Exception badGrammar) {
            throw new IllegalArgumentException(
                    "invalid ASN.1 grammar " + moduleLabel + ": " + badGrammar.getMessage(), badGrammar);
        }

        Map<String, List<String>> selectorsByKey = declareSegments(cfg, sink);

        List<ParseError> errors = new ArrayList<>();
        try (ByteSource src = ByteSource.map(file.toPath())) {
            Iterator<NamedNode> records = decoder.decode(src, framing(ic), strictness(str(ic.get("strictness"))),
                    RecoveryPolicy.STOP_FILE, errors::add).iterator();
            while (records.hasNext()) {
                NamedNode record = records.next();
                List<String> selectors = selectorsByKey.get(record.name());
                if (selectors == null) {          // not a declared segment → junk candidate
                    sink.junk();
                    continue;
                }
                Map<String, Object> decoded = RecordMapper.toMap(record);
                Object[] values = new Object[selectors.size() + 1];
                for (int i = 0; i < selectors.size(); i++) {
                    values[i] = scalar(resolve(decoded, selectors.get(i)));
                }
                values[selectors.size()] = record.name();   // trailing EVENT_TYPE
                sink.emit(record.name(), values);
            }
        }
        if (!errors.isEmpty()) {
            ParseError first = errors.getFirst();
            throw new IOException("ASN.1 decode failed in " + file.getName() + " at record "
                    + first.recordIndex() + " (offset " + first.fileOffset() + "): " + first.message());
        }
    }

    /**
     * Declare every segment's columns up front (raw.fields + the derived {@code EVENT_TYPE}) and
     * return each key's selector list, so the per-record loop never re-walks the config map.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> declareSegments(PipelineConfig cfg, RecordSink sink) throws Exception {
        if (cfg.schemas().segments() == null || cfg.schemas().segments().isEmpty())
            throw new IllegalArgumentException("Asn1RecordIngester requires at least one segment");
        Map<String, List<String>> selectorsByKey = new LinkedHashMap<>();
        for (String key : cfg.schemas().segments().keySet()) {
            Object raw = cfg.schemas().segments().get(key).get("raw");
            Object fields = raw instanceof Map<?, ?> m ? m.get("fields") : null;
            if (!(fields instanceof List<?> fl) || fl.isEmpty())
                throw new IllegalArgumentException("segment '" + key + "' declares no raw.fields");
            List<String> columns = new ArrayList<>(fl.size() + 1);
            List<String> selectors = new ArrayList<>(fl.size());
            for (Object o : fl) {
                Map<String, Object> f = (Map<String, Object>) o;
                String name = str(f.get("name"));
                String selector = str(f.get("selector"));
                if (selector.isEmpty()) selector = name;   // a field whose path IS its name
                columns.add(name);
                selectors.add(selector);
            }
            columns.add(EVENT_TYPE);
            sink.define(key, columns);
            selectorsByKey.put(key, selectors);
        }
        return selectorsByKey;
    }

    /** Walk a dotted selector into the decoded record; missing at any step ⇒ {@code null}. */
    private static Object resolve(Map<String, Object> decoded, String selector) {
        Object current = decoded;
        for (String step : selector.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = m.get(step);
            if (current == null) return null;
        }
        return current;
    }

    /** Leaves stringify (the sink stores VARCHAR); containers are not a column — see the class doc. */
    private static Object scalar(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) return null;
        return String.valueOf(value);
    }

    /** Mirrors {@code Asn1ParserPlugin}'s framing: the two knobs the corpus varies by. */
    private static Framing framing(Map<String, Object> ic) {
        int fileHeader = nonNegative(ic.get("file_header_length"), "file_header_length");
        int recordHeader = nonNegative(ic.get("record_header_length"), "record_header_length");
        return Framing.of(new Framing.FramingSpec(fileHeader, 0, Set.of(0x00, 0xFF),
                recordHeader == 0 ? null : Framing.RecordHeaderSpec.skipOnly(recordHeader)));
    }

    private static Strictness strictness(String v) {
        return switch (v.toUpperCase()) {
            case "", "BER" -> Strictness.BER;
            case "DER" -> Strictness.DER;
            case "CER" -> Strictness.CER;
            default -> throw new IllegalArgumentException(
                    "ingester_config.strictness must be BER, DER or CER, got: " + v);
        };
    }

    private static int nonNegative(Object v, String field) {
        if (v == null) return 0;
        int n;
        try {
            n = Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("ingester_config." + field + " must be a number, got: " + v);
        }
        if (n < 0) throw new IllegalArgumentException("ingester_config." + field + " must not be negative");
        return n;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
