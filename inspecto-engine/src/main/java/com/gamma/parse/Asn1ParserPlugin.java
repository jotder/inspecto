package com.gamma.parse;

import com.gamma.asn.core.ByteSource;
import com.gamma.asn.core.Framing;
import com.gamma.asn.core.ParseError;
import com.gamma.asn.core.RecoveryPolicy;
import com.gamma.asn.core.Strictness;
import com.gamma.asn.facade.Asn1Decoder;
import com.gamma.asn.schema.NamedNode;
import com.gamma.config.spec.FieldSpec;
import com.gamma.config.spec.FieldType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ASN.1 as a {@link ParserPlugin}, over the {@code asn-facade} module's public bytes-to-records
 * API ({@link Asn1Decoder}). Registered via {@code META-INF/services} like {@link XmlParserPlugin}.
 *
 * <p>Hierarchical, same as XML: the preview is a record {@link ParseResult.Tree} (one node forest
 * per decoded record), and {@code ingesterClass} is deliberately empty — tree-shaped records cannot
 * honestly load to Tables until the flatten configuration exists (the tree→segments ingest bridge,
 * {@code docs/BACKLOG.md} "Parsing (Stage-1)"), so this parser is <b>preview-only</b> for now.
 *
 * <p>Framing is served, not hardcoded: {@code file_header_length} and {@code record_header_length}
 * cover every layout in the parity corpus (see {@link #framing}). What remains for the declarative
 * decode profile tracked in that backlog entry is sourcing the grammar from a schema-module
 * reference rather than pasted module text.
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

    @Override
    public List<FieldSpec> grammarSchema() {
        return List.of(
                FieldSpec.required("asn1.grammar", "ASN.1 grammar", FieldType.STRING,
                        "The ASN.1 module text (X.680 syntax) defining the record type."),
                FieldSpec.required("asn1.root_type", "Root type", FieldType.STRING,
                        "Name of the type in the grammar each record binds against, e.g. Record."),
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
        if (grammarText.isEmpty())
            throw new IllegalArgumentException("asn1.grammar is required");
        if (rootType.isEmpty())
            throw new IllegalArgumentException("asn1.root_type is required");
        Strictness strictness = strictness(str(asn1.get("strictness")));
        Framing framing = framing(asn1);
        int maxRecords = clampRecords(asn1.get("max_records"));

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
