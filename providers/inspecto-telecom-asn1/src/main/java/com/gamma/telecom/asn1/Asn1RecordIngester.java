package com.gamma.telecom.asn1;

import com.gamma.asn.core.ByteSource;
import com.gamma.asn.core.Framing;
import com.gamma.asn.core.ParseError;
import com.gamma.asn.core.RecoveryPolicy;
import com.gamma.asn.core.Strictness;
import com.gamma.asn.facade.Asn1Decoder;
import com.gamma.asn.facade.RecordMapper;
import com.gamma.asn.schema.NamedNode;
import com.gamma.etl.ChildSpec;
import com.gamma.etl.DeriveSpec;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RecordSink;
import com.gamma.etl.StreamingFileIngester;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link StreamingFileIngester} for ASN.1 BER/DER files, over the {@code asn-facade} decoder
 * ({@link Asn1Decoder}). This is what makes {@code com.gamma.telecom.asn1.Asn1ParserPlugin} genuinely
 * {@code ingestable} rather than preview-only: records decode, flatten onto segment schemas, and
 * land in Tables through the ordinary {@code parsing.plugin} machinery.
 *
 * <h3>Segment selection</h3>
 * One segment key per record type. The decoded record's own name is matched against the keys of
 * {@code processing.segments} — for the vendor grammars in the corpus the root is a union
 * (a SET/SEQUENCE whose tagged components are the record types), so that name is the matched
 * <em>alternative</em> (e.g. {@code moCallRecord}); for a single-type grammar it is the root type
 * name. Records whose name is not a declared segment are counted as junk and skipped, exactly as
 * {@code TypedRecordIngester} treats an unknown type prefix.
 *
 * <h3>Columns</h3>
 * A segment's columns are its schema's {@code raw.fields} — but unlike the positional text
 * ingesters, {@code raw.fields[].selector} is a <b>dotted path</b> into the decoded record
 * ({@link RecordMapper#toMap}), e.g. {@code servedIMSI} or {@code recordExtensions.chargeAmount}.
 * A trailing {@code EVENT_TYPE} column is derived (the segment key), so schemas can partition by
 * record type without redeclaring it — the {@code TypedRecordIngester} convention.
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
 *     ingester: com.gamma.telecom.asn1.Asn1RecordIngester
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
 *       max_value_bytes: 67108864           # optional single-value cap (default 64 MiB)
 * </pre>
 *
 * <h3>Derived columns (Parsing D9 slice 1)</h3>
 * An optional {@code ingester_config.derive} block ({@link DeriveSpec}: {@code select} / {@code reduce} over a
 * repeated child) appends VARCHAR columns after {@code raw.fields} and before {@code EVENT_TYPE}; row
 * cardinality is unchanged and a segment without {@code derive} is byte-identical to before. A list over
 * {@code max_list_elements} fails the file (QUARANTINED_UNREADABLE) rather than truncating an aggregate.
 *
 * <h3>Child rows (Parsing D9 slice 2)</h3>
 * An optional {@code ingester_config.children} block ({@link ChildSpec}) explodes a list of a decoded record into
 * rows of a declared child segment, in addition to the record's own row. A child segment's selectors resolve
 * relative to the child element, plus {@code $index} and {@code $parent.<path>}. More than
 * {@code max_child_rows_per_record} child rows from one record fails the file (QUARANTINED_UNREADABLE).
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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Asn1RecordIngester.class);

    /** The derived column carrying the segment key, appended to every segment. */
    static final String EVENT_TYPE = "EVENT_TYPE";

    @Override
    public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
        Map<String, Object> ic = cfg.schemas().ingesterConfig();
        // `grammar_text` carries the module INLINE; `grammar` is the path-jailed `.asn` file reference
        // (what `frontend: asn1`'s `asn1.grammar_file` becomes). Text wins when both are present. The
        // rule and the jail are Asn1GrammarSource's — shared with the preview, so a grammar that previews
        // is the grammar that ingests. The FILE is read from `ingesterGrammar()`, the ref the parser
        // already resolved beside the config (SCHEMA-FILE-RESOLVES-AGAINST-CWD-1): this ingester never
        // sees the config's directory, so resolving the authored `grammar` here would read it against the
        // working directory. The parser does not jail it; Asn1GrammarSource does, once.
        Asn1GrammarSource.Module module = Asn1GrammarSource.resolve(
                ic.get("grammar_text"), "ingester_config.grammar_text",
                cfg.schemas().ingesterGrammar(), "ingester_config.grammar", null);
        String rootType = str(ic.get("root_type"));
        if (module == null)
            throw new IllegalArgumentException(
                    "ingester_config.grammar_text (inline module) or ingester_config.grammar "
                    + "(path to the ASN.1 module) is required for Asn1RecordIngester");
        if (rootType.isEmpty())
            throw new IllegalArgumentException(
                    "ingester_config.root_type is required for Asn1RecordIngester");

        Asn1Decoder decoder;
        try {
            decoder = Asn1Decoder.compile(module.source(), rootType);
        } catch (Exception badGrammar) {
            throw new IllegalArgumentException(
                    "invalid ASN.1 grammar " + module.label() + ": " + badGrammar.getMessage(), badGrammar);
        }

        DeriveSpec.Stats deriveStats;
        List<ParseError> errors = new ArrayList<>();
        try (ByteSource src = ByteSource.map(file.toPath())) {
            Iterator<NamedNode> records = decoder.decode(src, framing(ic), strictness(str(ic.get("strictness"))),
                    RecoveryPolicy.STOP_FILE, errors::add,
                    Asn1ParserPlugin.maxValueBytes(ic.get("max_value_bytes"), "ingester_config.max_value_bytes"))
                    .iterator();
            deriveStats = emitRecords(records, ic, cfg.schemas().segments(), sink);
        }
        if (deriveStats.nonNumeric > 0 || deriveStats.noMatch > 0)
            LOG.info("{}: derive_non_numeric={} derive_no_match={}", file.getName(),
                    deriveStats.nonNumeric, deriveStats.noMatch);
        if (!errors.isEmpty()) {
            ParseError first = errors.getFirst();
            throw new IOException("ASN.1 decode failed in " + file.getName() + " at record "
                    + first.recordIndex() + " (offset " + first.fileOffset() + "): " + first.message());
        }
    }

    /**
     * The row-building path ingest and the Parse preview share (PARSING-PREVIEW-DERIVED-1): declare every segment,
     * then emit each record's row, its derived columns and its exploded child rows into {@code sink}. A preview
     * passes a collecting sink and a bounded record iterator, so what it shows is what ingest would write.
     */
    static DeriveSpec.Stats emitRecords(Iterator<NamedNode> records, Map<String, Object> ic,
                                        Map<String, Map<String, Object>> segments, RecordSink sink) throws Exception {
        Set<String> segmentKeys = segments == null ? null : segments.keySet();
        DeriveSpec derive = DeriveSpec.parse(ic.get("derive"), segmentKeys,
                ic.get("max_list_elements"), ic.get("max_derived_columns"));
        DeriveSpec.Stats deriveStats = new DeriveSpec.Stats();
        ChildSpec children = ChildSpec.parse(ic.get("children"), segmentKeys, ic.get("max_child_rows_per_record"));
        Set<String> childSegments = children.childSegments();
        Map<String, List<String>> selectorsByKey = declareSegments(segments, sink, derive, childSegments);
        while (records.hasNext()) {
            NamedNode record = records.next();
            List<String> selectors = selectorsByKey.get(record.name());
            if (selectors == null || childSegments.contains(record.name())) {   // not a record segment → junk candidate
                sink.junk();
                continue;
            }
            Map<String, Object> decoded = RecordMapper.toMap(record);
            Object[] derived = derive.evaluate(record.name(), decoded, deriveStats);   // ASN1-DERIVE-LIST-TOO-LARGE fails the file
            Object[] values = new Object[selectors.size() + derived.length + 1];
            for (int i = 0; i < selectors.size(); i++) {
                values[i] = scalar(resolve(decoded, selectors.get(i)));
            }
            System.arraycopy(derived, 0, values, selectors.size(), derived.length);   // derived columns after raw.fields
            values[values.length - 1] = record.name();   // trailing EVENT_TYPE
            sink.emit(record.name(), values);
            explode(record.name(), decoded, children, selectorsByKey, derive, deriveStats, sink);
        }
        return deriveStats;
    }

    /** Emit one row per element of each list the parent's {@code children} name; over the cap fails the file. */
    @SuppressWarnings("unchecked")
    private static void explode(String parent, Map<String, Object> decoded, ChildSpec children,
                                Map<String, List<String>> selectorsByKey, DeriveSpec derive,
                                DeriveSpec.Stats stats, RecordSink sink) throws Exception {
        long rows = 0;
        for (ChildSpec.Child c : children.of(parent)) {
            if (!(resolve(decoded, String.join(".", c.list())) instanceof List<?> list)) continue;
            List<String> selectors = selectorsByKey.get(c.segment());
            for (int i = 0; i < list.size(); i++) {
                if (++rows > children.maxChildRows())
                    throw new IOException("ASN1-CHILD-TOO-MANY-ROWS: a '" + parent + "' record explodes into more than "
                            + children.maxChildRows() + " child rows (max_child_rows_per_record)");
                Map<String, Object> element = list.get(i) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                Object[] derived = derive.evaluate(c.segment(), element, stats);
                Object[] values = new Object[selectors.size() + derived.length + 1];
                for (int s = 0; s < selectors.size(); s++) {
                    String sel = selectors.get(s);
                    values[s] = sel.equals("$index") ? String.valueOf(i)
                            : sel.startsWith("$parent.") ? scalar(resolve(decoded, sel.substring("$parent.".length())))
                            : scalar(resolve(element, sel));
                }
                System.arraycopy(derived, 0, values, selectors.size(), derived.length);
                values[values.length - 1] = c.segment();
                sink.emit(c.segment(), values);
            }
        }
    }

    /**
     * Declare every segment's columns up front (raw.fields + the derived {@code EVENT_TYPE}) and
     * return each key's selector list, so the per-record loop never re-walks the config map.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> declareSegments(Map<String, Map<String, Object>> segments, RecordSink sink,
                                                             DeriveSpec derive, Set<String> childSegments) throws Exception {
        if (segments == null || segments.isEmpty())
            throw new IllegalArgumentException("Asn1RecordIngester requires at least one segment");
        Map<String, List<String>> selectorsByKey = new LinkedHashMap<>();
        for (String key : segments.keySet()) {
            Object raw = segments.get(key).get("raw");
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
                if (childSegments.contains(key) && !childSelector(selector))
                    throw new IllegalArgumentException("ASN1-CHILD-BAD-SELECTOR: child segment '" + key + "' field '"
                            + name + "' uses a reserved '$' selector other than $index or $parent.<path>");
                columns.add(name);
                selectors.add(selector);
            }
            derive.checkClash(key, new ArrayList<>(columns));
            columns.addAll(derive.columns(key));   // derived columns: after raw.fields, before EVENT_TYPE
            columns.add(EVENT_TYPE);
            sink.define(key, columns);
            selectorsByKey.put(key, selectors);
        }
        return selectorsByKey;
    }

    /** A child segment's selector: a plain path, {@code $index}, or {@code $parent.<dotted identifiers>}. */
    private static boolean childSelector(String s) {
        if (!s.startsWith("$")) return true;
        return s.equals("$index") || s.matches("[$]parent([.][A-Za-z_][A-Za-z0-9_-]*)+");
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
