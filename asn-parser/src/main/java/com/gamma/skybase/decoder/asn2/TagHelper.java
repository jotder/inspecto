package com.gamma.skybase.decoder.asn2;

import java.lang.reflect.Method;
import java.util.*;

public class TagHelper {

    static final Set<String> PRIMITIVE_TYPES = new HashSet<>(Arrays.asList(
            "INTEGER", "OCTET STRING", "OCTETSTRING", "HEX STRING", "HEXSTRING", "IA5STRING", "UTF8STRING", "PRINTABLESTRING",
            "NUMERICSTRING", "BIT STRING", "BOOLEAN", "NULL", "NULLTYPE", "ENUMERATED", "OBJECT IDENTIFIER",
            "CCNTIMESTAMP", "TIMESTAMP", "IPADDRESS", "CHARGINGCHARACTERISTICS", "IA5STRING", "ASCIISTRING", "GRAPHICSTRING",
            "TBCDSTRING", "TBCD-STRING", "BCDSTRING", "BITSTRING", "HEX STRING", "TBCD", "USERLOCATIONINFORMATION", "MSTIMEZONE", "PLMNID",
            "TOPDPTYPE" ,"TOIMEI"
    ));

    static final Set<String> UNV_CONTAINER_TYPE = new HashSet<>(Arrays.asList(
            "SEQUENCE", "SET", "CHOICE", "SEQUENCE OF", "SET OF"
    ));

    /**
     * Parses the field type (e.g., INTEGER, OCTET STRING) from a schema line.
     *
     * @param line The schema line.
     * @return The parsed field type.
     */
    private static String parseFieldType(String line) {
        if (line.toUpperCase().contains("OBJECT IDENTIFIER")) {
            return "OBJECT IDENTIFIER";
        }

        String fieldType;
        if (line.contains("]")) {
            fieldType = line.substring(line.indexOf(']') + 1).trim();
        } else if (line.contains("::=")) {
            fieldType = line.substring(line.indexOf("::=") + 3).trim();
        } else {
            String[] a = line.split(" ");
            fieldType = (a.length > 1) ? a[1] : null;
        }

        if (fieldType != null) {
            if (fieldType.contains("(")) {
                fieldType = fieldType.substring(0, fieldType.indexOf('(')).trim();
            }
            if (fieldType.contains("DEFAULT")) {
                fieldType = fieldType.substring(0, fieldType.indexOf("DEFAULT")).trim();
            }
        }
        return fieldType;
    }

    private static final Map<String, Method> decoderCache = new HashMap<>();
    private static final Set<String> DECODABLE_TYPES = new HashSet<>();

    //    method = clazz.getMethod(methodName, byte[].class);


    static {
        DECODABLE_TYPES.add("SET OF");
        DECODABLE_TYPES.add("SEQUENCE OF");
        Class<?> clazz = com.gamma.skybase.decoder.asn2.BERDecoder.class;
        for (Method method : BERDecoder.class.getMethods()) {

            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == byte[].class) {
                String name = method.getName();
                try {
                    Method m = clazz.getMethod(name, byte[].class);
                    DECODABLE_TYPES.add(name.toUpperCase());
                    decoderCache.put(name.toUpperCase(), m);
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                }
            }
        }
//        Field[] fields = BERDecoder.class.getFields();
//        for (Field field : fields) {
//            String name = field.getName().toUpperCase();
//            DECODABLE_TYPES.add(name);
//        }
    }

    public static Method getDecodeMethod(String type) {
        type = type.toUpperCase().replace(" ", "");
        type = type.replace("-", "");
        Method method = decoderCache.get(type);
        if (method == null)
            method = decoderCache.get("HEXSTRING");
        return method;
    }

    /**
     * Checks if the tag's type is a known primitive type.
     *
     * @return true if the type is primitive, false otherwise.
     */
    public static boolean isDecodable(String type) {

        if (type != null) {
            return DECODABLE_TYPES.contains(type.toUpperCase());
        }
        return false;
    }
//    /**
//     * Parses a single line from the ASN.1 schema to create a {@link Tag} object.
//     *
//     * @param line The line to parse.
//     * @return A new {@link Tag} object, or null on failure.
//     */
//    public static Tag createTag(String line) {
//        try {
//            String name = parseName(line);
//            String[] tagInfo = parseTagInfo(line);
//            String fieldType = parseFieldType(line);
//            return new Tag(tagInfo[0], tagInfo[1], name, fieldType);
//        } catch (Exception e) {
////            logger.error("Failed to parse tag config from line: '{}'", line, e);
//            e.printStackTrace();
//            return null;
//        }
//    }
//
//    public static Tag createTag(String parentTagPath, String no, String name, String type) {
//          return new Tag(parentTagPath, no, name, type);
//    }

    /**
     * Parses the name of the tag from a schema line.
     *
     * @param line The schema line.
     * @return The parsed tag name.
     */
    private static String parseName(String line) {
        if (line.contains("::=")) {
            return line.substring(0, line.indexOf("::=")).trim();
        } else if (line.contains("[")) {
            return line.substring(0, line.indexOf("[")).trim();
        } else {
            return line.split(" ")[0].trim();
        }
    }

    /**
     * Parses the tag number and class (e.g., [APPLICATION 1]) from a schema line.
     *
     * @param line The schema line.
     * @return A string array containing the tag number and type, or nulls if not present.
     */
    private static String[] parseTagInfo(String line) {
        String tagNo = null, tType = null;
        if (line.contains("[") && line.contains("]")) {
            String tagStr = line.substring(line.indexOf('[') + 1, line.indexOf(']')).trim();
            String[] a = tagStr.split(" ");
            if (a.length > 1) {
                tType = a[0].trim();
                tagNo = a[1].trim();
            } else {
                tagNo = a[0].trim();
            }
        }
        return new String[]{tagNo, tType};
    }


    public static Asn1Element createTag(String parentTagPath, Object o, String name, String type) {
        return null;
    }

    public static boolean isPrimitive(String type) {
        return PRIMITIVE_TYPES.contains(type.toUpperCase());
    }

    public static boolean isPrimitive(Asn1Element tlv) {
        return PRIMITIVE_TYPES.contains(tlv.elementType.toUpperCase());
    }

    public static boolean isUnvContainer(String type) {
        return UNV_CONTAINER_TYPE.contains(type.toUpperCase());
    }

    public static boolean isUnvContainer(Asn1Element tag) {
        return UNV_CONTAINER_TYPE.contains(tag.containerType);
    }


    public static boolean isUserDefined(Asn1Element tlv) {
        return "USER_DEFINED".equalsIgnoreCase(tlv.containerType);
    }

    public static boolean isIntermediate(Asn1Element tlv) {
        boolean noTagNo = tlv.tagNo.isEmpty();
        return noTagNo && isUserDefined(tlv);
    }

}
