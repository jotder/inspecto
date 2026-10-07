package com.gamma.skybase.decoder.asn2.reader;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Optional;

public abstract class ASNReader extends InputStream implements Serializable {

    protected final SkipList skipList;

    protected ASNReader(SkipList skipList) {
        this.skipList = skipList;
    }

    protected ASNReader() {
        this.skipList = null;
    }

    public abstract void open(InputStream is);

    public ASNReader readValue() throws Exception {
        return null;
    }

    public int getOffset() {
        return -1;
    }

    public boolean isEOF() throws IOException {
        return false;
    }

    public boolean isEndWithFiller(int filler) {
        return false;
    }

    public boolean isEndRecord() {
        return false;
    }

    public Optional<SkipList> getSkipList() {
        return Optional.ofNullable(skipList);
    }

    public boolean shouldSkipTag(int id) {
        return skipList != null && skipList.contains(id);
    }

    public void ps() {
    }

    public abstract int getTagValueLength();

    public abstract boolean skipOffset(int offset) throws IOException;

    public abstract void skipOffset() throws IOException;
}
