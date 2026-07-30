package com.gamma.skybase.decoder.asn2;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a node in an ASN.1 tree structure, corresponding to a TLV (Tag-Length-Value).
 * It can contain other ASNNode objects, forming a tree.
 */
public class ASNNode {
    private final ASNTag tag;
    private final int length;
    private final byte[] value;
    private final List<ASNNode> children = new ArrayList<>();
    private String name;

    /**
     * Constructs an ASNNode.
     *
     * @param tag    The ASN.1 tag of this node.
     * @param length The length of the value part.
     * @param value  The raw byte value of this node.
     */
    public ASNNode(ASNTag tag, int length, byte[] value) {
        this.tag = tag;
        this.length = length;
        this.value = value;
        this.name = tag.getTypeName(); // Default name is the tag type name
    }

    public ASNTag getTag() {
        return tag;
    }

    public int getLength() {
        return length;
    }

    public byte[] getValue() {
        return value;
    }

    public List<ASNNode> getChildren() {
        return children;
    }

    public void addChild(ASNNode child) {
        children.add(child);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "ASNNode{" +
                "name='" + name + '\'' +
                ", tag=" + tag +
                ", length=" + length +
                ", children=" + children.size() +
                '}';
    }
}
