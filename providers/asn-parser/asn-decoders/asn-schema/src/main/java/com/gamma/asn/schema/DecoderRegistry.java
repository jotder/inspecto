package com.gamma.asn.schema;

import com.gamma.asn.schema.ast.BuiltinKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Explicit name → decoder registration (REDESIGN.md §4.3): no reflection scans, no
 * case-collision roulette. Immutable once built; per-pipeline, never static. Lookup walks
 * the type's reference-name chain (most specific first), then the builtin kind, then hex.
 */
public final class DecoderRegistry {

    public record DecoderMeta(String id, String description, String sqlType) {
    }

    private final Map<String, ValueDecoder> byName;
    private final List<DecoderMeta> metas;

    private DecoderRegistry(Map<String, ValueDecoder> byName, List<DecoderMeta> metas) {
        this.byName = Map.copyOf(byName);
        this.metas = List.copyOf(metas);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Universal types + generic telecom pseudo-types pre-registered. */
    public static DecoderRegistry withDefaults() {
        return builder().build();
    }

    /** Catalog of registered decoders with descriptions and SQL target types. */
    public List<DecoderMeta> listDecoders() {
        return metas;
    }

    public String decode(List<String> nameChain, BuiltinKind kind, byte[] bytes) {
        return resolve(nameChain, kind).decode(bytes);
    }

    /**
     * The decoder this chain/kind resolves to, never null (hex is the floor). Resolution
     * normalizes every name it tries, which allocates — callers on a hot path should
     * resolve ONCE per type and reuse, as {@link SchemaBinder} does, rather than calling
     * {@link #decode} per value.
     */
    public ValueDecoder resolve(List<String> nameChain, BuiltinKind kind) {
        for (String name : nameChain) {
            ValueDecoder d = byName.get(normalize(name));
            if (d != null) {
                return d;
            }
        }
        if (kind != null) {
            ValueDecoder d = byName.get(kind.name());
            if (d != null) {
                return d;
            }
        }
        return Decoders::hex;
    }

    private static String normalize(String name) {
        // legacy TagHelper.getDecodeMethod strips spaces and dashes before lookup
        return name.toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
    }

    public static final class Builder {

        private final Map<String, ValueDecoder> byName = new LinkedHashMap<>();
        private final Map<String, DecoderMeta> metaById = new LinkedHashMap<>();

        private Builder() {
            // universal types — text/format semantics mirror the legacy BERTags methods
            register(BuiltinKind.INTEGER.name(), "Signed integer", "BIGINT", Decoders::integer);
            register(BuiltinKind.ENUMERATED.name(), "Enumerated integer", "BIGINT", Decoders::integer);
            register(BuiltinKind.BOOLEAN.name(), "Boolean flag", "BOOLEAN", Decoders::bool);
            register(BuiltinKind.OCTET_STRING.name(), "ISO-8859-1 octet string", "VARCHAR", Decoders::latin1);
            register(BuiltinKind.BIT_STRING.name(), "Bit string as uppercase hex", "VARCHAR", Decoders::bitString);
            register(BuiltinKind.NULL.name(), "ASN.1 NULL", "VARCHAR", b -> "");
            register(BuiltinKind.OBJECT_IDENTIFIER.name(), "Dotted object identifier", "VARCHAR", Decoders::objectIdentifier);
            register(BuiltinKind.UTF8_STRING.name(), "UTF-8 string", "VARCHAR", Decoders::utf8);
            register(BuiltinKind.IA5_STRING.name(), "7-bit IA5 string", "VARCHAR", Decoders::ia5);
            register(BuiltinKind.NUMERIC_STRING.name(), "Numeric string", "VARCHAR", Decoders::ascii);
            register(BuiltinKind.PRINTABLE_STRING.name(), "Printable string", "VARCHAR", Decoders::ascii);
            register(BuiltinKind.VISIBLE_STRING.name(), "Visible string", "VARCHAR", Decoders::ascii);
            register(BuiltinKind.GRAPHIC_STRING.name(), "Graphic string (printable ASCII only)", "VARCHAR", Decoders::graphic);
            register(BuiltinKind.GENERAL_STRING.name(), "General string", "VARCHAR", Decoders::ascii);
            register(BuiltinKind.TELETEX_STRING.name(), "Teletex string", "VARCHAR", Decoders::ascii);
            register(BuiltinKind.UTC_TIME.name(), "UTC time string", "TIMESTAMP", Decoders::ascii);
            register(BuiltinKind.GENERALIZED_TIME.name(), "Generalized time string", "TIMESTAMP", Decoders::ascii);
            // telecom pseudo-types the legacy decoder dispatched by grammar type name
            // (TagHelper strips spaces/dashes and falls back to hex — same effect here)
            register("tbcd", "Nibble-swapped TBCD digits", "VARCHAR", Decoders::tbcd,
                    "TBCDSTRING", "TBCD-STRING", "TBCD");
            register("bcd", "High-nibble-first BCD digits", "VARCHAR", Decoders::bcd, "BCDSTRING");
            register("hex", "Uppercase hexadecimal bytes", "VARCHAR", Decoders::hex, "HEX", "HEXSTRING");
            register("addressString", "Telecom address string", "VARCHAR", Decoders::addressString,
                    "ADDRESSSTRING", "ISDN-ADDRESSSTRING");
            register("directoryNumber", "Directory number (TON/NPI + TBCD)", "VARCHAR", Decoders::directoryNumber,
                    "DIRECTORYNUMBER");
            register("ipAddress", "IPv4 or IPv6 address", "VARCHAR", Decoders::ipAddress, "IPADDRESS");
            register("plmnId", "PLMN MCC+MNC identifier", "VARCHAR", Decoders::plmnId, "PLMN-ID", "PLMNID");
            register("timeStamp", "BCD timestamp with timezone", "TIMESTAMP", Decoders::timeStamp, "TIMESTAMP");
            register("ccnTimeStamp", "CCN TBCD timestamp with timezone", "TIMESTAMP", Decoders::ccnTimeStamp,
                    "CCNTIMESTAMP");
            register("chargingCharacteristics", "Charging characteristics code", "VARCHAR",
                    Decoders::chargingCharacteristics, "CHARGINGCHARACTERISTICS");
            register("userLocationInformation", "User location (MCC/MNC/LAC/CI)", "VARCHAR",
                    Decoders::userLocationInformation, "USERLOCATIONINFORMATION");
            register("locationAreaCode", "Big-endian unsigned location area code", "BIGINT",
                    Decoders::locationAreaCode, "LOCATIONAREACODE");
            register("teleServiceCode", "Teleservice code", "INTEGER", Decoders::teleServiceCode,
                    "TELESERVICECODE");
            register("pdpType", "PDP type code", "VARCHAR", Decoders::pdpType, "TOPDPTYPE");
            register("imei", "IMEI with Luhn check digit", "VARCHAR", Decoders::imei, "TOIMEI");
            // NOT registered although legacy had methods for them: IMEI and NumberString —
            // the corpus grammars DEFINE those names (IMEI ::= TBCD-STRING,
            // NumberString ::= IA5String) and legacy dispatched the resolved base type;
            // registering the name here would hijack the resolution (awcc servedIMEI,
            // sdp subscriberNumber)
            register("asciiPrintable", "Printable ASCII string ('?' for non-printable)", "VARCHAR",
                    Decoders::asciiPrintable, "ASCIISTRING");
        }

        private void register(String id, String description, String sqlType,
                              ValueDecoder decoder, String... aliases) {
            metaById.put(id, new DecoderMeta(id, description, sqlType));
            if (aliases.length == 0) {
                byName.put(normalize(id), decoder);
            } else {
                for (String alias : aliases) {
                    byName.put(normalize(alias), decoder);
                }
            }
        }

        /** Registers or overrides a decoder by grammar type name (case-insensitive). */
        public Builder put(String typeName, ValueDecoder decoder) {
            byName.put(normalize(typeName), decoder);
            metaById.putIfAbsent(typeName, new DecoderMeta(typeName, "Custom decoder", "VARCHAR"));
            return this;
        }

        public DecoderRegistry build() {
            return new DecoderRegistry(byName, List.copyOf(metaById.values()));
        }
    }
}
