package com.gamma.skybase.decoder.asn2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * This class provides methods for decoding byte arrays from ASN.1 structures into various data types.
 */
public class BERDecoder extends BERTags  {

    private static final Logger logger = LoggerFactory.getLogger(BERDecoder.class);

    public static String timeStamp(byte[] data) {
        if (data == null) return "";
        StringBuilder time = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i == 6) { // Timezone indicator
                time.append(data[i] == 1 ? " -" : " +");
            } else {
                time.append((data[i] & 0xF0) >>> 4);
                time.append(data[i] & 0x0F);
            }
        }
        return time.toString();
    }

    public static String ccnTimeStamp(byte[] data) {
        if (data == null || data.length < 5) return "";
        StringBuilder time = new StringBuilder();
        byte[] ts = Arrays.copyOfRange(data, 0, 5);
        time.append(BERTags.tbcdString(ts));

        if (data.length > 6) {
            char tzSign = (char) data[6];
            time.append(tzSign == '1' ? " -" : " +");
        }
        if (data.length > 7) {
            ts = Arrays.copyOfRange(data, 7, data.length);
            time.append(BERTags.tbcdString(ts));
        }
        return time.toString();
    }

    public static String chargingCharacteristics(byte[] data) {
        if (data == null || data.length < 2) return "";
        // Combines parts of two bytes into a single string representation.
        return String.valueOf(data[1]) + ((data[0] & 0xF0) >>> 4) + (data[0] & 0x0F);
    }

    public static Long locationAreaCode(byte[] data) {
        if (data == null) return 0L;
        long octNumber = 0L;
        for (byte b : data) {
            octNumber = (octNumber << 8) | (b & 0xFF);
        }
        return octNumber;
    }

    public static String ipAddress(byte[] data) {
        if (data == null) return "";
        try {
            return InetAddress.getByAddress(data).getHostAddress();
        } catch (UnknownHostException e) {
//            logger.warn("Could not decode IP address from bytes: {}", BERTags.hexString(data), e);
            return "Invalid IP"; // Return a more descriptive string for invalid data
        }
    }

    public static String directoryNumber(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder as = new StringBuilder();
        // The first byte is treated differently, converted to a hex string.
        as.append(HEX_CODE[data[0] >> 4 & 15]);
        as.append(HEX_CODE[data[0] & 15]);
        // The rest of the bytes are decoded as a standard TBCD string.
        if (data.length > 1) {
            as.append(BERTags.tbcdString(Arrays.copyOfRange(data, 1, data.length)));
        }
        return as.toString();
    }

    public static int teleServiceCode(byte[] data) {
        if (data == null || (data.length != 1 && data.length != 2)) {
//            logger.warn("Invalid input: TeleServiceCode should be 1 or 2 octets, but length is {}.", data == null ? "null" : data.length);
            return -1; // Return a default error value
        }
        if (data.length == 1) {
            return data[0] & 0xFF;
        }
        // For two-octet encoding, the first octet must be 0.
        if (data[0] != 0) {
//            logger.warn("Invalid encoding for two-octet TeleserviceCode: first octet must be 0, but was {}.", data[0]);
            return -1;
        }
        return data[1] & 0xFF;
    }
}
