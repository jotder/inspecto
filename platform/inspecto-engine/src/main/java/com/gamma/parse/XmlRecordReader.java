package com.gamma.parse;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static com.gamma.util.Values.trimOrEmpty;

/**
 * The one StAX walker that turns an XML document into record trees — shared by
 * {@link XmlParserPlugin#preview} and {@code com.gamma.ingester.XmlRecordIngester}.
 *
 * <p>It is deliberately ONE implementation, not two: an operator authors a segment's
 * {@code raw.fields[].selector} against the labels they saw in the preview tree, so a second walker
 * that labelled nodes even slightly differently would make those selectors resolve to {@code NULL}
 * at ingest time while the preview kept looking right.
 *
 * <p>Options ({@code record_element}, {@code namespace_aware}, {@code encoding}) come from one flat
 * map — the parser's {@code ingester_config} grammar block, which is also what is persisted for
 * ingest, so preview and load are configured by the same keys.
 */
public final class XmlRecordReader {

    private XmlRecordReader() {}

    /** What to do with each record the reader finds. */
    public interface Handler {
        /**
         * Asked before a record is materialized. {@code false} skips the element's subtree entirely
         * (it is still counted), which is how the preview stops building once its bounds are hit.
         */
        boolean wants();

        /** One materialized record tree, rooted at the record element. */
        void record(ParseResult.Node record) throws Exception;
    }

    /**
     * Walk {@code in}, handing every record element to {@code handler}.
     *
     * @param nodeBudget a one-element mutable countdown of nodes the walker may materialize across
     *                   all records (a runaway-document guard); pass
     *                   {@code new int[]{Integer.MAX_VALUE}} for no practical bound
     * @return how many record elements matched, including any the handler declined
     */
    public static long read(InputStream in, Map<String, Object> opts, int[] nodeBudget, Handler handler)
            throws Exception {
        String recordPath = trimOrEmpty(opts.get("record_element"));
        long matched = 0;
        XMLStreamReader r = reader(in, opts);
        try {
            Deque<String> path = new ArrayDeque<>();
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    path.addLast(localName(r));
                    if (isRecordStart(path, recordPath)) {
                        matched++;
                        if (handler.wants() && nodeBudget[0] > 0) {
                            handler.record(element(r, nodeBudget));
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
        } finally {
            r.close();
        }
        return matched;
    }

    /** An XXE-hardened reader: DTD support and external entities OFF (S3Connector precedent). */
    static XMLStreamReader reader(InputStream in, Map<String, Object> opts) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, bool(opts.get("namespace_aware")));
        String enc = trimOrEmpty(opts.get("encoding"));
        return enc.isEmpty() ? f.createXMLStreamReader(in) : f.createXMLStreamReader(in, enc);
    }

    /**
     * Materialize the element the reader is positioned on (START_ELEMENT), consuming through its
     * matching END_ELEMENT. Attributes become {@code @name} leaves, child elements recurse,
     * significant text becomes the leaf value (or a {@code #text} leaf when the element also has
     * children).
     */
    static ParseResult.Node element(XMLStreamReader r, int[] nodeBudget) throws XMLStreamException {
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
    static void skipElement(XMLStreamReader r) throws XMLStreamException {
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
    static boolean isRecordStart(Deque<String> path, String recordPath) {
        if (recordPath.isEmpty()) return path.size() == 2;
        List<String> want = List.of(recordPath.split("/"));
        if (want.size() > path.size()) return false;
        List<String> have = new ArrayList<>(path);
        return have.subList(have.size() - want.size(), have.size()).equals(want);
    }

    static String localName(XMLStreamReader r) {
        return r.getName().getLocalPart();
    }

    static boolean bool(Object v) {
        return v != null && Boolean.parseBoolean(String.valueOf(v).trim());
    }
}
