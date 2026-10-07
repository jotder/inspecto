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

    private final Map<String, ValueDecoder> byName;

    private DecoderRegistry(Map<String, ValueDecoder> byName) {
        this.byName = Map.copyOf(byName);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Universal types + generic telecom pseudo-types pre-registered. */
    public static DecoderRegistry withDefaults() {
        return builder().build();
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

        private Builder() {
            // universal types — text/format semantics mirror the legacy BERTags methods
            put(BuiltinKind.INTEGER.name(), Decoders::integer);
            put(BuiltinKind.ENUMERATED.name(), Decoders::integer);
            put(BuiltinKind.BOOLEAN.name(), Decoders::bool);
            put(BuiltinKind.OCTET_STRING.name(), Decoders::latin1); // legacy: OCTET STRING is text
            put(BuiltinKind.BIT_STRING.name(), Decoders::bitString);
            put(BuiltinKind.NULL.name(), b -> "");
            put(BuiltinKind.OBJECT_IDENTIFIER.name(), Decoders::objectIdentifier);
            put(BuiltinKind.UTF8_STRING.name(), Decoders::utf8);
            put(BuiltinKind.IA5_STRING.name(), Decoders::ia5);
            put(BuiltinKind.NUMERIC_STRING.name(), Decoders::ascii);
            put(BuiltinKind.PRINTABLE_STRING.name(), Decoders::ascii);
            put(BuiltinKind.VISIBLE_STRING.name(), Decoders::ascii);
            put(BuiltinKind.GRAPHIC_STRING.name(), Decoders::graphic);
            put(BuiltinKind.GENERAL_STRING.name(), Decoders::ascii);
            put(BuiltinKind.TELETEX_STRING.name(), Decoders::ascii);
            put(BuiltinKind.UTC_TIME.name(), Decoders::ascii);
            put(BuiltinKind.GENERALIZED_TIME.name(), Decoders::ascii);
            // telecom pseudo-types the legacy decoder dispatched by grammar type name
            // (TagHelper strips spaces/dashes and falls back to hex — same effect here)
            put("TBCDSTRING", Decoders::tbcd);
            put("TBCD-STRING", Decoders::tbcd);
            put("TBCD", Decoders::tbcd);
            put("BCDSTRING", Decoders::bcd);
            put("HEX", Decoders::hex);
            put("HEXSTRING", Decoders::hex);
            put("ADDRESSSTRING", Decoders::addressString);
            put("ISDN-ADDRESSSTRING", Decoders::addressString);
            put("DIRECTORYNUMBER", Decoders::directoryNumber);
            put("IPADDRESS", Decoders::ipAddress);
            put("PLMN-ID", Decoders::plmnId);
            put("PLMNID", Decoders::plmnId);
            put("TIMESTAMP", Decoders::timeStamp);
            put("CCNTIMESTAMP", Decoders::ccnTimeStamp);
            put("CHARGINGCHARACTERISTICS", Decoders::chargingCharacteristics);
            put("USERLOCATIONINFORMATION", Decoders::userLocationInformation);
            put("LOCATIONAREACODE", Decoders::locationAreaCode);
            put("TELESERVICECODE", Decoders::teleServiceCode);
            put("TOPDPTYPE", Decoders::pdpType);
            put("TOIMEI", Decoders::imei);
            // NOT registered although legacy had methods for them: IMEI and NumberString —
            // the corpus grammars DEFINE those names (IMEI ::= TBCD-STRING,
            // NumberString ::= IA5String) and legacy dispatched the resolved base type;
            // registering the name here would hijack the resolution (awcc servedIMEI,
            // sdp subscriberNumber)
            put("ASCIISTRING", Decoders::asciiPrintable);
        }

        /** Registers or overrides a decoder by grammar type name (case-insensitive). */
        public Builder put(String typeName, ValueDecoder decoder) {
            byName.put(normalize(typeName), decoder);
            return this;
        }

        public DecoderRegistry build() {
            return new DecoderRegistry(byName);
        }
    }
}
