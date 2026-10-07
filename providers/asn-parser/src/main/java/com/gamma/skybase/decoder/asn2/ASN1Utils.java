package com.gamma.skybase.decoder.asn2;

import java.io.BufferedInputStream;
import java.io.IOException;

/**
 * A utility class for handling common ASN.1 parsing operations, such as reading tags and lengths.
 * This class cannot be instantiated.
 */
public final class ASN1Utils {

    private ASN1Utils() {
        // This is a utility class and should not be instantiated
    }

    /**
     * Reads an ASN.1 tag from the given reader.
     *
     * @param reader The reader to read from.
     * @return The parsed ASNTag.
     * @throws IOException if an I/O error occurs.
     */
    public static ASNTag readTag(TLReader reader) throws IOException {
        int c = reader.read();

        while (c == 0xFF || c == 0x00)
            c = reader.read();

        int tClass = c & 0xC0;
        boolean isConstructed = (c & 0x20) != 0;
        int tValue = c & 0x1F;

        if (tValue == 0x1F) {
            tValue = 0;
            do {
                c = reader.read();
                tValue = (tValue << 7) | (c & 0x7F);
            } while ((c & 0x80) != 0);
        }

        return new ASNTag(tClass, tValue, isConstructed);
    }

    /**
     * Reads an ASN.1 length from the given reader.
     *
     * @param reader The reader to read from.
     * @return The parsed length, or -1 for indefinite length.
     * @throws IOException if an I/O error occurs.
     */
    public static int readLength(TLReader reader) throws IOException {
        int firstByte = reader.read();
        if (firstByte == -1)
            throw new IOException("Unexpected EOF while reading length");

        // Short form
        if ((firstByte & 0x80) == 0)
            return firstByte;

        // Indefinite
        if (firstByte == 0x80)
            return -1;

        // Long form
        int numBytes = firstByte & 0x7F;

        if (numBytes == 0)
            throw new IOException("Invalid length encoding");

        if (numBytes > 4)
            throw new IOException("Length too large for int");

        int length = 0;

        for (int i = 0; i < numBytes; i++) {
            int b = reader.read();
            if (b == -1)
                throw new IOException("EOF inside length bytes");

            length = (length << 8) | b;
        }

        return length;
    }

}
