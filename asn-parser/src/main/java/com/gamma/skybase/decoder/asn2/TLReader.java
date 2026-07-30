package com.gamma.skybase.decoder.asn2;

import java.io.IOException;
import java.util.Map;

/**
 * Defines the contract for a generic Tag-Length-Value (TLV) reader, used in ASN.1 parsing.
 * Implementations of this interface are responsible for reading and processing structured data from a stream or byte array.
 */
interface TLReader {

    /**
     * Checks if there is more data to be read.
     *
     * @return true if there is more data, false otherwise.
     * @throws IOException if an I/O error occurs.
     */
    boolean hasNext() throws IOException;

    /**
     * Reads and processes the next logical data record.
     *
     * @return A map representing the processed record, or an empty map if no data is available.
     */
    Map<String, Object> next() throws Exception;

    /**
     * Reads the next single byte of data.
     *
     * @return the next byte of data, or -1 if the end of the stream is reached.
     * @throws IOException if an I/O error occurs.
     */
    int read() throws IOException;

    /**
     * Reads a block of bytes into the provided buffer.
     *
     * @param buffer the buffer into which the data is read.
     * @throws IOException if an I/O error occurs or if the buffer cannot be filled.
     */
    void read(byte[] buffer) throws IOException;

    /**
     * Closes the reader and releases any system resources associated with it.
     *
     * @throws IOException if an I/O error occurs.
     */
    void close() throws IOException;

    /**
     * Skips bytes until one of the specified tag bytes is found.
     *
     * @param tags an array of tag byte values to search for.
     * @return a string representation of the skipped bytes (typically for debugging).
     * @throws IOException if an I/O error occurs.
     */
    String skipUntil(int[] tags) throws IOException;

    /**
     * Skips a sequence of repeating filler bytes.
     *
     * @param filler the byte value of the filler to skip.
     * @return a string representation of the skipped filler bytes (typically for debugging).
     * @throws IOException if an I/O error occurs.
     */
    String skipFiller(int filler) throws IOException;
}
