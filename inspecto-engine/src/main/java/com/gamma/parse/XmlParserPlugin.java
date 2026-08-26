package com.gamma.parse;

import com.gamma.config.spec.FieldSpec;
import com.gamma.config.spec.FieldType;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.gamma.util.Values.trimOrEmpty;

/**
 * XML as a {@link ParserPlugin} — the reference custom-Java parser, registered via
 * {@code META-INF/services} (the ServiceLoader path a vendor plugin would use). StAX from the JDK
 * (no new dependency), hardened against XXE like the cloud connectors' XML handling: DTDs and
 * external entities are disabled outright.
 *
 * <p>Hierarchical: the preview is a record {@link ParseResult.Tree} — one node forest per record
 * element (attributes as {@code @name} leaves, text content as leaf values). {@code ingesterClass}
 * is deliberately empty: tree-shaped records cannot honestly load to Tables until the flatten
 * configuration maps them onto segment schemas, so this parser is <b>preview-only</b> and the
 * catalog reports {@code ingestable: false}.
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

    @Override
    public List<FieldSpec> grammarSchema() {
        return List.of(
                FieldSpec.of("xml.record_element", "Record element", FieldType.STRING,
                        "Element that starts one record — a local name (order) or a slash path "
                                + "(orders/order). Blank = every direct child of the root."),
                FieldSpec.withDefault("xml.namespace_aware", "Namespace aware", FieldType.BOOL, false,
                        "Resolve namespaces (element labels then use local names)."),
                FieldSpec.of("xml.encoding", "Encoding", FieldType.STRING,
                        "Overrides the document prolog's encoding (default: auto-detect)."),
                FieldSpec.withDefault("xml.max_records", "Preview records", FieldType.INT, DEFAULT_RECORDS,
                        "Records materialized into the preview tree (max " + MAX_RECORDS + ")."));
    }

    @Override
    public ParseResult preview(byte[] sample, Map<String, Object> grammar) throws Exception {
        if (sample == null || sample.length == 0)
            throw new IllegalArgumentException("sample content is required");
        Map<String, Object> xml = sub(grammar, "xml");
        String recordPath = trimOrEmpty(xml.get("record_element"));
        int maxRecords = clampRecords(xml.get("max_records"));

        List<ParseResult.Node> records = new ArrayList<>();
        long matched = 0;
        int[] nodeBudget = {MAX_NODES};

        XMLStreamReader r = reader(sample, xml);
        try {
            Deque<String> path = new ArrayDeque<>();
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    path.addLast(localName(r));
                    if (isRecordStart(path, recordPath)) {
                        matched++;
                        if (records.size() < maxRecords && nodeBudget[0] > 0) {
                            records.add(element(r, nodeBudget));
                        } else {
                            skipElement(r);
                        }
                        path.removeLast(); // element()/skipElement() consumed the record's END_ELEMENT
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    // A non-record element closing — keep the ancestry stack honest.
                    if (!path.isEmpty()) path.removeLast();
                }
            }
        } catch (XMLStreamException notXml) {
            throw new IllegalArgumentException("sample is not well-formed XML: " + notXml.getMessage());
        } finally {
            r.close();
        }
        if (matched == 0) {
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
            XMLStreamReader r = reader(sample, Map.of());
            try {
                int depth = 0;
                Map<String, Integer> depth2Counts = new LinkedHashMap<>();
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        if (depth == 2) depth2Counts.merge(localName(r), 1, Integer::sum);
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                    }
                }
                for (Map.Entry<String, Integer> e : depth2Counts.entrySet()) {
                    if (e.getValue() > 1) return Map.of("xml", Map.of("record_element", e.getKey()));
                }
            } finally {
                r.close();
            }
        } catch (Exception noClue) {
            // A clue is best-effort — an unparseable sample simply yields none.
        }
        return Map.of();
    }

    // ── StAX plumbing ────────────────────────────────────────────────────────────────

    /** An XXE-hardened reader: DTD support and external entities OFF (S3Connector precedent). */
    private static XMLStreamReader reader(byte[] sample, Map<String, Object> xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, bool(xml.get("namespace_aware")));
        String enc = trimOrEmpty(xml.get("encoding"));
        ByteArrayInputStream in = new ByteArrayInputStream(sample);
        return enc.isEmpty() ? f.createXMLStreamReader(in) : f.createXMLStreamReader(in, enc);
    }

    /**
     * Materialize the element the reader is positioned on (START_ELEMENT) into a preview node,
     * consuming through its matching END_ELEMENT. Attributes become {@code @name} leaves, child
     * elements recurse, significant text becomes the leaf value (or a {@code #text} leaf when the
     * element also has children).
     */
    private static ParseResult.Node element(XMLStreamReader r, int[] nodeBudget) throws XMLStreamException {
        String name = localName(r);
        List<ParseResult.Node> children = new ArrayList<>();
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (--nodeBudget[0] <= 0) break;
            children.add(ParseResult.Node.leaf("@" + r.getAttributeLocalName(i), "attr", r.getAttributeValue(i)));
        }
        StringBuilder text = new StringBuilder();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if (nodeBudget[0] <= 0) {
                    skipElement(r);
                } else {
                    nodeBudget[0]--;
                    children.add(element(r, nodeBudget));
                }
            } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                if (!r.isWhiteSpace()) text.append(r.getText().trim());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }
        String value = text.isEmpty() ? null : text.toString();
        if (children.isEmpty()) return ParseResult.Node.leaf(name, "element", value);
        if (value != null) children.add(ParseResult.Node.leaf("#text", "text", value));
        return ParseResult.Node.container(name, "element", children);
    }

    /** Consume from the current START_ELEMENT through its matching END_ELEMENT, building nothing. */
    private static void skipElement(XMLStreamReader r) throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) depth++;
            else if (ev == XMLStreamConstants.END_ELEMENT) depth--;
        }
    }

    /**
     * Whether the element whose path is {@code path} starts a record: with no configured
     * {@code record_element}, every depth-2 element (a direct child of the root); with a local
     * name, any element of that name; with a slash path, any element whose ancestry ends with it.
     */
    private static boolean isRecordStart(Deque<String> path, String recordPath) {
        if (recordPath.isEmpty()) return path.size() == 2;
        List<String> want = List.of(recordPath.split("/"));
        if (want.size() > path.size()) return false;
        List<String> have = new ArrayList<>(path);
        return have.subList(have.size() - want.size(), have.size()).equals(want);
    }

    private static String localName(XMLStreamReader r) {
        return r.getName().getLocalPart();
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

    private static boolean bool(Object v) {
        return v != null && Boolean.parseBoolean(String.valueOf(v).trim());
    }
}
