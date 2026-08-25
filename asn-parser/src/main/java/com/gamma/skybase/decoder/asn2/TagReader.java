package com.gamma.skybase.decoder.asn2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * An internal reader responsible for parsing the byte array value of a constructed ASN.1 tag.
 * It operates on a given byte array (the value from a TLV) and recursively parses any nested TLV structures within it.
 * This class is not intended for direct use outside the ASN.1 decoding package.
 */
class TagReader implements TLReader {
    private static final Logger logger = LoggerFactory.getLogger(TagReader.class);
    private final String parentTag;
    private final byte[] data;
    private final TagDefinitionProvider conf;
    private final long fileOffset;
    private int offset = 0;

    /**
     * Constructs a TagReader to parse a byte array that is the value of a constructed tag.
     *
     * @param pTag       The fully qualified tag number of the parent tag.
     * @param value      The byte array to be parsed.
     * @param conf       The tag definition provider for resolving nested tags.
     * @param fileOffset The absolute file offset where this byte array begins, for logging purposes.
     */
    public TagReader(String pTag, byte[] value, TagDefinitionProvider conf, long fileOffset) {
        this.data = value;
        this.parentTag = pTag;
        this.fileOffset = fileOffset;
        this.conf = conf;
    }

    @Override
    public boolean hasNext() {
        return data.length > offset;
    }

    /**
     * This method is not applicable for TagReader as it processes a fixed byte array in a single pass via the {@link #parse} method.
     * This suggests a potential design mismatch where TagReader might not need to be a TLReader.
     *
     * @return An empty map.
     */
    @Override
    public Map<String, Object> next() {
        return Collections.emptyMap();
    }

    /**
     * Parses the byte array provided in the constructor into a map of raw tag-value pairs.
     *
     * @param pTag           The parent tag number to prepend to nested tags.
     * @param definiteLength True if the context is a definite-length encoding, false for indefinite-length.
     * @return A map representing the parsed data, with tag numbers as keys.
     * @throws IOException if a parsing or I/O error occurs.
     */
    public Map<String, Object> parse(String pTag, boolean definiteLength) throws IOException {
        Map<String, Object> record = new LinkedHashMap<>();
        while (hasNext()) {
            if (!definiteLength && isEndOfContentMarker())
                break;

            long tagOffset = fileOffset + offset;


            ASNTag asnTag = ASN1Utils.readTag(this);
            int length = ASN1Utils.readLength(this);

            if (length == 0)
                continue;

            String currentTag = pTag + "." + asnTag.gettValue();
            Asn1Element cnf = conf.getTagNoConf().get(currentTag);
            String tagName = (cnf != null) ? cnf.name : currentTag;

            if (length == -1) { // Indefinite length
                byte[] indefiniteData = readUntilEndOfContent();
                TagReader indefiniteReader = new TagReader(currentTag, indefiniteData, conf, tagOffset);
                Map<String, Object> subRecord = indefiniteReader.parse(currentTag, false);
                updateRecord(record, tagName, subRecord, cnf);
            } else {
                if (offset + length > data.length) {
//                    logger.warn("Improper length at tag {}, len {}, offset {}. Truncating to available {} bytes.", tagName, length, tagOffset, data.length - offset);
                    length = data.length - offset;
                }

                byte[] value = new byte[length];
                read(value);
                if (asnTag.isConstructed()) {
                    TagReader constructedReader = new TagReader(currentTag, value, conf, tagOffset);
                    Map<String, Object> subRecord = constructedReader.parse(currentTag, true);
                    if (cnf == null)
                        logger.debug("No config found for constructed tag: {}", currentTag);
                    updateRecord(record, tagName, subRecord, cnf);
                } else {
                    try {
                        if (cnf == null) {
                            logger.debug("No config found for primitive tag: {}. Storing as raw bytes.", currentTag);
                            updateRecord(record, tagName, value, null);
                        } else
                            updateRecord(record, tagName, cnf.decode(value), cnf);

                    } catch (InvocationTargetException | IllegalAccessException e) {
                        logger.error("Error decoding value for tag: {}", tagName, e);
                    } catch (Exception e) {
                        logger.error("An unexpected error occurred during decoding of tag: {}", tagName, e);
                    }
                }
            }
        }
        return record;
    }

    /**
     * Checks for and consumes the end-of-content marker (two zero bytes) for indefinite-length encoding.
     *
     * @return true if the marker was found and consumed, false otherwise.
     */
    private boolean isEndOfContentMarker() {
        if (offset + 2 <= data.length && data[offset] == 0x00 && data[offset + 1] == 0x00) {
            offset += 2;
            return true;
        }
        return false;
    }

    /**
     * Reads bytes from the internal buffer until an end-of-content marker is found for the current nesting level.
     * This method correctly handles nested indefinite-length tags by keeping a count of the nesting level.
     *
     * @return The byte array of the content, excluding the final EOC marker.
     * @throws IOException if the end of the buffer is reached before the marker is found.
     */
    private byte[] readUntilEndOfContent() throws IOException {
        int start = offset;
        int nestedLevel = 0;
        while (hasNext()) {
            if (isEndOfContentMarker()) {
                if (nestedLevel == 0) {
                    byte[] result = new byte[offset - start - 2];
                    System.arraycopy(data, start, result, 0, result.length);
                    return result;
                }
                nestedLevel--;
            } else {
                ASN1Utils.readTag(this); // Read tag to advance offset, required for correct parsing.
                int length = ASN1Utils.readLength(this);
                if (length == -1)
                    nestedLevel++;

                offset += Math.max(length, 0);
            }
        }
        throw new IOException("Could not find end-of-content marker for indefinite length tag starting at offset " + (fileOffset + start));
    }

    /**
     * Updates a record map with a new value for a given tag. If the tag already exists,
     * it converts the value into a list to hold multiple values.
     *
     * @param record  The record map to update.
     * @param tagPath The tag (key) for the value.
     * @param value   The value to add.
     * @param cnf     The configuration for the element, used to determine if it's a list type.
     */
    private void updateRecord(Map<String, Object> record, String tagPath, Object value, Asn1Element cnf) {
        if (value == null) {
//            logger.warn("Decoded value for tag '{}' is null. Skipping.", tagPath);
            return;
        }
        boolean eleType = cnf != null && cnf.isSeqOrSetOf();         // Default behavior based on schema
        // ToDo: This is a specific case for SEQUENCE OF/SET OF, change it with handling IMPLICIT tags, tag class 2
        if (cnf != null && "creditControlRecords".equalsIgnoreCase(cnf.name))
            eleType = true; // Force list for this specific tag, as it's a SEQUENCE OF
        // This block handles a specific case for SEQUENCE OF/SET OF where a single-element map needs to be wrapped in a list. This logic can be complex to maintain.
        if (eleType) {
            if (value instanceof Map) {
                Map<String, Object> val1 = (Map<String, Object>) value;
                if (val1.size() == 1) { // ideal case
                    val1.forEach((k, val2) -> {
                        if (val2 instanceof Map) {
                            List<Object> l = new ArrayList<>();
                            l.add(val2);
                            val1.put(k, l);
                        }
                    });
                }
            } else {
                // If the schema indicates a list but the value isn't one, wrap it.
                List<Object> l = new ArrayList<>();
                l.add(value);
                value = l;
            }
        }

        Object finalValue = value;
        record.compute(tagPath, (key, existingValue) -> {
            if (existingValue == null)
                return finalValue;

            if (existingValue instanceof List) {
                ((List<Object>) existingValue).add(finalValue);
                return existingValue;
            }
            List<Object> list = new ArrayList<>();
            list.add(existingValue);
            list.add(finalValue);
            return list;
        });
    }


    @Override
    public int read() throws IOException {
        if (!hasNext())
            throw new EOFException("End of data buffer reached.");

        return data[offset++] & 0xFF;
    }

    @Override
    public void read(byte[] buf) throws IOException {
        if (offset + buf.length > data.length)
            throw new EOFException("Not enough data to read " + buf.length + " bytes. Needed " + buf.length + ", but only " + (data.length - offset) + " available.");

        System.arraycopy(data, offset, buf, 0, buf.length);
        offset += buf.length;
    }

    @Override
    public void close() {
        // No resources to close for a byte array reader.
    }

    @Override
    public String skipFiller(int filler) {
        throw new UnsupportedOperationException("skipFiller is not supported in TagReader.");
    }

    @Override
    public String skipUntil(int[] tags) {
        throw new UnsupportedOperationException("skipUntil is not supported in TagReader.");
    }
}
