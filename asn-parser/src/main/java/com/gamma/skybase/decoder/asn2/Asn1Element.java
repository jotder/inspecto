package com.gamma.skybase.decoder.asn2;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class Asn1Element {

    public String name;
    public String elementType; //User defined intermediate Type
    public String tagNo;        // null if not present
    boolean constructed;
    public String containerType;  // SEQUENCE, SET, CHOICE, null if not container
    List<Asn1Element> children = new ArrayList<>();


    public Asn1Element(String name, String tagNo, boolean constructed, String containerType, String elementType) {
        this.name = name;
        this.tagNo = tagNo;
        this.constructed = constructed;
        if ("ENUMERATED".equalsIgnoreCase(elementType))
            elementType = "INTEGER";
        this.elementType = elementType;
        this.containerType = containerType;

    }

    public void addChild(Asn1Element child) {
        children.add(child);
    }

    public List<Asn1Element> getChildren() {
        return children;
    }

    public String getTransTemplate(String pad) {
        String comments = "        \t\t \"@comment\": \" " + containerType + " " + elementType + "\"\n";
        switch (containerType) {
            case "SEQUENCE OF":
            case "SET OF":
                String xOf = pad + "\"" + name + "\": {\n";
                xOf +=  pad + "\t \"@comment\": \"Contains sub record: '" + elementType + "', define joning strategy\"\n";
                xOf +=  pad + "}";
//              System.out.println(xOf);
                return xOf;
            case "CHOICE":
            case "SEQUENCE":
            case "SET":
                String t3 = pad + "\"" + name + "\": {\n";
                t3 += pad + "\t \"@comment\": \"Define strategy to accept nested fields - <key,val> pairs \"\n";
                t3 += pad + "}";
//              System.out.println(t3);
                return t3;
            default:
                return "\t\"" + name + "\": {}";
        }
    }

    public String toString() {
//            String pad = "  ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        sb.append(tagNo).append("        \t")
                .append(name);
        if (!containerType.isEmpty())
            sb.append("  \t\t'").append(containerType).append('\'');
        if (!containerType.isEmpty())
            sb.append(" ").append(elementType);
        if (!children.isEmpty())
            sb.append("\t-- size: ").append(children.size());
        return sb.toString();
    }

    public boolean isSeqOf() {
        return "SEQUENCE OF".equals(containerType);
    }

    public boolean isSetOf() {
        return "SET OF".equals(containerType);
    }

    public boolean isSeqOrSetOf() {
        return isSeqOf() || isSetOf();
    }

    public boolean isSeqOrSet() {
        return "SEQUENCE".equals(containerType) || "SET".equals(containerType);
    }

    public boolean isChoice() {
        return "CHOICE".equals(containerType);
    }

    public boolean hasTagNo() {
        return !tagNo.isEmpty();
    }

    public boolean isEnumerated() {
        return "ENUMERATED".equals(containerType);
    }

    public List<Asn1Element> getMemberTags() {
        return children;
    }

    public Asn1Element dup() {
        Asn1Element t = new Asn1Element(name, tagNo, constructed, containerType, elementType);
        this.children.forEach(x -> t.children.add(x.dup()));
        return t;
    }

    public Object decode(byte[] value) throws InvocationTargetException, IllegalAccessException {
        Method method = TagHelper.getDecodeMethod(elementType);
        return method.invoke(null, value);
    }

//    public String getTagNo() {
//        if ("SEQUENCE OF".equals(containerType))
//            return tagNo + ".16";
//        else if ("SET OF".equals(containerType))
//            return tagNo + ".17";
//        else
//            return tagNo;
//    }

    public void setTagNo(String no) {
        tagNo = no;
    }

}