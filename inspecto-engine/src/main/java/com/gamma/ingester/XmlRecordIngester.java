package com.gamma.ingester;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RecordSink;
import com.gamma.etl.StreamingFileIngester;
import com.gamma.parse.ParseResult;
import com.gamma.parse.XmlRecordReader;

import javax.xml.stream.XMLStreamException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link StreamingFileIngester} for XML, over the same {@link XmlRecordReader} that produces
 * {@code com.gamma.parse.XmlParserPlugin}'s preview. This is the <b>tree&rarr;segments bridge</b>:
 * it is what makes the XML parser genuinely {@code ingestable} rather than preview-only, by mapping
 * a decoded record tree onto segment schemas through the ordinary {@code parsing.plugin} machinery.
 *
 * <h3>Segment selection</h3>
 * One segment key per record element name. The record element's local name is matched against the
 * keys of {@code parsing.plugin.segments} — so a document holding several record kinds under one
 * root ingests by leaving {@code record_element} blank and declaring a segment per kind. Records
 * whose element name is not a declared segment are counted as junk and skipped, exactly as
 * {@link Asn1RecordIngester} and {@link TypedRecordIngester} treat an unknown type.
 *
 * <h3>Columns</h3>
 * A segment's columns are its schema's {@code raw.fields}, and {@code raw.fields[].selector} is a
 * <b>dotted path</b> into the record tree — the same notation the JSON read path and
 * {@link Asn1RecordIngester} use, so there is one path convention across ingesters rather than a
 * fourth. The path steps are the labels the preview shows, which is the whole point of sharing the
 * reader: {@code customer.name}, an attribute as {@code @id}, and an element that has both text and
 * children as {@code #text}. A trailing {@code EVENT_TYPE} column is derived (the segment key), the
 * {@link TypedRecordIngester} convention.
 *
 * <p>⚠ A selector must name a <b>leaf that occurs once</b>. Resolving to a container yields
 * {@code NULL} rather than a stringified subtree, and so does a step matching <em>repeated</em>
 * sibling elements — XML has no first-one-wins rule that is not a guess, and silently taking one of
 * five {@code item} elements would be a lie dressed as a decoded value. A repeated element is not
 * one column; give it its own segment by naming it as the {@code record_element}.
 *
 * <h3>Configuration</h3>
 * <pre>
 * parsing:
 *   frontend: plugin
 *   plugin:
 *     ingester: com.gamma.ingester.XmlRecordIngester
 *     segments:
 *       order:  config/xml/order_schema.toon
 *       return: config/xml/return_schema.toon
 *     ingester_config:
 *       record_element: ""          # local name (order), slash path (orders/order),
 *                                   # or blank = every direct child of the root
 *       namespace_aware: false      # labels stay local names either way
 *       encoding: utf-8             # overrides the document prolog (default: auto-detect)
 * </pre>
 * {@code max_records} may also be present — it bounds the parser's preview and is ignored here.
 *
 * <h3>Failure handling</h3>
 * A malformed document fails the file so the framework quarantines it
 * {@code QUARANTINED_UNREADABLE}: XML is not resynchronizable mid-document, and half a loaded file
 * is worse than a quarantined one. Zero emitted records &rArr; {@code QUARANTINED_MISMATCH}, as usual.
 */
public final class XmlRecordIngester implements StreamingFileIngester {

    /** Public no-arg constructor required for reflective load by the batch strategies. */
    public XmlRecordIngester() {}

    /** The derived column carrying the segment key, appended to every segment. */
    static final String EVENT_TYPE = "EVENT_TYPE";

    @Override
    public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
        Map<String, Object> opts = cfg.schemas().ingesterConfig();
        Map<String, List<String>> selectorsByKey = declareSegments(cfg, sink);

        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            XmlRecordReader.read(in, opts, new int[]{Integer.MAX_VALUE}, new XmlRecordReader.Handler() {
                @Override
                public boolean wants() {
                    return true;   // every record is a candidate; the segment match decides
                }

                @Override
                public void record(ParseResult.Node record) throws Exception {
                    List<String> selectors = selectorsByKey.get(record.label());
                    if (selectors == null) {          // not a declared segment → junk candidate
                        sink.junk();
                        return;
                    }
                    Object[] values = new Object[selectors.size() + 1];
                    for (int i = 0; i < selectors.size(); i++) {
                        values[i] = scalar(resolve(record, selectors.get(i)));
                    }
                    values[selectors.size()] = record.label();   // trailing EVENT_TYPE
                    sink.emit(record.label(), values);
                }
            });
        } catch (XMLStreamException notXml) {
            throw new IOException("XML decode failed in " + file.getName() + ": " + notXml.getMessage(), notXml);
        }
    }

    /**
     * Declare every segment's columns up front (raw.fields + the derived {@code EVENT_TYPE}) and
     * return each key's selector list, so the per-record loop never re-walks the config map.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> declareSegments(PipelineConfig cfg, RecordSink sink) throws Exception {
        if (cfg.schemas().segments() == null || cfg.schemas().segments().isEmpty())
            throw new IllegalArgumentException("XmlRecordIngester requires at least one segment");
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

    /**
     * Walk a dotted selector into the record tree. Missing at any step, or a step matching more
     * than one sibling, yields {@code null} — see the class doc on why a repeated element is not a
     * column.
     */
    private static ParseResult.Node resolve(ParseResult.Node record, String selector) {
        ParseResult.Node current = record;
        for (String step : selector.split("\\.")) {
            ParseResult.Node match = null;
            for (ParseResult.Node child : current.children()) {
                if (!child.label().equals(step)) continue;
                if (match != null) return null;   // repeated sibling — ambiguous, not a column
                match = child;
            }
            if (match == null) return null;
            current = match;
        }
        return current;
    }

    /** Leaves carry their value (the sink stores VARCHAR); containers are not a column. */
    private static Object scalar(ParseResult.Node node) {
        return node == null || !node.children().isEmpty() ? null : node.value();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
