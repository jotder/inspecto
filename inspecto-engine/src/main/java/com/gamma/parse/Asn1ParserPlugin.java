package com.gamma.parse;

import com.gamma.asn.core.ByteSource;
import com.gamma.asn.core.Framing;
import com.gamma.asn.core.ParseError;
import com.gamma.asn.core.RecordReader;
import com.gamma.asn.core.RecoveryPolicy;
import com.gamma.asn.core.Strictness;
import com.gamma.asn.core.Tlv;
import com.gamma.asn.facade.Asn1Decoder;
import com.gamma.asn.schema.NamedNode;
import com.gamma.config.spec.FieldSpec;
import com.gamma.config.spec.FieldType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ASN.1 as a {@link ParserPlugin}, over the {@code asn-facade} module's public bytes-to-records
 * API ({@link Asn1Decoder}). Registered via {@code META-INF/services} like {@link XmlParserPlugin}.
 *
 * <p>Hierarchical, same as XML: the preview is a record {@link ParseResult.Tree} (one node forest
 * per decoded record). Unlike XML this parser DOES name an {@code ingesterClass}
 * ({@code Asn1RecordIngester}), so it loads to Tables through the existing {@code parsing.plugin}
 * machinery.
 *
 * <p><b>The grammar is optional for preview.</b> BER is self-describing — every value carries its
 * own tag and length — so with no {@code asn1.grammar} the sample is dumped <i>structurally</i>:
 * the raw TLV forest, nodes labelled by tag ({@code [0]}, {@code [APPLICATION 1]}, …) instead of by
 * schema name. That is what lets an operator look at an unknown vendor's file <i>before</i> they
 * have its {@code .asn} module, which is exactly the onboarding situation. Supply the grammar and
 * the same bytes come back with real names and decoded values.
 *
 * <p>⚠ Structural mode is a <b>preview</b> capability only. Ingest still requires a grammar
 * ({@code ingester_config.grammar}): anonymous tags cannot be mapped onto segment columns, and a
 * column named {@code [0]} would be worthless.
 *
 * <p>Framing is served, not hardcoded: {@code file_header_length} and {@code record_header_length}
 * cover every layout in the parity corpus (see {@link #framing}). What remains for the declarative
 * decode profile tracked in {@code docs/BACKLOG.md} "Parsing (Stage-1)" is sourcing the grammar
 * from a schema-module reference rather than pasted module text.
 */
public final class Asn1ParserPlugin implements ParserPlugin {

    /** Hard bound on records materialized into the preview forest. */
    static final int MAX_RECORDS = 1000;
    /** Default when the grammar does not set {@code max_records}. */
    static final int DEFAULT_RECORDS = 50;

    @Override
    public String id() {
        return "asn1";
    }

    @Override
    public String label() {
        return "ASN.1 — BER/DER encoded records";
    }

    @Override
    public boolean hierarchical() {
        return true;
    }

    /**
     * Unlike XML, ASN.1 can load to Tables today: {@code Asn1RecordIngester} flattens decoded
     * records onto segment schemas via the existing {@code parsing.plugin} machinery. The segments
     * are still authored in the TOON until the segments editor ships.
     */
    @Override
    public Optional<String> ingesterClass() {
        return Optional.of("com.gamma.ingester.Asn1RecordIngester");
    }

    @Override
    public List<FieldSpec> grammarSchema() {
        return List.of(
                FieldSpec.of("asn1.grammar", "ASN.1 grammar", FieldType.STRING,
                        "The ASN.1 module text (X.680 syntax) defining the record type. "
                                + "Leave EMPTY to dump the file's raw TLV structure instead — BER is "
                                + "self-describing, so an unknown file can be inspected before its "
                                + "module is available. A grammar is still required to ingest."),
                FieldSpec.of("asn1.root_type", "Root type", FieldType.STRING,
                        "Name of the type in the grammar each record binds against, e.g. Record. "
                                + "Required when a grammar is supplied; ignored in structural mode."),
                FieldSpec.enumField("asn1.strictness", "Strictness",
                        List.of("BER", "DER", "CER"), "BER",
                        "Encoding rules enforced while decoding: BER (permissive), DER, or CER."),
                FieldSpec.withDefault("asn1.file_header_length", "File header bytes", FieldType.INT, 0,
                        "Bytes to skip at the start of the file before the first record "
                                + "(e.g. 50 for Huawei-framed files). 0 = none."),
                FieldSpec.withDefault("asn1.record_header_length", "Record header bytes", FieldType.INT, 0,
                        "Bytes preceding each record's TLV, skipped (e.g. 4 for Huawei-framed files). "
                                + "0 = bare back-to-back TLVs. Records stay delimited by their own BER length."),
                FieldSpec.withDefault("asn1.max_records", "Preview records", FieldType.INT, DEFAULT_RECORDS,
                        "Records materialized into the preview tree (max " + MAX_RECORDS + ")."));
    }

    @Override
    public ParseResult preview(byte[] sample, Map<String, Object> grammar) throws Exception {
        if (sample == null || sample.length == 0)
            throw new IllegalArgumentException("sample content is required");
        Map<String, Object> asn1 = sub(grammar, "asn1");
        String grammarText = str(asn1.get("grammar"));
        String rootType = str(asn1.get("root_type"));
        Strictness strictness = strictness(str(asn1.get("strictness")));
        Framing framing = framing(asn1);
        int maxRecords = clampRecords(asn1.get("max_records"));

        // No grammar: dump the self-describing TLV structure so an unknown file can be inspected.
        if (grammarText.isEmpty()) {
            if (!rootType.isEmpty())
                throw new IllegalArgumentException(
                        "asn1.root_type was set without a grammar — supply asn1.grammar to bind "
                                + "against '" + rootType + "', or clear root_type for a structural dump");
            return structural(sample, framing, strictness, maxRecords);
        }
        if (rootType.isEmpty())
            throw new IllegalArgumentException("asn1.root_type is required when a grammar is supplied");

        Asn1Decoder decoder;
        try {
            decoder = Asn1Decoder.compile(grammarText, rootType);
        } catch (Exception badGrammar) {
            throw new IllegalArgumentException("invalid ASN.1 grammar: " + badGrammar.getMessage(), badGrammar);
        }

        List<ParseError> errors = new ArrayList<>();
        List<NamedNode> records;
        try (ByteSource src = ByteSource.of(sample)) {
            records = decoder.decode(src, framing, strictness, RecoveryPolicy.SKIP_RECORD, errors::add)
                    .toList();
        }
        if (records.isEmpty()) {
            throw new IllegalArgumentException(errors.isEmpty()
                    ? "no records of type '" + rootType + "' found in the sample"
                    : "sample did not decode against root type '" + rootType + "': " + errors.get(0).message());
        }
        List<ParseResult.Node> nodes = records.stream().limit(maxRecords).map(Asn1ParserPlugin::toNode).toList();
        return new ParseResult.Tree(records.size(), nodes);
    }

    /** Bytes of a primitive value rendered into the preview before truncation. */
    static final int VALUE_PREVIEW_BYTES = 32;

    /**
     * Grammar-less preview: walk the raw TLV forest and label every node by its tag. Uses
     * {@link RecordReader} directly (it takes no schema) rather than the facade, since there is
     * nothing to bind against. {@code SKIP_RECORD} matches the bound path — a preview should show
     * what it can rather than fail whole on one bad record.
     */
    private static ParseResult.Tree structural(byte[] sample, Framing framing, Strictness strictness,
                                               int maxRecords) throws Exception {
        List<ParseError> errors = new ArrayList<>();
        List<ParseResult.Node> nodes = new ArrayList<>();
        int total = 0;
        try (ByteSource src = ByteSource.of(sample)) {
            RecordReader reader = new RecordReader(src, framing, strictness,
                    RecoveryPolicy.SKIP_RECORD, errors::add);
            while (reader.hasNext()) {
                Tlv record = reader.next();
                total++;
                if (nodes.size() < maxRecords) nodes.add(toNode(record, src));
            }
            if (total == 0)
                throw new IllegalArgumentException(errors.isEmpty()
                        ? "no ASN.1 records found in the sample — check the framing "
                                + "(file_header_length / record_header_length)"
                        : "sample did not decode as ASN.1: " + errors.get(0).message());
            return new ParseResult.Tree(total, List.copyOf(nodes));
        }
    }

    /** One raw TLV to a preview node: tag as the label, decoded bytes as the value. */
    private static ParseResult.Node toNode(Tlv t, ByteSource src) {
        if (t.constructed()) {
            List<ParseResult.Node> children = t.children().stream().map(c -> toNode(c, src)).toList();
            return ParseResult.Node.container(t.tagString(), "constructed", children);
        }
        return ParseResult.Node.leaf(t.tagString(), "primitive · " + t.valueLength() + "B",
                renderValue(t, src));
    }

    /**
     * A primitive's bytes, hex first. Without a grammar there is NO type information, so hex is the
     * only honest rendering — an INTEGER 42 shown as {@code "*"} because 0x2A happens to be
     * printable would be a lie dressed as a decoded value. Text is therefore appended as an
     * <i>annotation</i> ({@code 6869 "hi"}), and only for 2+ bytes that are all printable, which is
     * where IA5String/NumericString identifiers actually live in CDR grammars. Truncated at
     * {@link #VALUE_PREVIEW_BYTES}: a preview must not materialize a large value.
     */
    private static String renderValue(Tlv t, ByteSource src) {
        if (t.valueLength() == 0) return "";
        int shown = (int) Math.min(t.valueLength(), VALUE_PREVIEW_BYTES);
        byte[] bytes = src.bytes(t.valueOffset(), shown);
        boolean truncated = t.valueLength() > shown;

        StringBuilder out = new StringBuilder(shown * 2 + 1);
        for (byte b : bytes) out.append(String.format("%02X", b));
        if (truncated) out.append('…');

        if (shown >= 2 && allPrintable(bytes)) {
            out.append(" \"").append(new String(bytes, java.nio.charset.StandardCharsets.US_ASCII));
            if (truncated) out.append('…');
            out.append('"');
        }
        return out.toString();
    }

    private static boolean allPrintable(byte[] bytes) {
        for (byte b : bytes) {
            if (b < 0x20 || b > 0x7E) return false;
        }
        return true;
    }

    private static ParseResult.Node toNode(NamedNode n) {
        List<ParseResult.Node> children = n.children().stream().map(Asn1ParserPlugin::toNode).toList();
        return children.isEmpty()
                ? ParseResult.Node.leaf(n.name(), n.typeName(), n.value())
                : ParseResult.Node.container(n.name(), n.typeName(), children);
    }

    /**
     * The record layout, from the two knobs real files actually vary by. 0x00/0xFF padding between
     * records is unconditional, matching the legacy reader (`ASN1Utils.readTag` skips both before
     * every record tag — e.g. the zero-fill in Ericsson OCC files) and the parity harness that
     * pins the rewrite to it. ⚠ A record header made of those bytes would therefore be eaten as
     * padding before the header is ever counted.
     *
     * <p>Deliberately NOT exposed: trailer length, and the length-prefix machinery
     * ({@code lengthOffset}/{@code lengthSize}/endianness/{@code lengthIncludesHeader}) that
     * {@link Framing.RecordHeaderSpec} can express. No file in the parity corpus uses either — every
     * framed case is {@code skipOnly}, leaving records delimited by their own BER length — so
     * serving those fields would be offering knobs nothing has ever needed. They stay available in
     * asn-core the moment a real file demands them.
     */
    private static Framing framing(Map<String, Object> asn1) {
        int fileHeader = nonNegative(asn1.get("file_header_length"), "file_header_length");
        int recordHeader = nonNegative(asn1.get("record_header_length"), "record_header_length");
        return Framing.of(new Framing.FramingSpec(fileHeader, 0, Set.of(0x00, 0xFF),
                recordHeader == 0 ? null : Framing.RecordHeaderSpec.skipOnly(recordHeader)));
    }

    private static int nonNegative(Object v, String field) {
        if (v == null) return 0;
        int n;
        try {
            n = Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException(field + " must be a number, got: " + v);
        }
        if (n < 0) throw new IllegalArgumentException(field + " must not be negative, got: " + n);
        return n;
    }

    private static Strictness strictness(String v) {
        return switch (v.toUpperCase()) {
            case "", "BER" -> Strictness.BER;
            case "DER" -> Strictness.DER;
            case "CER" -> Strictness.CER;
            default -> throw new IllegalArgumentException("asn1.strictness must be BER, DER or CER, got: " + v);
        };
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

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
