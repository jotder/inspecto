package com.gamma.skybase.decoder.asn3;

import com.gamma.skybase.decoder.asn2.Asn1Element;
import com.gamma.skybase.decoder.asn2.DataDef;
import com.gamma.skybase.decoder.asn2.TagDefinitionProvider;


import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class ASNStreamReader {

    private final ByteSource source;
    Map<String, Object> fileStruct;
    private TagDefinitionProvider config;

    public ASNStreamReader(DataDef config, Map<String, Object> fileStruct, ByteSource source) {
        this.source = source;
        this.fileStruct = fileStruct;
        this.config = config;
    }

    public boolean hasNext() {
        return source.hasRemaining();
    }

    public long getOffset() {
        return source.position();
    }

    long recordCount;

    public Map<String, Object> next() {
        if (!hasNext()) return null;

        TLVNode root;
        try {
            root = readNextRecord();
            if (root == null) return null;
            Map<String, Object> record = new LinkedHashMap<>();
            getDecodedRecord(root, "", record);
//            System.out.println("\n\n" + record);
//            System.out.println("Record no:" + recordCount + "\t,POS-" + Long.toHexString(source.position()) + ":"
//                    + source.position());
            recordCount++;
            return record;
        } catch (IOException | InvocationTargetException | IllegalAccessException e) {
            e.printStackTrace();
        }
//        System.out.println("Pos-" + Long.toHexString(source.position()));
        return null;
    }

    private void getDecodedRecord(TLVNode root, String pTag, Map<String, Object> record) throws InvocationTargetException, IllegalAccessException {
        String tag;
        if (pTag.isEmpty()) {
            tag = root.getTag().getTagNumber() + "";
        } else
            tag = pTag + "." + root.getTag().getTagNumber();

        Asn1Element cnf = config.getTagNoConf().get(tag); // get schema
        String tagName = (cnf != null) ? cnf.name : tag; // if schema not found, use numbers like 1.2.3

        boolean isSeqOrSetOf = (cnf != null) && cnf.isSeqOrSetOf();

        if (root.children != null) {
            Map<String, Object> map = new LinkedHashMap<>();

            root.children.forEach(child -> {
                if (isSeqOrSetOf) {
                    Object o = record.get(tagName);
                    List<Object> list;
                    if (o == null)
                        list = new ArrayList<>();
                    else
                        list = (List<Object>) o;

                    try {
                        Map<String, Object> m = new LinkedHashMap<>();
                        getDecodedRecord(child, tag, m);
                        list.add(m);
                        record.put(tagName, list);
                    } catch (InvocationTargetException | IllegalAccessException e) {
                        e.printStackTrace();
                    }
                } else {
                    try {
                        getDecodedRecord(child, tag, map);
                    } catch (InvocationTargetException | IllegalAccessException e) {
                        e.printStackTrace();
                    }
                    record.put(tagName, map);
                }
//                System.out.print(tagName+ "->");
            });
        } else {
            if ((cnf != null))
                record.put(tagName, cnf.decode(root.value));
            else
                record.put(tagName, root.value);
        }
    }

    public TLVNode readNextRecord() throws IOException {
        while (hasNext()) {
            long startOffset = source.position();
            int firstByte = source.read();
            source.skip(-1); // rewind to let parseTLV handle it

            if (firstByte == 0x00) {
                // Skip 0x00 padding
                source.read();
                continue;
            }
            return parseTLV();
        }
        return null;
    }

    // ================= CORE PARSER ===========================
    int indefiniteDepth;

    private TLVNode parseTLV() {

        long startOffset = source.position();

        ASNTag tag = readTag();
        ASNLength length = null;
        try {
            length = readLength();
        } catch (IOException e) {
            e.printStackTrace();
        }
        TLVNode node = new TLVNode(tag, length.getLength());
        node.setStartOffset(startOffset);

        if (tag.isConstructed()) {

            if (length.isIndefinite()) {
                indefiniteDepth++;
                try {
                    parseIndefinite(node);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                indefiniteDepth--;

            } else {
                try {
                    parseConstructed(node, length.getLength());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        } else {
            node.setValue(source.readBytes(length.getLength()));
        }
//        if (node.children != null)
//            System.out.println();
        node.setEndOffset(source.position());
        return node;
    }

    // ================= TAG READER ============================

    private ASNTag readTag() {

        int firstByte = source.read();

        int tagClass = (firstByte & 0b11000000) >> 6;
        boolean constructed = (firstByte & 0b00100000) != 0;
        int tagNumber = firstByte & 0b00011111;

        // High-tag-number form
        if (tagNumber == 0x1F) {

            tagNumber = 0;
            int b;

            do {
                b = source.read();
                tagNumber = (tagNumber << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0);
        }

        return new ASNTag(tagClass, constructed, tagNumber);
    }

    // ================= LENGTH READER =========================
    public ASNLength readLength() throws IOException {

        int first = source.read();  // MUST be 0–255

        if (first == -1) {
            throw new EOFException("Unexpected EOF while reading length");
        }

        //  1. INDEFINITE (must be checked FIRST)
        if (first == 0x80) {
            return ASNLength.indefinite();
        }

        //  2. SHORT FORM
        if ((first & 0x80) == 0) {
            return ASNLength.definite(first);
        }

        //  3. LONG FORM
        int numBytes = first & 0x7F;

        if (numBytes == 0) {
            throw new IOException("Invalid length: 0x80 already handled as indefinite");
        }

        if (numBytes > 4) {
//            System.out.println("Error in pos: " + source.position() + ", Length too large: " + numBytes + " bytes, HEX Pos:"
//                    + Long.toHexString(source.position()) + " at record" + recordCount);
            throw new IOException("Error in pos: " + source.position() + "Length too large: " + numBytes + " bytes");
        }

        int length = 0;

        for (int i = 0; i < numBytes; i++) {
            int b = source.read();

            if (b == -1) {
                throw new EOFException("EOF in long-form length");
            }

            length = (length << 8) | (b & 0xFF);
        }

        return ASNLength.definite(length);
    }

    // ================= CONSTRUCTED ===========================

    private void parseConstructed(TLVNode parent, int length) throws IOException {

        long end = source.position() + length;

        List<TLVNode> children = new ArrayList<>();

        while (source.position() < end) {
            children.add(parseTLV());
        }

        parent.setChildren(children);
    }

    // ================= INDEFINITE ============================

    private void parseIndefinite(TLVNode parent) throws IOException {

        List<TLVNode> children = new ArrayList<>();

        while (true) {

            long before = source.position();

            ASNTag tag = readTag();

            // Detect EOC properly
            if (tag.getTagNumber() == 0 && !tag.isConstructed()) {

                ASNLength len = readLength();

                if (!len.isIndefinite() && len.getLength() == 0) {
                    // ✔ TRUE EOC
                    break;
                } else {
                    // If we encounter 00 XX (where XX != 00), it's not a valid EOC.
                    // But to support "dump without grammar", we treat it as a regular node (Tag 0).
                    // We rewind and let parseTLV handle it as a primitive node.
                    source.skip(-(source.position() - before));
                    children.add(parseTLV());
                    continue;
                }
            }

            // rewind fully before parsing full TLV
            source.skip(-(source.position() - before));

            children.add(parseTLV());
        }

        parent.setChildren(children);
    }

    // read tag
    // read length
    // if indefinite
    //      loop until get 00 00
    //      increase nesting level ++
    //      read tag
    //      read length
    //      tlv()
    //
    // ================= RECOVERY ==============================

    public boolean recover() {

        while (source.hasRemaining()) {

            long saved = source.position();

            try {
                parseTLV();
                return true;
            } catch (Exception e) {
                source.skip(1);
            }
        }

        return false;
    }
}
