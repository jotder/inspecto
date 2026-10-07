package com.gamma.skybase.decoder.asn2;

//import cryptix.asn1.lang.OctetString;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * A utility class that holds constants for ASN.1 BER (Basic Encoding Rules) tags and classes.
 * This class cannot be instantiated or extended.
 */
public class BERTags {

    // --- UNIVERSAL TAGS ---
    static final int BOOLEAN = 0x01;
    static final int INTEGER = 0x02;
    static final int BIT_STRING = 0x03;
    static final int BITSTRING = 0x03;
    static final int OCTET_STRING = 0x04;
    static final int OCTETSTRING = 0x04;
    static final int NULL = 0x05;
    static final int OBJECT_IDENTIFIER = 0x06;
    static final int OBJECTIDENTIFIER = 0x06;
    static final int EXTERNAL = 0x08;
    static final int ENUMERATED = 0x0a;
    static final int UTF8_STRING = 0x0c;
    static final int UTF8STRING = 0x0c;
    static final int SEQUENCE = 0x10;

    /** A synonym for SEQUENCE, often used to model a SEQUENCE of the same type. */
    static final int SEQUENCE_OF = 0x10;
    static final int SEQUENCEOF = 0x10;
    static final int SET = 0x11;

    /** A synonym for SET, often used to model a SET of the same type. */
    static final int SET_OF = 0x11;
    static final int SETOF = 0x11;
    static final int NUMERIC_STRING = 0x12;
    static final int numericString = 0x12;
    static final int PRINTABLE_STRING = 0x13;
    static final int printableString = 0x13;
    static final int T61_STRING = 0x14;
    static final int T61STRING = 0x14;
    static final int VIDEOTEX_STRING = 0x15;
    static final int videoTexString = 0x15;
    static final int IA5_STRING = 0x16;
    static final int ia5String = 0x16;
    static final int UTC_TIME = 0x17;
    static final int utcTime = 0x17;
    static final int GENERALIZED_TIME = 0x18;
    static final int generalizedTime = 0x18;
    static final int GRAPHIC_STRING = 0x19;
    static final int graphicString = 0x19;
    static final int VISIBLE_STRING = 0x1a;
    static final int visibleString = 0x1a;
    static final int GENERAL_STRING = 0x1b;
    static final int generalString = 0x1b;
    static final int UNIVERSAL_STRING = 0x1c;
    static final int universalString = 0x1c;
    static final int BMP_STRING = 0x1e;
    static final int bmpString = 0x1e;


    static final int CONSTRUCTED = 0x20; // decimal 32   1=CONSTRUCTED or 0=PRIMITIVE
    static final int APPLICATION = 0x40; // decimal 64
    static final int CONTEXT = 0x80; // decimal 128  CONTENT_SPECIFIC tag
    static final int UNIVERSAL = 0x00; // decimal 0    UNIVERSAL data type
    static final int PRIVATE = 0xC0; // decimal 192  PRIVATE data types


    static char[] HEX_CODE = "0123456789ABCDEF".toCharArray();

    public static String objectIdentifier(byte[] data) {
        return "";
    }

    public static String nULL(byte[] data) {
        return "";
    }

    /**
     * Decodes a byte array using a custom AddressString format.
     * The first byte is decoded as standard BCD, while subsequent bytes are TBCD.
     * @param data The byte array to decode.
     * @return The decoded AddressString.
     */
    public static String addressString(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder as = new StringBuilder();
        int m_nibble = (data[0] & 0xF0) >>> 4;
        if (m_nibble < 0x0A) as.append(m_nibble);
        int l_nibble = data[0] & 0x0F;
        if (l_nibble < 0x0A) as.append(l_nibble);

        for (int i = 1; i < data.length; i++) {
            as.append(data[i] & 0x0F);
            as.append((data[i] & 0xF0) >>> 4);
        }
        return as.toString();
    }

    /**
     * Decodes a byte array as an ASN.1 INTEGER.
     * @param data The byte array to decode.
     * @return A BigInteger representing the integer, or null if the input is null.
     */
    public static BigInteger integer(byte[] data) {
        if (data == null) return null;
        return new BigInteger(data);
    }

    /**
     * Decodes a byte array as an ASN.1 ENUMERATED type.
     * @param data The byte array to decode.
     * @return A BigInteger representing the enumerated value, or null if the input is null.
     */
    public static BigInteger enumerated(byte[] data) {
        return integer(data);
    }

    /**
     * Decodes a byte array as an ASN.1 NumberString.
     * @param data The byte array to decode.
     * @return A BigInteger representing the number string, or null if the input is null.
     */
    public static BigInteger numberString(byte[] data) {
        return integer(data);
    }

    /** Decodes a value into a hex string. */
    public static String hexString(byte[] data) {
        if (data == null) return "";
        StringBuilder r = new StringBuilder(data.length * 2);
        for (byte b : data) {
            r.append(HEX_CODE[(b >> 4) & 0xF]);
            r.append(HEX_CODE[b & 0xF]);
        }
        return r.toString();
    }

    /** Decodes a BIT STRING into a hex string. */
    public static String bitString(byte[] data) {
        return hexString(data);
    }

    /**
     * Decodes a byte array as a BCD (Binary-Coded Decimal) string.
     * @param bytes The byte array to decode.
     *      * @return The decoded BCD string.
     *      */
    public static String bcdString(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int highNibble = (b & 0xF0) >>> 4;
            if (highNibble < 0x0A) sb.append(highNibble);

            int lowNibble = b & 0x0F;
            if (lowNibble < 0x0A) sb.append(lowNibble);
        }
        return sb.toString();
    }

    /**
     * Decodes a byte array as a tbcd (Telephony BCD) string.
     * @param data The byte array to decode.
     * @return The decoded TBCD string.
     */
    public static  String tbcdString(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            int low = b & 0x0f;
            int high = (b & 0xf0) >> 4;
            sb.append(HEX_CODE[low]);
            if (high != 0xf) { // 0xF is a filler for odd number of digits
                sb.append(HEX_CODE[high]);
            }
        }
        return sb.toString();
    }

    /**
     * Decodes a byte array as an ASN.1 OCTET STRING using ISO-8859-1 encoding.
     * This ensures a 1:1 mapping for all byte values from 0x00 to 0xFF.
     * @param data The byte array to decode.
     * @return The decoded string.
     */
    public static String octetString(byte[] data) {
        if (data == null) return "";
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    /**
     * Decodes a byte array as an ASN.1 OCTET STRING using ISO-8859-1 encoding.
     * This ensures a 1:1 mapping for all byte values from 0x00 to 0xFF.
     * @param data The byte array to decode.
     * @return The decoded string.
     */
    public static String utf8String(byte[] data) {
        if (data == null) return "";
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Decodes a byte array as a GraphicString, filtering for printable ASCII characters.
     * @param data The byte array to decode.
     * @return The decoded string containing only printable characters.
     */
    public static String graphicString(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            char c = (char) (b & 0xFF);
            if (c >= 0x20 && c <= 0x7E) { // Printable ASCII range
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Decodes a byte array as an ASCII string, replacing non-printable characters.
     * @param data The byte array to decode.
     * @return The decoded string with non-printable characters replaced by '?'.
     */
    public static String asciiString(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            char c = (char) (b & 0xFF);
            if (c >= 0x20 && c <= 0x7E) { // Printable ASCII range
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    /**
     * Decodes a byte array as an IA5String (full 7-bit ASCII).
     * @param data The byte array to decode.
     * @return The decoded string.
     */
    public static String ia5String(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append((char) (b & 0x7F));
        }
        return sb.toString();
    }

    /**
     * Decodes a byte array as a boolean value.
     * @param data The byte array to decode.
     * @return True if the byte array is not empty and its first byte is not zero.
     */
    public static Boolean BOOLEAN(byte[] data) {
        return data != null && data.length > 0 && data[0] != 0;
    }


    public static String userlocationinformation(byte[] ba){

        StringBuilder uli = new StringBuilder();
        int index = 0;
        //if value is 1 its signifies CGI, if 2 then signify SAI
        int cgiInd = ba[index] & 0xF;

        index++;
        StringBuilder mcc = new StringBuilder();
        mcc.append(ba[index] & 0xF);
        mcc.append((ba[index] & 0xF0) >>> 4);

        index++;
        mcc.append(ba[index] & 0xF);

        StringBuilder mnc = new StringBuilder();
        //If only two digits are included in the MNC, then mncFirstDigit value will be 15
        // i.e. octet binary value will be 1111
        int mncFirstDigit = (ba[index] & 0xF0) >>> 4;
        if(mncFirstDigit != 15){
            mnc.append(mncFirstDigit);
        }

        index++;
        mnc.append(ba[index] & 0xF);
        mnc.append((ba[index] & 0xF0) >>> 4);

        String lac = hex2Long(ba, index, 2);
        if (lac.length() < 5) lac = String.format("%05d", Integer.parseInt(lac));

        index += 2;
        String ci = hex2Long(ba, index, 2);
        if (ci.length() < 5) ci = String.format("%05d", Integer.parseInt(ci));

        return uli.append(mcc).append(mnc).append(lac).append(ci).toString();
    }

    public static String hex2Long(byte[] ints, int index, int intLength) throws NumberFormatException {
        if (index > ints.length) {
            throw new NumberFormatException("Given index is out of range of given array, " + "Index = "
                    + index + " array length = " + ints.length);
        }
        long octNumber = 0;
        int length = Math.min((index + intLength), ints.length);
        for (int i = index; i < length; i++) {
            octNumber = octNumber * 16 + ((ints[i] & 0xF0) >>> 4);
            octNumber = octNumber * 16 + (ints[i] & 0x0F);
        }
        return Long.toString(octNumber);
    }

    public static Long locationareacode(byte[] ba) throws NumberFormatException {
        long octNumber = 0L;
        for (byte b : ba) {
            octNumber = octNumber * 16L + (long) ((b & 240) >>> 4);
            octNumber = octNumber * 16L + (long) (b & 15);
        }
        return octNumber;
    }

    public static String plmnid (byte[] hexValues) {
        StringBuilder octNumber = new StringBuilder();
        octNumber.append(hexValues[0] & 0x0F);  //MCC 1
        octNumber.append((hexValues[0] & 0xF0) >>> 4);   //MCC 2
        octNumber.append(hexValues[1] & 0x0F);   //MCC 3
        octNumber.append(hexValues[2] & 0x0F);   //MNC 1
        octNumber.append((hexValues[2] & 0xF0) >>> 4);   //MNC 2
        int mnc3 = (hexValues[1] & 0xF0) >>> 4;
        if (mnc3 < 15)
            octNumber.append((hexValues[1] & 0xF0) >>> 4);

        return octNumber.toString();
    }

    public static String toPDPType(byte[] hexValues) {
        if (hexValues.length == 1) {
            return String.valueOf(hexValues[0]);
        } else if (hexValues.length > 1) {
            return String.valueOf(hexValues[0]) + hexValues[1];
        }
        return "";
    }


    public static String toIMEI(byte[] hexValues) {
        String imei = hex2TBCD(hexValues);
        return imeiCheckDigitAdjustment(imei);
    }

    public static String hex2TBCD(byte[] ba) {
        try {
            StringBuilder strInt = new StringBuilder();
            for (byte t_byte : ba) {
                int m_nibble = t_byte & 0xF0;
                m_nibble = m_nibble >>> 4;
                int l_nibble = t_byte & 0x0F;
                if (l_nibble == 0x0A)
                    strInt.append("A");//"*";
                else if (l_nibble == 0x0B)
                    strInt.append("B");//"#";
                else if (l_nibble == 0x0C)
                    strInt.append("C");//"a";
                else if (l_nibble == 0x0D)
                    strInt.append("D");//"b";
                else if (l_nibble == 0x0E)
                    strInt.append("E");//"c";
                else if (l_nibble < 0x0A)//since it is a BCD string
                    strInt.append(l_nibble);

                if (m_nibble == 0x0A)
                    strInt.append("A");
                else if (m_nibble == 0x0B)
                    strInt.append("B");
                else if (m_nibble == 0x0C)
                    strInt.append("C");
                else if (m_nibble == 0x0D)
                    strInt.append("D");
                else if (m_nibble == 0x0E)
                    strInt.append("E");
                else if (m_nibble < 0x0A) //since it is a BCD string
                    strInt.append(m_nibble);
            }

            return strInt.toString();
        } catch (Exception e) {
//            e.printStackTrace();
        }
        return null;
    }

    public static String imeiCheckDigitAdjustment(String value) {
        StringBuilder imei15 = new StringBuilder();
        if (value != null && !value.isEmpty()) {
            if (value.length() < 14) {
                imei15.append(value);
            } else if (value.length() == 14) {
                imei15.append(value).append(computeCheckDigit(value));
            } else {
                String imei14 = value.substring(0, 14);
                imei15.append(imei14);
                imei15.append(computeCheckDigit(imei14));
            }

            return imei15.toString();
        } else {
            return "";
        }
    }

    private static Integer computeCheckDigit(String imei14) {
        int sum = 0;
        int checkDigit;
        for (checkDigit = 0; checkDigit < imei14.length(); ++checkDigit) {
            char c = imei14.charAt(checkDigit);
            int digit;
            if (checkDigit % 2 != 0) {
                digit = charToInt(c) * 2;
                if (String.valueOf(digit).length() == 2) {
                    char[] var5 = String.valueOf(digit).toCharArray();
                    for (char sub : var5)
                        sum = sum + charToInt(sub);
                } else
                    sum = sum + digit;
            } else {
                digit = charToInt(c);
                sum = sum + digit;
            }
        }

        checkDigit = 0;
        if (sum % 10 != 0)
            checkDigit = 10 - sum % 10;

        return checkDigit;
    }

    private static int charToInt(char c) {
        return c - 48;
    }
}
