package com.gamma.asn.schema;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Built-in value decoders: universal types plus the telecom pseudo-types the legacy
 * decoder dispatched by grammar type name (BERTags/BERDecoder). The byte→text logic is
 * ported VERBATIM from the legacy code — including its oddities (uppercase hex, TBCD
 * fillers as letters, AddressString's first-byte handling) — because Phase 2's exit
 * criterion is output parity with the golden corpus. Deliberate deviations are limited
 * to {@link #objectIdentifier} (legacy returned "") and {@link #ipAddress} for IPv6.
 */
public final class Decoders {

    private static final char[] HEX_CODE = "0123456789ABCDEF".toCharArray();

    private Decoders() {
    }

    /** Uppercase hex — the legacy fallback for every unknown type (BERTags.hexString). */
    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX_CODE[(b >> 4) & 0xF]).append(HEX_CODE[b & 0xF]);
        }
        return sb.toString();
    }

    public static String integer(byte[] bytes) {
        if (bytes.length == 0) {
            return "0";
        }
        return new BigInteger(bytes).toString();
    }

    /** INTEGER content read as unsigned — how the legacy decoder treats counters/ids. */
    public static String unsignedInteger(byte[] bytes) {
        if (bytes.length == 0) {
            return "0";
        }
        return new BigInteger(1, bytes).toString();
    }

    public static String bool(byte[] bytes) {
        return (bytes.length > 0 && bytes[0] != 0) ? "true" : "false";
    }

    /** Legacy octetString: OCTET STRING content is text, ISO-8859-1 (1:1 byte mapping). */
    public static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    public static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    public static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Legacy graphicString: printable ASCII kept, everything else dropped. */
    public static String graphic(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Legacy asciiString: printable ASCII kept, everything else becomes '?'. */
    public static String asciiPrintable(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            sb.append(c >= 0x20 && c <= 0x7E ? c : '?');
        }
        return sb.toString();
    }

    /** Legacy ia5String: 7-bit mask per byte. */
    public static String ia5(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            sb.append((char) (b & 0x7F));
        }
        return sb.toString();
    }

    /** BIT STRING: first content byte is the unused-bit count; remaining bits as hex. */
    public static String bitString(byte[] bytes) {
        if (bytes.length <= 1) {
            return "";
        }
        byte[] rest = new byte[bytes.length - 1];
        System.arraycopy(bytes, 1, rest, 0, rest.length);
        return hex(rest);
    }

    /** Deliberate deviation: the legacy decoder returned "" for every OID. */
    public static String objectIdentifier(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int first = bytes[0] & 0xFF;
        sb.append(first / 40).append('.').append(first % 40);
        long sub = 0;
        for (int i = 1; i < bytes.length; i++) {
            sub = (sub << 7) | (bytes[i] & 0x7F);
            if ((bytes[i] & 0x80) == 0) {
                sb.append('.').append(sub);
                sub = 0;
            }
        }
        return sb.toString();
    }

    /**
     * Legacy tbcdString: nibble-swapped; the LOW nibble is always emitted (as a hex
     * digit, so A–E come out as letters and a low F as 'F'); the high nibble is skipped
     * only when it is the 0xF odd-digit filler.
     */
    public static String tbcd(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int lo = b & 0x0F;
            int hi = (b >> 4) & 0x0F;
            sb.append(HEX_CODE[lo]);
            if (hi != 0xF) {
                sb.append(HEX_CODE[hi]);
            }
        }
        return sb.toString();
    }

    /** Legacy bcdString: high nibble first, nibbles ≥ 0xA dropped. */
    public static String bcd(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int hi = (b >> 4) & 0x0F;
            if (hi < 0x0A) {
                sb.append(hi);
            }
            int lo = b & 0x0F;
            if (lo < 0x0A) {
                sb.append(lo);
            }
        }
        return sb.toString();
    }

    /**
     * Legacy addressString: first byte as BCD (nibbles ≥ 0xA dropped), remaining bytes
     * swapped low-then-high emitted as decimal ints (a 0xF filler comes out as "15" —
     * legacy behaviour, kept for parity).
     */
    public static String addressString(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int hi = (bytes[0] & 0xF0) >>> 4;
        if (hi < 0x0A) {
            sb.append(hi);
        }
        int lo = bytes[0] & 0x0F;
        if (lo < 0x0A) {
            sb.append(lo);
        }
        for (int i = 1; i < bytes.length; i++) {
            sb.append(bytes[i] & 0x0F);
            sb.append((bytes[i] & 0xF0) >>> 4);
        }
        return sb.toString();
    }

    /** Legacy directoryNumber: first byte as hex, rest as TBCD. */
    public static String directoryNumber(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(HEX_CODE[(bytes[0] >> 4) & 0xF]).append(HEX_CODE[bytes[0] & 0xF]);
        if (bytes.length > 1) {
            byte[] rest = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, rest, 0, rest.length);
            sb.append(tbcd(rest));
        }
        return sb.toString();
    }

    /**
     * Legacy timeStamp: BCD digit pairs, with byte 6 as the timezone indicator
     * (1 = " -", anything else = " +").
     */
    public static String timeStamp(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i == 6) {
                sb.append(bytes[i] == 1 ? " -" : " +");
            } else {
                sb.append((bytes[i] & 0xF0) >>> 4).append(bytes[i] & 0x0F);
            }
        }
        return sb.toString();
    }

    /**
     * Legacy ccnTimeStamp: bytes 0–4 as TBCD, byte 6 as ASCII timezone sign
     * ('1' = " -", else " +"), bytes 7+ as TBCD.
     */
    public static String ccnTimeStamp(byte[] bytes) {
        if (bytes.length < 5) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        byte[] head = new byte[5];
        System.arraycopy(bytes, 0, head, 0, 5);
        sb.append(tbcd(head));
        if (bytes.length > 6) {
            sb.append((char) bytes[6] == '1' ? " -" : " +");
        }
        if (bytes.length > 7) {
            byte[] tail = new byte[bytes.length - 7];
            System.arraycopy(bytes, 7, tail, 0, tail.length);
            sb.append(tbcd(tail));
        }
        return sb.toString();
    }

    /** Legacy chargingCharacteristics: byte1 as signed int, then byte0's nibbles. */
    public static String chargingCharacteristics(byte[] bytes) {
        if (bytes.length < 2) {
            return "";
        }
        return String.valueOf(bytes[1]) + ((bytes[0] & 0xF0) >>> 4) + (bytes[0] & 0x0F);
    }

    /** Legacy userlocationinformation: MCC + MNC + zero-padded LAC + CI. */
    public static String userLocationInformation(byte[] ba) {
        if (ba.length < 8) {
            return hex(ba);
        }
        StringBuilder mcc = new StringBuilder();
        mcc.append(ba[1] & 0xF).append((ba[1] & 0xF0) >>> 4).append(ba[2] & 0xF);
        StringBuilder mnc = new StringBuilder();
        int mncFirstDigit = (ba[2] & 0xF0) >>> 4; // 0xF = two-digit MNC
        if (mncFirstDigit != 15) {
            mnc.append(mncFirstDigit);
        }
        mnc.append(ba[3] & 0xF).append((ba[3] & 0xF0) >>> 4);
        String lac = nibblesAsLong(ba, 3, 2);
        if (lac.length() < 5) {
            lac = String.format("%05d", Integer.parseInt(lac));
        }
        String ci = nibblesAsLong(ba, 5, 2);
        if (ci.length() < 5) {
            ci = String.format("%05d", Integer.parseInt(ci));
        }
        return mcc.append(mnc).append(lac).append(ci).toString();
    }

    private static String nibblesAsLong(byte[] bytes, int index, int count) {
        long v = 0;
        int end = Math.min(index + count, bytes.length);
        for (int i = index; i < end; i++) {
            v = (v << 8) | (bytes[i] & 0xFF);
        }
        return Long.toString(v);
    }

    /** Legacy locationAreaCode: big-endian unsigned value as decimal. */
    public static String locationAreaCode(byte[] bytes) {
        long v = 0;
        for (byte b : bytes) {
            v = (v << 8) | (b & 0xFF);
        }
        return Long.toString(v);
    }

    /** Legacy teleServiceCode: 1 or 2 octets; two-octet form requires a zero first octet. */
    public static String teleServiceCode(byte[] bytes) {
        if (bytes.length == 1) {
            return String.valueOf(bytes[0] & 0xFF);
        }
        if (bytes.length == 2 && bytes[0] == 0) {
            return String.valueOf(bytes[1] & 0xFF);
        }
        return "-1";
    }

    /** Legacy toPDPType: first byte as signed int, second concatenated when present. */
    public static String pdpType(byte[] bytes) {
        if (bytes.length == 1) {
            return String.valueOf(bytes[0]);
        }
        if (bytes.length > 1) {
            return String.valueOf(bytes[0]) + bytes[1];
        }
        return "";
    }

    /** Legacy toIMEI: TBCD-style nibbles (A–E letters, no F), padded to 15 with a Luhn check digit. */
    public static String imei(byte[] bytes) {
        StringBuilder digits = new StringBuilder();
        for (byte b : bytes) {
            int lo = b & 0x0F;
            int hi = (b >> 4) & 0x0F;
            if (lo < 0xF) {
                digits.append(HEX_CODE[lo]);
            }
            if (hi < 0xF) {
                digits.append(HEX_CODE[hi]);
            }
        }
        String value = digits.toString();
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() < 14) {
            return value;
        }
        String imei14 = value.substring(0, 14);
        return imei14 + imeiCheckDigit(imei14);
    }

    private static int imeiCheckDigit(String imei14) {
        int sum = 0;
        for (int i = 0; i < imei14.length(); i++) {
            int digit = imei14.charAt(i) - '0';
            if (i % 2 != 0) {
                digit *= 2;
                sum += digit / 10 + digit % 10;
            } else {
                sum += digit;
            }
        }
        return sum % 10 == 0 ? 0 : 10 - sum % 10;
    }

    /** 4 bytes → dotted IPv4 (as legacy via InetAddress); 16 bytes → IPv6; else hex. */
    public static String ipAddress(byte[] bytes) {
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "."
                    + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
        }
        if (bytes.length == 16) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(Integer.toHexString(((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF)));
            }
            return sb.toString();
        }
        return hex(bytes);
    }

    /** Legacy plmnid: MCC digits 1-3, MNC digits 1-2, then MNC digit 3 unless 0xF. */
    public static String plmnId(byte[] bytes) {
        if (bytes.length < 3) {
            return hex(bytes);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(bytes[0] & 0x0F);
        sb.append((bytes[0] & 0xF0) >>> 4);
        sb.append(bytes[1] & 0x0F);
        sb.append(bytes[2] & 0x0F);
        sb.append((bytes[2] & 0xF0) >>> 4);
        int mnc3 = (bytes[1] & 0xF0) >>> 4;
        if (mnc3 < 15) {
            sb.append(mnc3);
        }
        return sb.toString();
    }
}
