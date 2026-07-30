//package com.gamma.skybase.decoder.asn2;
//
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import static org.junit.jupiter.api.Assertions.*;
//
//class BERDecoderTest {
//
//    private Decoder decoder;
//
//    @BeforeEach
//    void setUp() {
//        decoder = new BERDecoder();
//    }
//
//    @Test
//    @DisplayName("Test timestamp decoding with valid data")
//    void testTimeStamp_valid() {
//        byte[] data = {(byte) 0x20, (byte) 0x24, (byte) 0x01, (byte) 0x01, (byte) 0x12, (byte) 0x00, (byte) 0x00, (byte) 0x05, (byte) 0x30};
//        String expected = "202401011200 +530";
//        assertEquals(expected, decoder.timeStamp(data));
//    }
//
//    @Test
//    @DisplayName("Test timestamp decoding with timezone indicator")
//    void testTimeStamp_timezone() {
//        byte[] data = {(byte) 0x20, (byte) 0x24, (byte) 0x01, (byte) 0x01, (byte) 0x12, (byte) 0x00, (byte) 0x01, (byte) 0x05, (byte) 0x30};
//        String expected = "202401011200 -530";
//        assertEquals(expected, decoder.timeStamp(data));
//    }
//
//    @Test
//    @DisplayName("Test timestamp decoding with null data")
//    void testTimeStamp_null() {
//        assertEquals("", decoder.timeStamp(null));
//    }
//
//    @Test
//    @DisplayName("Test CCN timestamp decoding with valid data")
//    void testCcnTimeStamp_valid() {
//        byte[] data = {(byte) 0x20, (byte) 0x24, (byte) 0x01, (byte) 0x01, (byte) 0x12, (byte) 0x00, '1', (byte) 0x05, (byte) 0x30};
//        String expected = "2024010112 -530";
//        assertEquals(expected, decoder.ccnTimeStamp(data));
//    }
//
//    @Test
//    @DisplayName("Test CCN timestamp decoding with null data")
//    void testCcnTimeStamp_null() {
//        assertEquals("", decoder.ccnTimeStamp(null));
//    }
//
//    @Test
//    @DisplayName("Test charging characteristics decoding with valid data")
//    void testChargingCharacteristics_valid() {
//        byte[] data = {(byte) 0x12, (byte) 0x03};
//        String expected = "312";
//        assertEquals(expected, decoder.chargingCharacteristics(data));
//    }
//
//    @Test
//    @DisplayName("Test charging characteristics decoding with null data")
//    void testChargingCharacteristics_null() {
//        assertEquals("", decoder.chargingCharacteristics(null));
//    }
//
//    @Test
//    @DisplayName("Test location area code decoding with valid data")
//    void testLocationAreaCode_valid() {
//        byte[] data = {(byte) 0x01, (byte) 0x02};
//        Long expected = 258L;
//        assertEquals(expected, decoder.locationAreaCode(data));
//    }
//
//    @Test
//    @DisplayName("Test location area code decoding with null data")
//    void testLocationAreaCode_null() {
//        assertEquals(0L, decoder.locationAreaCode(null));
//    }
//
//    @Test
//    @DisplayName("Test IP address decoding with valid data")
//    void testIpAddress_valid() {
//        byte[] data = {(byte) 192, (byte) 168, (byte) 1, (byte) 1};
//        String expected = "192.168.1.1";
//        assertEquals(expected, decoder.ipAddress(data));
//    }
//
//    @Test
//    @DisplayName("Test IP address decoding with invalid data")
//    void testIpAddress_invalid() {
//        byte[] data = {(byte) 192, (byte) 168};
//        assertEquals("Invalid IP", decoder.ipAddress(data));
//    }
//
//    @Test
//    @DisplayName("Test IP address decoding with null data")
//    void testIpAddress_null() {
//        assertEquals("", decoder.ipAddress(null));
//    }
//
//    @Test
//    @DisplayName("Test directory number decoding with valid data")
//    void testDirectoryNumber_valid() {
//        byte[] data = {(byte) 0x12, (byte) 0x34, (byte) 0x56};
//        String expected = "123456";
//        assertEquals(expected, decoder.directoryNumber(data));
//    }
//
//    @Test
//    @DisplayName("Test directory number decoding with null data")
//    void testDirectoryNumber_null() {
//        assertEquals("", decoder.directoryNumber(null));
//    }
//
//    @Test
//    @DisplayName("Test teleservice code decoding with one octet")
//    void testTeleServiceCode_oneOctet() {
//        byte[] data = {(byte) 0x12};
//        int expected = 18;
//        assertEquals(expected, decoder.teleServiceCode(data));
//    }
//
//    @Test
//    @DisplayName("Test teleservice code decoding with two octets")
//    void testTeleServiceCode_twoOctets() {
//        byte[] data = {(byte) 0x00, (byte) 0x12};
//        int expected = 18;
//        assertEquals(expected, decoder.teleServiceCode(data));
//    }
//
//    @Test
//    @DisplayName("Test teleservice code decoding with invalid two octet encoding")
//    void testTeleServiceCode_invalidTwoOctet() {
//        byte[] data = {(byte) 0x01, (byte) 0x12};
//        assertEquals(-1, decoder.teleServiceCode(data));
//    }
//
//    @Test
//    @DisplayName("Test teleservice code decoding with invalid length")
//    void testTeleServiceCode_invalidLength() {
//        byte[] data = {(byte) 0x01, (byte) 0x12, (byte) 0x34};
//        assertEquals(-1, decoder.teleServiceCode(data));
//    }
//
//    @Test
//    @DisplayName("Test teleservice code decoding with null data")
//    void testTeleServiceCode_null() {
//        assertEquals(-1, decoder.teleServiceCode(null));
//    }
//}
