package com.gamma.telecom.asn1;

import com.gamma.asn.core.ByteSource;
import com.gamma.asn.core.Framing;
import com.gamma.asn.core.ParseError;
import com.gamma.asn.core.RecordReader;
import com.gamma.asn.core.RecoveryPolicy;
import com.gamma.asn.core.Strictness;
import com.gamma.asn.core.Tlv;
import com.gamma.asn.facade.Asn1Decoder;
import com.gamma.asn.facade.RecordMapper;
import com.gamma.asn.schema.CompiledType;
import com.gamma.asn.schema.DecoderRegistry;
import com.gamma.asn.schema.NamedNode;
import com.gamma.asn.schema.SchemaBinder;
import com.gamma.asn.schema.ast.BuiltinKind;
import com.gamma.etl.ChildSpec;
import com.gamma.etl.DecodeProfile;
import com.gamma.etl.DeriveSpec;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RecordSink;
import com.gamma.etl.StreamingFileIngester;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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

        boolean simplifyAliases = Boolean.parseBoolean(String.valueOf(ic.getOrDefault("simplify_aliases", false)));
        Asn1Decoder decoder;
        try {
            decoder = Asn1ParserPlugin.compileDecoder(module.source(), rootType, simplifyAliases);
        } catch (Exception badGrammar) {
            throw new IllegalArgumentException(
                    "invalid ASN.1 grammar " + module.label() + ": " + badGrammar.getMessage(), badGrammar);
        }

        DeriveSpec.Stats deriveStats;
        List<ParseError> errors = new ArrayList<>();
        Map<String, String> fieldDecoders = DecodeProfile.fieldDecoders(ic);
        try (ByteSource src = ByteSource.map(file.toPath())) {
            Iterator<NamedNode> records = decodeWithOverrides(decoder, src, framing(ic),
                    strictness(str(ic.get("strictness"))), RecoveryPolicy.STOP_FILE, errors::add,
                    Asn1ParserPlugin.maxValueBytes(ic.get("max_value_bytes"), "ingester_config.max_value_bytes"),
                    fieldDecoders)
                    .iterator();
            deriveStats = emitRecords(records, ic, cfg.schemas().segments(), sink, !fieldDecoders.isEmpty());
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
     * Decode records lazily and apply {@code field_decoders} overrides directly to raw leaf {@code V} bytes
     * from the underlying {@link Tlv} / {@link ByteSource}.
     */
    static Stream<NamedNode> decodeWithOverrides(Asn1Decoder decoder, ByteSource src, Framing framing,
                                                 Strictness strictness, RecoveryPolicy policy,
                                                 RecordReader.ErrorListener errors, int maxValueBytes,
                                                 Map<String, String> fieldDecoders) {
        if (fieldDecoders == null || fieldDecoders.isEmpty()) {
            return decoder.decode(src, framing, strictness, policy, errors, maxValueBytes);
        }
        RecordReader reader = new RecordReader(src, framing, strictness, policy, errors, maxValueBytes);
        SchemaBinder binder = new SchemaBinder(decoder.schema(), src, decoder.registry());
        Iterator<NamedNode> it = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return reader.hasNext();
            }

            @Override
            public NamedNode next() {
                if (!hasNext()) throw new NoSuchElementException();
                Tlv tlv = reader.next();
                NamedNode bound = binder.bind(tlv);
                return applyLeafOverrides(bound, tlv, src, fieldDecoders, decoder.registry());
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false);
    }

    /**
     * The row-building path ingest and the Parse preview share (PARSING-PREVIEW-DERIVED-1): declare every segment,
     * then emit each record's row, its derived columns and its exploded child rows into {@code sink}. A preview
     * passes a collecting sink and a bounded record iterator, so what it shows is what ingest would write.
     */
    static DeriveSpec.Stats emitRecords(Iterator<NamedNode> records, Map<String, Object> ic,
                                        Map<String, Map<String, Object>> segments, RecordSink sink) throws Exception {
        return emitRecords(records, ic, segments, sink, false);
    }

    static DeriveSpec.Stats emitRecords(Iterator<NamedNode> records, Map<String, Object> ic,
                                        Map<String, Map<String, Object>> segments, RecordSink sink,
                                        boolean decodersApplied) throws Exception {
        Set<String> segmentKeys = segments == null ? null : segments.keySet();
        DeriveSpec derive = DeriveSpec.parse(ic.get("derive"), segmentKeys,
                ic.get("max_list_elements"), ic.get("max_derived_columns"));
        DeriveSpec.Stats deriveStats = new DeriveSpec.Stats();
        ChildSpec children = ChildSpec.parse(ic.get("children"), segmentKeys, ic.get("max_child_rows_per_record"));
        Set<String> childSegments = children.childSegments();
        Map<String, String> eventRoots = DecodeProfile.eventRoots(ic);
        List<String> headerFields = DecodeProfile.headerFields(ic);
        Map<String, String> fieldDecoders = DecodeProfile.fieldDecoders(ic);
        Map<String, List<String>> selectorsByKey = declareSegments(segments, sink, derive, childSegments);
        Map<String, Object> headerContext = new LinkedHashMap<>();
        DecoderRegistry defaultRegistry = !decodersApplied && !fieldDecoders.isEmpty()
                ? DecoderRegistry.withDefaults() : null;

        while (records.hasNext()) {
            NamedNode record = records.next();
            if (defaultRegistry != null) {
                record = applyLeafOverrides(record, null, null, fieldDecoders, defaultRegistry);
            }
            Map<String, Object> recordMap = headerFields.isEmpty() ? null : RecordMapper.toMap(record);
            boolean headerCaptured = !headerFields.isEmpty()
                    && captureHeader(record.name(), recordMap, headerFields, headerContext);

            if (eventRoots.isEmpty()) {
                List<String> selectors = selectorsByKey.get(record.name());
                if (selectors == null || childSegments.contains(record.name())) {   // not a record segment → junk candidate
                    if (!headerCaptured) sink.junk();
                    continue;
                }
                Map<String, Object> decoded = recordMap != null ? recordMap : RecordMapper.toMap(record);
                emitEventRow(record.name(), decoded, selectors, derive, deriveStats, children,
                        selectorsByKey, headerContext, sink);
            } else {
                List<RoutedEvent> matched = new ArrayList<>();
                collectEventRoots(record, null, null, eventRoots, selectorsByKey, childSegments, matched);
                if (matched.isEmpty()) {
                    if (!headerCaptured) sink.junk();
                    continue;
                }
                for (RoutedEvent ev : matched) {
                    Map<String, Object> decoded = ev.node() == record && recordMap != null
                            ? recordMap : RecordMapper.toMap(ev.node());
                    emitEventRow(ev.sinkKey(), decoded, selectorsByKey.get(ev.sinkKey()), derive, deriveStats,
                            children, selectorsByKey, headerContext, sink);
                }
            }
        }
        return deriveStats;
    }

    private record RoutedEvent(String sinkKey, NamedNode node) {
    }

    private static void emitEventRow(String sinkKey, Map<String, Object> decoded, List<String> selectors,
                                     DeriveSpec derive, DeriveSpec.Stats deriveStats, ChildSpec children,
                                     Map<String, List<String>> selectorsByKey, Map<String, Object> headerContext,
                                     RecordSink sink) throws Exception {
        Object[] derived = derive.evaluate(sinkKey, decoded, deriveStats);   // ASN1-DERIVE-LIST-TOO-LARGE fails the file
        Object[] values = new Object[selectors.size() + derived.length + 1];
        for (int i = 0; i < selectors.size(); i++) {
            String sel = selectors.get(i);
            values[i] = sel.startsWith("$header.")
                    ? scalar(headerContext.get(sel.substring("$header.".length())))
                    : scalar(resolve(decoded, sel));
        }
        System.arraycopy(derived, 0, values, selectors.size(), derived.length);   // derived columns after raw.fields
        values[values.length - 1] = sinkKey;   // trailing EVENT_TYPE
        sink.emit(sinkKey, values);
        explode(sinkKey, decoded, children, selectorsByKey, derive, deriveStats, headerContext, sink);
    }

    private static void collectEventRoots(NamedNode node, String fullPath, String relPath,
                                          Map<String, String> eventRoots,
                                          Map<String, List<String>> selectorsByKey,
                                          Set<String> childSegments, List<RoutedEvent> out) {
        String curFull = fullPath == null ? node.name() : fullPath + "." + node.name();
        String curRel = fullPath == null ? "" : (relPath.isEmpty() ? node.name() : relPath + "." + node.name());
        String sinkKey = eventRoots.get(curFull);
        if (sinkKey == null && !curRel.isEmpty()) sinkKey = eventRoots.get(curRel);
        if (sinkKey == null) sinkKey = eventRoots.get(node.name());
        if (sinkKey != null) {
            if (selectorsByKey.containsKey(sinkKey) && !childSegments.contains(sinkKey)) {
                out.add(new RoutedEvent(sinkKey, node));
            }
            return;
        }
        for (NamedNode child : node.children()) {
            collectEventRoots(child, curFull, curRel, eventRoots, selectorsByKey, childSegments, out);
        }
    }

    private static boolean captureHeader(String recordName, Map<String, Object> recordMap,
                                         List<String> headerFields, Map<String, Object> headerContext) {
        boolean captured = false;
        for (String hf : headerFields) {
            Object val = resolve(recordMap, hf);
            if (val == null && hf.startsWith(recordName + ".")) {
                val = resolve(recordMap, hf.substring(recordName.length() + 1));
            }
            Object s = scalar(val);
            if (s != null && !String.valueOf(s).isEmpty()) {
                headerContext.put(hf, s);
                captured = true;
            }
        }
        return captured;
    }

    /** Emit one row per element (or grouped/folded sub-record) of each list the parent's {@code children} name; over the cap fails the file. */
    @SuppressWarnings("unchecked")
    private static void explode(String parent, Map<String, Object> decoded, ChildSpec children,
                                Map<String, List<String>> selectorsByKey, DeriveSpec derive,
                                DeriveSpec.Stats stats, Map<String, Object> headerContext,
                                RecordSink sink) throws Exception {
        long rows = 0;
        for (ChildSpec.Child c : children.of(parent)) {
            Object rawList = resolve(decoded, String.join(".", c.list()));
            List<?> list = unwrapList(rawList);
            List<String> selectors = selectorsByKey.get(c.segment());

            if (c.foldMode() != null) {
                if (++rows > children.maxChildRows())
                    throw new IOException("ASN1-CHILD-TOO-MANY-ROWS: a '" + parent + "' record explodes into more than "
                            + children.maxChildRows() + " child rows (max_child_rows_per_record)");
                List<Map<String, Object>> shaped = shapeChildList(list, c, stats);
                String folded = DeriveSpec.formatFold(shaped, c.foldMode(), c.itemDelim(), c.fieldDelim(), c.fields());
                String colKey = c.targetColumn() != null && !c.targetColumn().isEmpty() ? c.targetColumn() : "FOLD";
                Map<String, Object> element = Map.of(colKey, folded);
                emitChildElementRow(c.segment(), decoded, element, 0, selectors, derive, stats, headerContext, sink);
                continue;
            }

            if (list.isEmpty()) {
                if ("inner".equals(c.joinMode())) continue;
                if (c.explicitJoinMode() || !c.groupBy().isEmpty() || !c.aggs().isEmpty()) {
                    if (++rows > children.maxChildRows())
                        throw new IOException("ASN1-CHILD-TOO-MANY-ROWS: a '" + parent + "' record explodes into more than "
                                + children.maxChildRows() + " child rows (max_child_rows_per_record)");
                    emitChildElementRow(c.segment(), decoded, Map.of(), null, selectors, derive, stats, headerContext, sink);
                }
                continue;
            }

            List<Map<String, Object>> items = shapeChildList(list, c, stats);
            for (int i = 0; i < items.size(); i++) {
                if (++rows > children.maxChildRows())
                    throw new IOException("ASN1-CHILD-TOO-MANY-ROWS: a '" + parent + "' record explodes into more than "
                            + children.maxChildRows() + " child rows (max_child_rows_per_record)");
                emitChildElementRow(c.segment(), decoded, items.get(i), i, selectors, derive, stats, headerContext, sink);
            }
        }
    }

    private static List<?> unwrapList(Object raw) {
        if (raw instanceof List<?> l) return l;
        if (raw instanceof Map<?, ?> m && m.size() == 1) {
            Object sole = m.values().iterator().next();
            if (sole instanceof List<?> l) return l;
            if (sole instanceof Map<?, ?> elem) return List.of(elem);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shapeChildList(List<?> list, ChildSpec.Child c, DeriveSpec.Stats stats) {
        if (list == null || list.isEmpty()) return List.of();
        List<Map<String, Object>> items;
        if (!c.groupBy().isEmpty() || !c.aggs().isEmpty()) {
            items = DeriveSpec.groupAndAggregate(list, c.groupBy(), c.aggs(), stats);
        } else {
            items = new ArrayList<>(list.size());
            for (Object e : list) {
                items.add(e instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
            }
        }
        boolean hasAffix = (c.prefix() != null && !c.prefix().isEmpty())
                || (c.postfix() != null && !c.postfix().isEmpty())
                || (c.rename() != null && !c.rename().isEmpty());
        if (!hasAffix) return items;
        List<Map<String, Object>> renamed = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : item.entrySet()) {
                String k = DeriveSpec.applyNameAffixes(
                        e.getKey(),
                        c.rename().get(e.getKey()),
                        c.prefix(),
                        c.postfix());
                out.put(k, e.getValue());
            }
            renamed.add(out);
        }
        return renamed;
    }

    private static void emitChildElementRow(String segment, Map<String, Object> decoded,
                                            Map<String, Object> element, Integer index,
                                            List<String> selectors, DeriveSpec derive,
                                            DeriveSpec.Stats stats, Map<String, Object> headerContext,
                                            RecordSink sink) throws Exception {
        Object[] derived = derive.evaluate(segment, element, stats);
        Object[] values = new Object[selectors.size() + derived.length + 1];
        for (int s = 0; s < selectors.size(); s++) {
            String sel = selectors.get(s);
            values[s] = sel.equals("$index") ? (index == null ? null : String.valueOf(index))
                    : sel.startsWith("$parent.") ? scalar(resolve(decoded, sel.substring("$parent.".length())))
                    : sel.startsWith("$header.") ? scalar(headerContext.get(sel.substring("$header.".length())))
                    : scalar(resolve(element, sel));
        }
        System.arraycopy(derived, 0, values, selectors.size(), derived.length);
        values[values.length - 1] = segment;
        sink.emit(segment, values);
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
            Map<String, String> sqlTypes = new LinkedHashMap<>();
            for (Object o : fl) {
                Map<String, Object> f = (Map<String, Object>) o;
                String name = str(f.get("name"));
                String selector = str(f.get("selector"));
                if (selector.isEmpty()) selector = name;   // a field whose path IS its name
                if (childSegments.contains(key) && !childSelector(selector))
                    throw new IllegalArgumentException("ASN1-CHILD-BAD-SELECTOR: child segment '" + key + "' field '"
                            + name + "' uses a reserved '$' selector other than $index or $parent.<path>");
                String effectiveName = DeriveSpec.applyNameAffixes(
                        name, f.get("rename"), f.get("prefix"), f.get("postfix"));
                columns.add(effectiveName);
                selectors.add(selector);
                String rawType = str(f.get("sql_type"));
                if (rawType.isEmpty()) rawType = str(f.get("type"));
                sqlTypes.put(effectiveName, rawType.isEmpty()
                        ? com.gamma.etl.SchemaFieldTypes.VARCHAR
                        : com.gamma.etl.SchemaFieldTypes.normalize(rawType));
            }
            derive.checkClash(key, new ArrayList<>(columns));
            List<String> derivedCols = derive.columns(key);
            columns.addAll(derivedCols);   // derived columns: after raw.fields, before EVENT_TYPE
            for (String dc : derivedCols) {
                sqlTypes.putIfAbsent(dc, com.gamma.etl.SchemaFieldTypes.VARCHAR);
            }
            columns.add(EVENT_TYPE);
            sqlTypes.put(EVENT_TYPE, com.gamma.etl.SchemaFieldTypes.VARCHAR);
            sink.defineTyped(key, columns, sqlTypes);
            selectorsByKey.put(key, selectors);
        }
        return selectorsByKey;
    }

    /** A child segment's selector: a plain path, {@code $index}, {@code $parent.<path>}, or {@code $header.<path>}. */
    private static boolean childSelector(String s) {
        if (!s.startsWith("$")) return true;
        return s.equals("$index")
                || s.matches("[$]parent([.][A-Za-z_][A-Za-z0-9_-]*)+")
                || s.matches("[$]header([.][A-Za-z_][A-Za-z0-9_-]*)+");
    }

    /** Apply {@code field_decoders} overrides strictly to leaf/value nodes, never container or repeated nodes. */
    static NamedNode applyLeafOverrides(NamedNode root, Tlv recordTlv, ByteSource src,
                                        Map<String, String> fieldDecoders, DecoderRegistry registry) {
        if (fieldDecoders == null || fieldDecoders.isEmpty()) return root;
        Map<String, Deque<Tlv>> leafTlvs = null;
        if (recordTlv != null && src != null) {
            leafTlvs = new LinkedHashMap<>();
            indexLeafTlvs(recordTlv, null, leafTlvs);
        }
        return rewriteLeaves(root, null, leafTlvs, src, fieldDecoders, registry);
    }

    private static void indexLeafTlvs(Tlv t, String parentPath, Map<String, Deque<Tlv>> out) {
        String path = parentPath == null ? String.valueOf(t.tagNumber()) : parentPath + "." + t.tagNumber();
        if (!t.constructed()) {
            out.computeIfAbsent(path, k -> new ArrayDeque<>()).addLast(t);
        } else {
            for (Tlv child : t.children()) {
                indexLeafTlvs(child, path, out);
            }
        }
    }

    private static NamedNode rewriteLeaves(NamedNode node, String parentFieldPath,
                                           Map<String, Deque<Tlv>> leafTlvs, ByteSource src,
                                           Map<String, String> fieldDecoders, DecoderRegistry registry) {
        String fieldPath = parentFieldPath == null ? node.name() : parentFieldPath + "." + node.name();
        if (isLeafNode(node)) {
            Tlv leafTlv = pollLeafTlv(leafTlvs, node.path());
            String decoderId = resolveFieldDecoderId(fieldPath, fieldDecoders);
            if (decoderId == null) return node;
            byte[] bytes = leafTlv != null && src != null ? leafTlv.value(src) : rawBytesFromLeafValue(node);
            String decoded = decodeWithId(registry, decoderId, bytes);
            return new NamedNode(node.name(), node.path(), node.typeName(), decoded, node.children(), node.type());
        }
        if (node.children().isEmpty()) return node;
        List<NamedNode> updated = null;
        for (int i = 0; i < node.children().size(); i++) {
            NamedNode orig = node.children().get(i);
            NamedNode rew = rewriteLeaves(orig, fieldPath, leafTlvs, src, fieldDecoders, registry);
            if (rew != orig) {
                if (updated == null) updated = new ArrayList<>(node.children());
                updated.set(i, rew);
            }
        }
        if (updated == null) return node;
        return new NamedNode(node.name(), node.path(), node.typeName(), node.value(), updated, node.type());
    }

    static boolean isLeafNode(NamedNode n) {
        boolean repeated = n.type() != null
                && (n.type().kind() == CompiledType.Kind.SEQUENCE_OF
                || n.type().kind() == CompiledType.Kind.SET_OF);
        return !repeated && n.children().isEmpty() && n.value() != null;
    }

    static String resolveFieldDecoderId(String fieldPath, Map<String, String> fieldDecoders) {
        if (fieldDecoders == null || fieldDecoders.isEmpty() || fieldPath == null) return null;
        String id = fieldDecoders.get(fieldPath);
        if (id != null) return id;
        int idx = fieldPath.indexOf('.');
        while (idx >= 0 && idx + 1 < fieldPath.length()) {
            String suffix = fieldPath.substring(idx + 1);
            id = fieldDecoders.get(suffix);
            if (id != null) return id;
            idx = fieldPath.indexOf('.', idx + 1);
        }
        return null;
    }

    static String decodeWithId(DecoderRegistry registry, String decoderId, byte[] bytes) {
        String lookup = switch (decoderId) {
            case "bcd" -> "BCDSTRING";
            case "pdpType" -> "TOPDPTYPE";
            case "imei" -> "TOIMEI";
            case "asciiPrintable" -> "ASCIISTRING";
            default -> decoderId;
        };
        return registry.decode(List.of(lookup), null, bytes);
    }

    private static Tlv pollLeafTlv(Map<String, Deque<Tlv>> leafTlvs, String path) {
        if (leafTlvs == null || path == null) return null;
        Deque<Tlv> q = leafTlvs.get(path);
        return q == null ? null : q.pollFirst();
    }

    private static byte[] rawBytesFromLeafValue(NamedNode node) {
        String val = node.value();
        if (val == null) return new byte[0];
        if (node.type() == null) {
            try {
                return HexFormat.of().parseHex(val);
            } catch (IllegalArgumentException ignored) {
                return val.getBytes(StandardCharsets.ISO_8859_1);
            }
        }
        BuiltinKind kind = node.type().primitive();
        if (kind == BuiltinKind.UTF8_STRING) {
            return val.getBytes(StandardCharsets.UTF_8);
        }
        return val.getBytes(StandardCharsets.ISO_8859_1);
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
        if (value instanceof BigDecimal bd) return bd.toPlainString();
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
