package com.gamma.skybase.decoder.asn2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The main reader for ASN.1 encoded data streams. This class reads tag-length-value (TLV) structures,
 * handles indefinite-length encoding, skips padding, and parses records based on a provided {@link TagDefinitionProvider}.
 */
public class ASN1Reader implements TLReader {
    private static final Logger logger = LoggerFactory.getLogger(ASN1Reader.class);
    private final BufferedInputStream bis;
    private final TagDefinitionProvider conf;
    private final Map<String, Object> fileStruct;
    private long offset = 0;
    private long recCount = 0;
    private long paddingByte = 255;
    private boolean hasPadding = false;
    private boolean streamBroken = false;
    int bytesToSkip = 0;

    /**
     * Constructs an ASN1Reader.
     *
     * @param in         The input stream to read ASN.1 data from.
     * @param conf       The tag definition provider to use for decoding.
     * @param fileStruct A map defining the structure of the file, including headers and padding.
     */
    public ASN1Reader(InputStream in, TagDefinitionProvider conf, Map<String, Object> fileStruct) {
        this.conf = conf;
        this.fileStruct = fileStruct;
        this.bis = new BufferedInputStream(in);

        // Configure padding byte, default to 0xFF if not specified
//        this.paddingByte = Integer.parseInt(fileStruct.getOrDefault("PADDING_BYTE", "255").toString());
        Object headerLength = fileStruct.get("HEADER_LENGTH");
        if ((headerLength != null) & (headerLength instanceof Long))
            readFileHeader(Math.toIntExact(Long.parseLong(headerLength.toString())));

        Object headerLengthValue = fileStruct.get("RECORD_HEADER_LENGTH");
        if (headerLengthValue != null)
            bytesToSkip = Integer.parseInt(headerLengthValue.toString());

        Object hasPad = fileStruct.get("HAS_PADDING");
        if (hasPad != null)
            hasPadding = hasPad.toString().trim().equalsIgnoreCase("true");

        Object pad = fileStruct.get("PADDING_BYTE");
        if (pad != null)
            paddingByte = Math.toIntExact(Integer.parseInt(pad.toString()));
    }

    /**
     * Reads or skips a file header based on the configuration provided in `fileStruct`.
     */
    private void readFileHeader(int bytesToSkip) {
        try {
            if (bytesToSkip > 0) {
                long skipped = skipFully(bytesToSkip);
                offset += skipped;
            }
        } catch (IOException e) {
//            logger.error("Failed to read or skip file header", e);
            throw new UncheckedIOException(e);
        } catch (NumberFormatException e) {
//            logger.error("Invalid FILE_HEADER_LENGTH specified: '{}'. Must be a number.", bytesToSkip);
        }
    }

    /**
     * Reads or skips a record header based on the configuration provided in `fileStruct`.
     */
    private void readRecordHeader() {
        try {
            long skipped = skipFully(bytesToSkip);
            offset += skipped;
        } catch (IOException e) {
//            logger.error("Failed to read or skip record header", e);
            throw new UncheckedIOException(e);
        } catch (NumberFormatException e) {
//            logger.error("Invalid RECORD_HEADER_LENGTH specified: '{}'. Must be a number.", bytesToSkip);
        }
    }

    /**
     * Skips exactly n bytes, looping because BufferedInputStream.skip may skip
     * fewer bytes than requested (e.g. at an internal buffer boundary).
     */
    private long skipFully(long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = bis.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else {
                if (bis.read() == -1)
                    break; // EOF
                remaining--;
            }
        }
        return n - remaining;
    }

    @Override
    public boolean hasNext() throws IOException {
        if (streamBroken)
            return false;
        if (hasPadding)
            skipPadding();
        return bis.available() > 0;
    }

    /**
     * Skips any padding bytes at the current position in the stream.
     */
    private void skipPadding() throws IOException {
        bis.mark(1);
        while (bis.available() > 0 && bis.read() == paddingByte) {
            offset++;
            bis.mark(1);
        }
        bis.reset();
    }

    /**
     * Reads and decodes the next complete ASN.1 record from the stream.
     *
     * @return A map representing the decoded record. Returns an empty map if the end of the stream is reached.
     */
    @Override
    public LinkedHashMap<String, Object> next() throws Exception {

        if (bytesToSkip > 0)
            readRecordHeader();
        if (hasPadding)
            skipPadding();

        Map<String, Object> rawData = readNextRecord();
        if (rawData == null || rawData.isEmpty()) {
            return new LinkedHashMap<>();
        }
        recCount++;
        return new LinkedHashMap<>(rawData);
    }

    public int readLength() throws IOException {

        int firstByte = read();
//        System.out.println(Integer.toHexString(firstByte));
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
            int b = read();
            if (b == -1)
                throw new IOException("EOF inside length bytes");

            length = (length << 8) | b;
        }

        return length;
    }

    public ASNTag readTag() throws IOException {

        int firstByte = read();

//        System.out.println(Integer.toHexString(firstByte));

        if (firstByte == -1)
            throw new IOException("Unexpected EOF while reading tag");

        int tagClass = firstByte & 0xC0;
        boolean constructed = (firstByte & 0x20) != 0;
        int tagNumber = firstByte & 0x1F;

        // Long-form tag number
        if (tagNumber == 0x1F) {
            tagNumber = 0;
            int b;
            do {
                b = read();
                if (b == -1)
                    throw new IOException("EOF in long-form tag");
                tagNumber = (tagNumber << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0);
        }

        return new ASNTag(tagClass, tagNumber, constructed);
    }


    /**
     * Reads the next raw ASN.1 record (TLV) and parses it into a nested map structure.
     *
     * @return A map representing the raw ASN.1 record, or null on EOF.
     */
    private Map<String, Object> readNextRecord() {
        long recordOffset = this.offset;
        ASNTag asnTag;
        byte[] value;
        boolean definiteLength;
        try {
            bis.mark(Integer.MAX_VALUE); // Mark stream to allow reset on error

            asnTag = ASN1Utils.readTag(this);
            int length = ASN1Utils.readLength(this);

            definiteLength = length != -1;
            if (definiteLength) {
                if (length > bis.available()) {
//                    logger.warn("Tag {} at offset {} reports length {} which is larger than remaining stream size {}. Skipping record.", asnTag, recordOffset, length, bis.available());
                    return null;
                }
                value = new byte[length];
                read(value);
            } else { // Indefinite length
                value = readUntilEndOfContent();
            }
        } catch (EOFException e) {
//            logger.warn("Reached end of data stream cleanly." + offset);
            return null;
        } catch (IOException e) {
            // Tag/length could not be read, so the record boundary is unknown and
            // there is no reliable way to re-sync. Stop processing the file.
//            logger.warn("Invalid Bytes read at Offset: " + offset + "  , RecCount : " + getRecCount());
            e.printStackTrace();
            streamBroken = true;
            try {
                bis.reset(); // Reset to the start of the bad record for diagnostics
                offset = recordOffset; // Rewind offset to match the stream position
            } catch (IOException resetException) {
//                logger.warn("Failed to reset stream after read error.", resetException);
            }
            return null;
        }

        // The record body is fully consumed at this point, so a parse failure
        // must not reset the stream: it is already positioned at the next record.
        try {
            String tagVal = String.valueOf(asnTag.gettValue());
            TagReader tr = new TagReader(tagVal, value, conf, recordOffset);
            return tr.parse(tagVal, definiteLength);
        } catch (Exception e) {
//            logger.warn("Failed to parse record at Offset: " + recordOffset + "  , RecCount : " + getRecCount());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public int read() throws IOException {
        int result = bis.read();
        if (result == -1) throw new EOFException();
        offset++;
        return result;
    }

    @Override
    public void read(byte[] buffer) throws IOException {
        int bytesToRead = buffer.length;
        int totalBytesRead = 0;
        while (totalBytesRead < bytesToRead) {
            int bytesRead = bis.read(buffer, totalBytesRead, bytesToRead - totalBytesRead);
            if (bytesRead == -1) {
                break; // End of stream
            }
            totalBytesRead += bytesRead;
        }

        if (totalBytesRead != bytesToRead) {
            throw new EOFException("Expected to read " + bytesToRead + " bytes, but only " + totalBytesRead + " were read before the stream ended.");
        }
        offset += totalBytesRead;
    }

    /**
     * Reads from the stream until the top-level End-Of-Content (EOC) marker is found.
     * This method correctly handles nested indefinite-length structures by tracking the nesting level.
     *
     * @return The byte array of the content, excluding the final EOC marker.
     * @throws IOException if the end of the stream is reached before the marker is found.
     */
    private byte[] readUntilEndOfContent() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nestingLevel = 1;

        while (nestingLevel > 0) {
            long markOffset = offset;
            bis.mark(3); // Mark for tag and length reading
            int b1 = read();
            buffer.write(b1);

            // Check for EOC marker
            if (b1 == 0x00) {
                int b2 = read();
                buffer.write(b2);
                if (b2 == 0x00) {
                    nestingLevel--;
                    if (nestingLevel == 0) {
                        // Remove the final EOC from the buffer
                        byte[] result = buffer.toByteArray();
                        return java.util.Arrays.copyOf(result, result.length - 2);
                    }
                    continue;
                }
            }

            // It's not an EOC, so it must be a tag. Read its length.
            bis.reset();
            offset = markOffset; // Rewind offset to match the stream position
            read(); // Re-read the first byte of the tag
            ASNTag tag = ASN1Utils.readTag(this);
            int length = ASN1Utils.readLength(this);

            if (length == -1) {
                nestingLevel++;
            }else {
                // For definite length, just skip the content bytes
                long skipped = skipFully(length);
                if (skipped != length) {
                    throw new EOFException("Stream ended prematurely while skipping definite-length content.");
                }
            }
        }
        throw new IOException("Could not find end-of-content marker for indefinite length tag.");
    }

    @Override
    public void close() throws IOException {
        if (bis != null) {
            bis.close();
        }
    }

    @Override
    public String skipUntil(int[] tags) {
        throw new UnsupportedOperationException("skipUntil is not supported in ASN1Reader.");
    }

    @Override
    public String skipFiller(int filler) {
        throw new UnsupportedOperationException("skipFiller is not supported in ASN1Reader.");
    }

    public long getRecCount() {
        return recCount;
    }
}
