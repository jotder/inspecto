package com.gamma.skybase.decoder.asn3;

import java.util.List;

public class TLVNode {

    public final ASNTag tag;
    public final int length;

    public byte[] value;
    public List<TLVNode> children;

    public long startOffset;
    public long endOffset;

    public TLVNode(ASNTag tag, int length) {
        this.tag = tag;
        this.length = length;
    }

    public ASNTag getTag() {
        return tag;
    }

    public int getLength() {
        return length;
    }

    public void setValue(byte[] value) {
        this.value = value;
    }

    public void setChildren(List<TLVNode> children) {
        this.children = children;
    }

    public void setStartOffset(long offset) {
        this.startOffset = offset;
    }

    public void setEndOffset(long offset) {
        this.endOffset = offset;
    }

    @Override
    public String toString() {
        return "TAG:: " + tag + "\tlength: " + length + (children != null ? "\n Children: " + children : "");
    }
}
