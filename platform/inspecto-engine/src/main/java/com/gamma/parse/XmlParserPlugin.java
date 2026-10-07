package com.gamma.parse;

import com.gamma.config.spec.FieldSpec;
import com.gamma.config.spec.FieldType;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.gamma.util.Values.trimOrEmpty;

/**
 * XML as a {@link ParserPlugin} — the reference custom-Java parser, registered via
 * {@code META-INF/services} (the ServiceLoader path a vendor plugin would use). StAX from the JDK
 * (no new dependency), hardened against XXE like the cloud connectors' XML handling: DTDs and
 * external entities are disabled outright.
 *
 * <p>Hierarchical: the preview is a record {@link ParseResult.Tree} — one node forest per record
 * element (attributes as {@code @name} leaves, text content as leaf values). Since the tree→segments
 * bridge shipped it is also <b>ingestable</b>: {@link #ingesterClass()} names
 * {@code com.gamma.ingester.XmlRecordIngester}, which maps that same tree onto segment schemas by
 * dotted selector. Both halves walk the document through {@link XmlRecordReader}, so a selector
 * authored against the preview resolves identically at load.
 *
 * <p>The grammar keys live under {@code ingester_config} — the block the pipeline persists for the
 * ingester — so preview and load are configured by one set of keys, not two spellings of the same
 * option. {@code max_records} is the exception: it bounds the preview only and the ingester ignores it.
 */
public final class XmlParserPlugin implements ParserPlugin {

    /** Hard bound on records materialized into the preview forest. */
    static final int MAX_RECORDS = 1000;
    /** Default when the grammar does not set {@code max_records}. */
    static final int DEFAULT_RECORDS = 50;
    /** Hard bound on total preview nodes across all records (a runaway-document guard). */
    static final int MAX_NODES = 20_000;

    @Override
    public String id() {
        return "xml";
    }

    @Override
    public String label() {
        return "XML — XML file format";
    }

    @Override
    public boolean hierarchical() {
        return true;
    }

    /** The tree→segments bridge — what makes this parser load to Tables, not just preview. */
    @Override
    public Optional<String> ingesterClass() {
        return Optional.of("com.gamma.ingester.XmlRecordIngester");
    }

    @Override
    public List<FieldSpec> grammarSchema() {
        return List.of(
                FieldSpec.of("ingester_config.record_element", "Record element", FieldType.STRING,
                        "Element that starts one record — a local name (order) or a slash path "
                                + "(orders/order). Blank = every direct child of the root."),
                FieldSpec.withDefault("ingester_config.namespace_aware", "Namespace aware", FieldType.BOOL, false,
                        "Resolve namespaces (element labels then use local names)."),
                FieldSpec.of("ingester_config.encoding", "Encoding", FieldType.STRING,
                        "Overrides the document prolog's encoding (default: auto-detect)."),
                FieldSpec.withDefault("ingester_config.max_records", "Preview records", FieldType.INT, DEFAULT_RECORDS,
                        "Records materialized into the preview tree (max " + MAX_RECORDS + ")."));
    }

    @Override
    public ParseResult preview(byte[] sample, Map<String, Object> grammar) throws Exception {
        if (sample == null || sample.length == 0)
            throw new IllegalArgumentException("sample content is required");
        Map<String, Object> opts = sub(grammar, "ingester_config");
        int maxRecords = clampRecords(opts.get("max_records"));

        List<ParseResult.Node> records = new ArrayList<>();
        int[] nodeBudget = {MAX_NODES};
        long matched;
        try {
            matched = XmlRecordReader.read(new ByteArrayInputStream(sample), opts, nodeBudget,
                    new XmlRecordReader.Handler() {
                        @Override public boolean wants() { return records.size() < maxRecords; }
                        @Override public void record(ParseResult.Node r) { records.add(r); }
                    });
        } catch (XMLStreamException notXml) {
            throw new IllegalArgumentException("sample is not well-formed XML: " + notXml.getMessage());
        }
        if (matched == 0) {
            String recordPath = trimOrEmpty(opts.get("record_element"));
            throw new IllegalArgumentException(recordPath.isEmpty()
                    ? "no record elements found under the document root"
                    : "no elements match record_element '" + recordPath + "'");
        }
        return new ParseResult.Tree(matched, records);
    }

    /** Clue: the first repeated child element under the root is very likely the record element. */
    @Override
    public Map<String, Object> suggest(byte[] sample) {
        try {
            XMLStreamReader r = XmlRecordReader.reader(new ByteArrayInputStream(sample), Map.of());
            try {
                int depth = 0;
                Map<String, Integer> depth2Counts = new LinkedHashMap<>();
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        if (depth == 2) depth2Counts.merge(XmlRecordReader.localName(r), 1, Integer::sum);
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                    }
                }
                for (Map.Entry<String, Integer> e : depth2Counts.entrySet()) {
                    if (e.getValue() > 1) return Map.of("ingester_config", Map.of("record_element", e.getKey()));
                }
            } finally {
                r.close();
            }
        } catch (Exception noClue) {
            // A clue is best-effort — an unparseable sample simply yields none.
        }
        return Map.of();
    }

    private static int clampRecords(Object v) {
        if (v == null) return DEFAULT_RECORDS;
        try {
            int n = Integer.parseInt(String.valueOf(v).trim());
            return Math.max(1, Math.min(n, MAX_RECORDS));
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("max_records must be a number, got: " + v);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> grammar, String key) {
        Object v = grammar == null ? null : grammar.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
