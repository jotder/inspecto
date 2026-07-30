package com.gamma.asn.schema;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The decode semantics are the LEGACY ones (BERTags/BERDecoder ports) — golden-corpus
 * parity is the contract, oddities included. See Decoders javadoc for the deliberate
 * deviations (OID, IPv6).
 */
class DecodersTest {

    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s);
    }

    @Test
    void tbcdSwapsNibblesLegacyStyle() {
        assertEquals("93707312345", Decoders.tbcd(hex("3907372143F5")));
        // legacy emits A-E as hex letters, only the 0xF high-nibble filler is dropped
        assertEquals("12AB", Decoders.tbcd(hex("21BA")));
    }

    @Test
    void addressStringLegacyKeepsTypeOfAddressDigits() {
        // legacy: first byte as BCD (nibbles >= 0xA dropped), rest swapped as decimal ints
        assertEquals("919370731234515", Decoders.addressString(hex("913907372143F5")));
    }

    @Test
    void octetStringIsTextLikeLegacy() {
        assertEquals("mtn.com.af", Decoders.latin1("mtn.com.af".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
    }

    @Test
    void ccnTimeStamp() {
        // corpus mtna_occ triggerTime: "2509162218 +0430"
        assertEquals("2509162218 +0430", Decoders.ccnTimeStamp(hex("529061228100304003")));
    }

    @Test
    void timeStampBcdWithTimezoneByte() {
        assertEquals("202509162240 -0430", Decoders.timeStamp(hex("202509162240010430")));
        assertEquals("202509162240 +0430", Decoders.timeStamp(hex("202509162240000430")));
    }

    @Test
    void chargingCharacteristics() {
        assertEquals("008", Decoders.chargingCharacteristics(hex("0800")));
    }

    @Test
    void teleServiceCode() {
        assertEquals("17", Decoders.teleServiceCode(hex("11")));
        assertEquals("17", Decoders.teleServiceCode(hex("0011")));
        assertEquals("-1", Decoders.teleServiceCode(hex("0111")));
    }

    @Test
    void imeiPadsWithCheckDigit() {
        assertEquals("000000000000000", Decoders.imei(hex("00000000000000")));
        assertEquals("12", Decoders.imei(hex("21"))); // short values pass through
    }

    @Test
    void ipv4() {
        assertEquals("10.1.200.3", Decoders.ipAddress(hex("0A01C803")));
    }

    @Test
    void plmn() {
        // MCC 412 (Afghanistan), MNC 20: 14 F2 02
        assertEquals("41220", Decoders.plmnId(hex("14F202")));
    }

    @Test
    void objectIdentifier() {
        assertEquals("1.2.840.113549", Decoders.objectIdentifier(hex("2A864886F70D")));
    }

    @Test
    void signedInteger() {
        assertEquals("-1", Decoders.integer(hex("FF")));
        assertEquals("255", Decoders.unsignedInteger(hex("FF")));
    }

    @Test
    void registryFallsBackToUppercaseHex() {
        DecoderRegistry reg = DecoderRegistry.withDefaults();
        assertEquals("DEAD", reg.decode(java.util.List.of("NoSuchType"), null, hex("DEAD")));
    }

    @Test
    void registryNormalizesSpacesAndDashes() {
        DecoderRegistry reg = DecoderRegistry.withDefaults();
        // legacy TagHelper strips spaces/dashes: "HEX STRING" -> HEXSTRING
        assertEquals("DEAD", reg.decode(java.util.List.of("HEX STRING"), null, hex("DEAD")));
        assertEquals("2509162218 +0430", reg.decode(java.util.List.of("CCN-TimeStamp"),
                null, hex("529061228100304003")));
    }

    @Test
    void registryOverrideByName() {
        DecoderRegistry reg = DecoderRegistry.builder()
                .put("ChargingID", Decoders::unsignedInteger)
                .build();
        assertEquals("255", reg.decode(java.util.List.of("chargingid"),
                com.gamma.asn.schema.ast.BuiltinKind.OCTET_STRING, hex("FF")));
    }
}
