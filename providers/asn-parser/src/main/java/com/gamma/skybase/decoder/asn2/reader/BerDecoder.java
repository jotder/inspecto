package com.gamma.skybase.decoder.asn2.reader;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BerDecoder extends ASNReader implements Serializable {

    private static final Logger log = LoggerFactory.getLogger(BerDecoder.class);

    BufferedInputStream in;
    Deque<Integer> stack;
    int tagValueLength;

    int size = -1;

    public BerDecoder() {
        stack = new ArrayDeque<>();
    }

    private BerDecoder(byte[] ba) {
        in = new BufferedInputStream(new ByteArrayInputStream(ba), ba.length + 2);
        stack = new ArrayDeque<>();
        size = 0;
        try {
            size = in.available();
        } catch (Exception e) {
            log.error("unhandled exception", e);
        }
        stack.push(0);
        stack.push(size);
    }

    private static boolean eval(int tClass, int xClass, int tValue, int xValue) {
        if (tClass != xClass) return (false);

        if (tClass == TagClass.APPLICATION.getValue() || tClass == TagClass.PRIVATE.getValue()) return (tValue == xValue);

        if (tValue == xValue) return (true);

        if (xValue > 0x20) // compare unstructured values
            xValue -= 0x20;

        // equate PrintableString, IA5String and T61_STRING
        if (xValue == UniversalTag.PRINTABLE_STRING.getValue() || xValue == UniversalTag.IA5_STRING.getValue() || xValue == UniversalTag.T61_STRING.getValue())
            return (tValue == UniversalTag.PRINTABLE_STRING.getValue() || tValue == UniversalTag.IA5_STRING.getValue() || tValue == UniversalTag.T61_STRING.getValue());

        // equate SEQUENCE, SEQUENCE OF, SET and SET OF
        if (xValue == UniversalTag.SEQUENCE.getValue() || xValue == UniversalTag.SET.getValue())
            return (tValue == UniversalTag.SEQUENCE.getValue() || tValue == UniversalTag.SET.getValue());

        return (false);
    }

    private static Date toDate(byte[] buffer) throws Exception {
        int limit = getLimit(buffer);
        int YY = (buffer[0] - '0') * 10 + (buffer[1] - '0');
        int MM = (buffer[2] - '0') * 10 + (buffer[3] - '0') - 1;
        int DD = (buffer[4] - '0') * 10 + (buffer[5] - '0');
        int hh = (buffer[6] - '0') * 10 + (buffer[7] - '0');
        int mm = (buffer[8] - '0') * 10 + (buffer[9] - '0');
        YY += YY <= 50 ? 2000 : 1900; // fails for 2051 and later
        Date result = null;
        Calendar cal;
        int ss;
        switch (limit) {
            case 11:
                cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                cal.set(YY, MM, DD, hh, mm);
                result = cal.getTime();
                break;
            case 13:
                cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                ss = (buffer[10] - '0') * 10 + (buffer[11] - '0');
                cal.set(YY, MM, DD, hh, mm, ss);
                result = cal.getTime();
                break;
            case 15:
                cal = Calendar.getInstance();
                cal.set(YY, MM, DD, hh, mm);
                hh = (buffer[11] - '0') * 10 + (buffer[12] - '0');
                mm = (buffer[13] - '0') * 10 + (buffer[14] - '0');
                mm += hh * 60;
                if (buffer[10] == '+')
                    cal.add(Calendar.MINUTE, mm);
                else
                    cal.add(Calendar.MINUTE, -mm);
                result = cal.getTime();
                break;
            case 17:
                cal = Calendar.getInstance();
                ss = (buffer[10] - '0') * 10 + (buffer[11] - '0');
                cal.set(YY, MM, DD, hh, mm, ss);
                hh = (buffer[13] - '0') * 10 + (buffer[14] - '0');
                mm = (buffer[15] - '0') * 10 + (buffer[16] - '0');
                mm += hh * 60;
                if (buffer[12] == '+')
                    cal.add(Calendar.MINUTE, mm);
                else
                    cal.add(Calendar.MINUTE, -mm);
                result = cal.getTime();
        }
        return result;
    }

    private static int getLimit(byte[] buffer) throws Exception {
        int limit = buffer.length;
        if ((limit != 11) && (limit != 13) && (limit != 15) && (limit != 17))
            throw new Exception("Invalid UTC_TIME format");
        if (limit == 11 && buffer[10] != 'Z') throw new Exception("Invalid UTC_TIME format");
        if (limit == 13 && buffer[12] != 'Z') throw new Exception("Invalid UTC_TIME format");
        if (limit == 15) throw new Exception("Invalid UTC_TIME format");
        if (limit == 17) throw new Exception("Invalid UTC_TIME format");
        return limit;
    }

    private static Date toFullDate(byte[] buffer) throws Exception {
        int limit = buffer.length;
        if (limit < 13) throw new Exception("Invalid GENERALIZED_TIME format");
        int YY = (buffer[0] - '0') * 1000 + (buffer[1] - '0') * 100 + (buffer[2] - '0') * 10 + (buffer[3] - '0');
        int MM = (buffer[4] - '0') * 10 + (buffer[5] - '0') - 1;
        int DD = (buffer[6] - '0') * 10 + (buffer[7] - '0');
        int hh = (buffer[8] - '0') * 10 + (buffer[9] - '0');
        int mm = (buffer[10] - '0') * 10 + (buffer[11] - '0');
        Calendar cal;
        int ss = 0;
        int ms = 0;
        int precision = 0;
        int b;
        boolean millis = false;
        if (buffer[limit - 1] == 'Z') {
            for (int i = 12; i < limit - 1; ) {
                b = buffer[i++] & 0xFF;
                if (b == '.')
                    millis = true;
                else if (!millis)
                    ss = ss * 10 + (b - '0');
                else if (precision++ < 3) ms = ms * 10 + (b - '0');
            }

            cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.set(YY, MM, DD, hh, mm, ss);
            cal.set(Calendar.MILLISECOND, ms);
        } else {
            int i = 12;
            while (buffer[i] != '+' && buffer[i] != '-') {
                b = buffer[i++] & 0xFF;
                if (b == '.')
                    millis = true;
                else if (!millis)
                    ss = ss * 10 + (b - '0');
                else if (precision++ < 3) ms = ms * 10 + (b - '0');
            }

            cal = Calendar.getInstance();
            cal.set(YY, MM, DD, hh, mm, ss);
            cal.set(Calendar.MILLISECOND, ms);
            boolean toSubtract = (buffer[i++] == '-');
            if ((limit - i) != 4) throw new Exception();

            hh = (buffer[i++] - '0') * 10 + (buffer[i++] - '0');
            mm = (buffer[i++] - '0') * 10 + (buffer[i++] - '0');
            mm += hh * 60;
            if (toSubtract) mm *= -1;

            cal.add(Calendar.MINUTE, mm);
        }

        return cal.getTime();
    }

    private static String toOID(byte[] buffer) {
        StringBuilder sb = new StringBuilder();
        int length = buffer.length;
        int i = 0;
        if (--length >= 0) // first byte is special
        {
            int b = buffer[i++] & 0xFF;
            int first = (b < 40 ? 0 : (b < 80 ? 1 : 2));
            int second = (b - first * 40);
            sb.append(first).append('.').append(second);
        }

        while (length > 0) // handle the rest
        {
            sb.append('.');
            int sid = 0; // subid
            int b;
            do {

                b = buffer[i++] & 0xFF;
                sid = sid << 7 | (b & 0x7F);
            }
            while (--length > 0 && (b & 0x80) == 0x80);

            sb.append(sid);
        }

        return (sb.toString());
    }

    private static Boolean toBoolean(byte[] buffer) throws Exception {
        int length = buffer.length;
        if (length != 1) throw new Exception();

        return buffer[0] != 0x00;
    }

    private static void toNull(byte[] buffer) throws Exception {
        int length = buffer.length;
        if (length != 0) throw new Exception();
    }

    public void open(InputStream is) {
        if (in != null) {
            throw new IllegalStateException();
        }

        in = is instanceof BufferedInputStream ? (BufferedInputStream) is : new BufferedInputStream(is, 10240);
        size = 0;
        try {
            size = in.available();
        } catch (Exception e) {
            log.error("unhandled exception", e);
        }
        stack.push(size);
    }

    public String decodeObjectIdentifier() throws IOException {
        throw new IOException("Method Not Implemented");
    }


    public void decodeNull() throws Exception {
        byte[] buffer = readByteValue();
        toNull(buffer);
    }


    public Boolean decodeBoolean() throws Exception {
        byte[] buffer = readByteValue();
        return toBoolean(buffer);
    }

    public BigInteger decodeInteger() throws IOException {
        byte[] buffer = readByteValue();
        return buffer.length > 0 ? new BigInteger(buffer) : new BigInteger(1, buffer);
    }


    public String decodeString(int tagValue) throws IOException {
        byte[] buffer = readByteValue();
        return new String(buffer, StandardCharsets.UTF_8);
    }

    public byte[] decodeBitString() throws IOException {
        byte[] tmp = readByteValue();
        byte[] result = new byte[tmp.length - 1];
        System.arraycopy(tmp, 1, result, 0, result.length);
        return result;
    }

    public byte[] decodeOctetString() throws IOException {
        return readByteValue();
    }

    public Date decodeUTCTime() throws Exception {
        byte[] buffer = readByteValue();
        return toDate(buffer);
    }

    public Date decodeGeneralizedTime() throws Exception {
        byte[] buffer = readByteValue();
        return toFullDate(buffer);
    }

    public ASNReader decodeStructure() throws IOException {
        readTagIgnoreFiller(0xFF);
        in.mark(64);
        int i1 = in.available();
        int len = readLength();
        stack.pop();
        int i2 = in.available();
        in.reset();
        int diff = i1 - i2;
        byte[] result = new byte[len + diff];

        int actualLength = in.read(result);

        if (actualLength == -1) throw new EOFException();

        if (actualLength != len + diff) throw new IOException(", len:" + actualLength + ", len:" + len + diff);

        return (new BerDecoder(result));
    }

    public void decodeSeqOf(ASNReader is, LinkedHashMap<String, Object> e1) throws Exception {
        if (is.isEndRecord())
            return;
        Tag tempTag = readTag();
        boolean isEndRecord;
        ArrayList<Object> values = new ArrayList<>();
        do {
            ASNReader rdr = readValue();
            LinkedHashMap<String, Object> e = new LinkedHashMap<>();
            decodeSeqOf(rdr, e);
            if (e.values().toArray().length != 0) {
                values.add(e.values().toArray()[0]);
            }
            if (is.isEndRecord()) {
                isEndRecord = true;
                break;
            }
            tempTag = readTag();
        }
        while (true);
        if (!isEndRecord) {
            // Handle error
        }
    }

    @Override
    public int read() throws IOException {
        int result = in.read();
        if (result == -1) {
            throw new EOFException();
        }
        return (result & 0xFF);
    }

    public void close() throws IOException {
        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                log.error("unhandled exception", e);
            }
            in = null;
        }
    }

    public void mark(int readlimit) {
        in.mark(readlimit);
    }

    public void reset() throws IOException {
        in.reset();
    }


    public boolean markSupported() {
        return in.markSupported();
    }


    public Tag readTag() throws IOException {
        int byteCount = 0;
        int c = read();
        byteCount++;

        int tClass = c & 0xC0;
        boolean tConstructed = (c & 0x20) != 0;
        int tValue = c & 0x1F;
        if (tValue == 0x1F) // multiple bytes for tag number
        {

            c = read();
            byteCount++;
            tValue = c & 0x7F;
            int[] appArr = new int[4];
            appArr[0] = tValue;

            int i = 1;
            while ((c & 0x80) != 0) {
                c = read();
                byteCount++;
                tValue = c & 0x7F;
                appArr[i++] = tValue;
            }

            try {
                tValue = calculateAppNumber(appArr, i);
            } catch (Exception e) {
                log.error("unhandled exception", e);
            }
        }
        Tag result = new Tag(TagClass.fromValue(tClass), tValue, true, tConstructed);
        int p = stack.pop();
        stack.push(p - byteCount);
        return result;
    }

    public Tag readTagIgnoreFiller(int filler) throws IOException {
        int byteCount = 0;
        int c = read();
        byteCount++;
        while (c == filler) {
            c = read();
            byteCount++;
        }

        int tClass = c & 0xC0;
        boolean tConstructed = (c & 0x20) != 0;
        int tValue = c & 0x1F;
        if (tValue == 0x1F) // multiple bytes for tag number
        {
            c = read();
            byteCount++;

            tValue = c & 0x7F;
            int[] appArr = new int[4];
            appArr[0] = tValue;

            int i = 1;
            while ((c & 0x80) != 0) {
                c = read();
                byteCount++;
                tValue = c & 0x7F;
                appArr[i++] = tValue;
            }

            try {
                tValue = calculateAppNumber(appArr, i);
            } catch (Exception e) {
                log.error("unhandled exception", e);
            }
        }

        Tag result = new Tag(TagClass.fromValue(tClass), tValue, true, tConstructed);
        int p = stack.pop();
        stack.push(p - byteCount);
        return result;
    }

    private static int calculateAppNumber(int[] number, int octet) {
        int s2power = 1;
        int result = 0;

        for (int i = 0; i < octet; i++) {
            String binStr = Integer.toBinaryString(number[octet - i - 1]);

            HashMap<String, Integer> hm = calculateInt(binStr, s2power, result);
            Integer keep = hm.get("POWER");
            int e2power = keep;

            keep = hm.get("RESULT");
            result = keep;

            s2power = e2power;
        }
        return result;
    }

    private static HashMap<String, Integer> calculateInt(String p_binStr, int p_2power, int p_result) {
        int sum = p_result;
        int strLen = p_binStr.length();
        int _2power = p_2power;

        for (int i = 0; i < 7; i++)
        {
            if (i < strLen) {
                int bitvalue = p_binStr.charAt(strLen - i - 1);
                if (bitvalue == 48)
                    bitvalue = 0;
                else
                    bitvalue = 1;

                sum += (bitvalue * _2power);
                _2power *= 2;
            } else
                _2power *= 2;
        }
        HashMap<String, Integer> ret = new HashMap<>();
        ret.put("POWER", _2power);
        ret.put("RESULT", sum);
        return ret;
    }

    public int readLength() throws IOException {
        int byteCount = 0;

        int limit = read();
        byteCount++;

        int result;
        if ((limit & 0x80) == 0) {
            result = limit;
        } else if (limit == 0x80) { //NON-definite Length encoding
            stack.push(-1);
            return -1;
        } else {
            limit &= 0x7F;
            if (limit > 4)
                throw new IOException();

            result = 0;
            while (limit-- > 0) {
                result = (result << 8) | (read() & 0xFF);
                byteCount++;
            }
        }
        int p = stack.pop();
        stack.push(p - byteCount - result);
        stack.push(result);
        return result;
    }

    @Override
    public ASNReader readValue() throws Exception {
        this.tagValueLength = readLength();
        return this;
    }

    public byte[] readByteValue() throws IOException {
        int length = stack.pop();

        byte[] result = new byte[length];
        int actualLength = in.read(result);
        if (actualLength == -1)
            throw new EOFException();

        if (actualLength != length)
            throw new IOException("actualLength:" + actualLength + ",length:" + length);

        return (result);
    }

    @Override
    public boolean isEOF() throws IOException {
        return in.available() <= 0;
    }

    @Override
    public boolean isEndRecord() {
        int p = stack.pop();
        if (p < 0) // NON-definite Length encoding
        {
            try {
                in.mark(16);
                int i1 = in.read();
                int i2 = in.read();
                if ((i1 == 0x0) && (i2 == 0x0)) // EOR for NON-definite Length encoding
                {
                    int x = stack.pop();
                    x = x + p - 2;
                    stack.push(x);
                    return true;
                } else {
                    in.reset();
                    stack.push(p);
                    return false;
                }
            } catch (Exception e) {
                log.error("unhandled exception", e);
                return true;
            }
        } else if (p == 0) // end of record for definite Length encoding
        {
            return true;
        } else {
            stack.push(p);
            return false;
        }
    }

    @Override
    public int getOffset() {
        return size - stack.stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public void ps() {
        System.out.println(stack);
    }

    @Override
    public int getTagValueLength() {
        return tagValueLength;
    }

    @Override
    public boolean skipOffset(int offset) throws IOException {
        int x = stack.pop();
        stack.push(0);
        long skippedBytes = this.in.skip(x);
        return skippedBytes > 0;
    }

    @Override
    public void skipOffset() throws IOException {
        while (stack.size() > 1) {
            int x = stack.pop();
            if (x > 0) {
                stack.push(0);
                long skippedBytes = this.in.skip(x);
                if (skippedBytes <= 0) break;
            }
        }

        int offset = getOffset();
        int inputStreamOffset = size - in.available();

        if (offset != inputStreamOffset) {
            long skipped = this.in.skip(offset - inputStreamOffset);
            if (skipped <= 0) {
                // Handle error
            }
        }
    }
}
