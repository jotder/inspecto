package com.gamma.skybase.decoder.asn2;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Asn1Parser {

    // === Regex Patterns ===
    private static final Pattern TAGGED_PATTERN = Pattern.compile(
            "^\\s*([a-zA-Z_][a-zA-Z0-9_-]*)\\s*" +            // name
                    "\\[\\s*(\\d+)\\s*]\\s*" +                       // [tagNo]
                    "([a-zA-Z_][a-zA-Z0-9_-]*(?:\\s+[a-zA-Z_][a-zA-Z0-9_-]*)?(?:\\s+OF\\s+[a-zA-Z_][a-zA-Z0-9_-]*)?)\\s*,?$"

//            "^\\s*([a-zA-Z_][a-zA-Z0-9_-]*)\\s*\\[\\s*(\\d+)\\s*]\\s*([a-zA-Z_][a-zA-Z0-9_-]*(?:\\s+OF\\s+[a-zA-Z_][a-zA-Z0-9_-]*)?)\\s*,?$"
//            "^\\s*([a-zA-Z0-9_]+)\\s*\\[\\s*(\\d+)\\s*]\\s*([a-zA-Z0-9_\\s\\-]+)(.*)$"
    );

    private static final Pattern TYPEDEF_PATTERN = Pattern.compile(
            "^\\s*([a-zA-Z0-9_-]+)\\s*::=\\s*([a-zA-Z0-9_\\s\\-]+)(.*)$"
    );

    private static final Pattern CONSTRAINT_PATTERN = Pattern.compile("(\\(.*\\))");

//    // === Known types ===
//    private static final Set<String> BASE_TYPES = new HashSet<>(Arrays.asList(
//            "INTEGER", "OCTET STRING", "IA5STRING", "UTF8STRING", "PRINTABLESTRING",
//            "NUMERICSTRING", "BIT STRING", "BOOLEAN", "NULL"
//    ));
//
//    private static final Set<String> CONTAINER_TYPES = new HashSet<>(Arrays.asList(
//            "SEQUENCE", "SET", "CHOICE", "SEQUENCE OF", "SET OF"
//    ));


    public static Asn1Element parseLine(String line) {
        line = line.trim();
        String name = "", tagNo = "", elementType = "", containerType = "";
        String typePart = "";

        Matcher m1 = TAGGED_PATTERN.matcher(line);
        Matcher m2 = TYPEDEF_PATTERN.matcher(line);
        boolean constructed = true;
        if (m1.find()) {
            name = m1.group(1);
            tagNo = m1.group(2);
            typePart = m1.group(3).trim();
            elementType = typePart.split(" ")[0];
        } else if (m2.find()) {
            name = m2.group(1);
            typePart = m2.group(2).trim();
//            elementType = typePart.split(" ")[0];
        } else {
//            System.out.println(line);
        }

        if (TagHelper.isPrimitive(typePart)) {
            elementType = typePart;
            constructed = false;
        } else {
            String type = typePart.split(" ")[0].trim();
            if (typePart.startsWith("SEQUENCE OF")) { // SEQUENCE OF or SET OF
                containerType = "SEQUENCE OF";
                elementType = typePart.substring(containerType.length()).trim();
            } else if (typePart.startsWith("SET OF")) { // SEQUENCE OF or SET OF
                containerType = "SET OF";
                elementType = typePart.substring(containerType.length()).trim();
            } else if (TagHelper.isUnvContainer(typePart)) { // SEQUENCE | SET | CHOICE
                containerType = type.toUpperCase();
            } else if (TagHelper.isPrimitive(typePart)) { // Primitive or defined
                if ("ENUMERATED".equalsIgnoreCase(type))
                    type = "INTEGER";
                elementType = type;
                constructed = false;
            } // user defined
            else {
                containerType = "USER_DEFINED";
                elementType = type;
            }
        }
        if (!name.isEmpty())
            return new Asn1Element(name, tagNo, constructed, containerType, elementType);
        else
            return null;
//        throw new IllegalArgumentException("Unrecognized ASN.1 line: " + line);
    }

    private static String extractConstraints(String part) {
        if (part == null) return null;
        Matcher c = CONSTRAINT_PATTERN.matcher(part.trim());
        return c.find() ? c.group(1) : null;
    }

//    private static Asn1Element buildElement(String name, String tagNo, String typePart, String constraints) {
//        String upper = typePart.toUpperCase(Locale.ROOT);
//

    ////        String tagClass = UNIVERSAL | APPLICATION | CONTEXT-SPECIFIC | PRIVATE,;
//
//        String containerType = null;
//        String elementType = null;
//        boolean constructed = true;
//        String category = "";
//
//        if (upper.startsWith("SEQUENCE OF") || upper.startsWith("SET OF")) {
//            containerType = upper.startsWith("SEQUENCE") ? "SEQUENCE OF" : "SET OF";
//            elementType = typePart.substring(containerType.length()).trim();
//            category = "CONTAINER_UNIVERSAL";
//        } else if (CONTAINER_TYPES.contains(typePart.split(" ")[0].toUpperCase())) {
//            containerType = typePart.split(" ")[0];
//            category = "CONTAINER_UNIVERSAL";
//            elementType = containerType;
//        } else if (PRIMITIVE_TYPES.contains(typePart.split(" ")[0].toUpperCase())) {
//            elementType = typePart.split(" ")[0];
//            constructed = false;
//        } else {
//            category = "USER_DEFINED";
//            elementType = upper;
//        }
//        return new Asn1Element(name, tagNo, constructed, containerType, elementType);
//    }

    // === Demo ===
    public static void main(String[] args) {
        List<String> lines = Arrays.asList(
                "lifeCycleChange [6] LifeCycleChange,",
                "clearedDedicatedAccounts [23] SEQUENCE OF ClearedDedicatedAccount",
                "ServiceFeeDeduction ::= SEQUENCE",
                "AccumulatorValue ::= INTEGER (-2147483648..2147483647)",
                "SelectionTreeQualifiers ::= SEQUENCE OF Qualifier"
        );

        for (String line : lines) {
            System.out.println(parseLine(line));
        }
    }
}

/**
 * Represents the configuration for an ASN.1 tag, including its number, type, and name.
 * This class is used to define the structure of ASN.1 data.
 */
//public class Tag  {
//
//    private String parent;
//    private String no;
//    private String tagType;
//    private String type;
//    private String name;
//    private List<Tag> memberTags = new ArrayList<>();
//
//    /**
//     * Constructs a new TagConfig.
//     *
//     * @param no        The tag number as a string.
//     * @param tagType     The tag type (e.g., APPLICATION, CONTEXT-SPECIFIC).
//     * @param name      The name of the tag.
//     * @param fieldType The data type of the tag (e.g., INTEGER, OCTET_STRING).
//     */
//    public Tag(String no, String tagType, String name, String fieldType) {
//        this.no = no;
//        this.tagType = tagType;
//        this.name = name;
//        this.type = fieldType;
//    }
//
//    private static final Logger logger = LoggerFactory.getLogger(Tag.class);
//
//    /**
//     * Creates a shallow copy of this TagConfig instance.
//     *
//     * @return A new TagConfig instance with the same field values.
//     */
//    public Tag dup() {
//        Tag newTag = new Tag(no, tagType, name, type);
//        if (!this.memberTags.isEmpty() || TagHelper.isDecodable(type))
//            newTag.setMemberTags(this.memberTags); // Use setter which creates a copy
//        return newTag;
//    }
//
//    @Override
//    public String toString() {
//        return String.format("TagConfig { no='%s', name='%s', type='%s'}", no, name, type);
//    }
//
//
//    /**
//     * Decodes a single byte array value using the appropriate method from the decoder cache.
//     *
//     * @param value The byte array to decode.
//     * @return The decoded object, or the original byte array if decoding fails.
//     */
//    public Object decode(byte[] value) throws Exception {
//        Method decoder = TagHelper.getDecodeMethod(type);
//
//        try {
//            if (decoder == null) {
//                Object o = decoder.invoke(null, value);
//                return o;
//            } else
//                return new Exception();
//        } catch (Exception e) {
//            e.printStackTrace();
//            String s = "Failed to decode value for tag: " + name + " with type: " + type;
//            System.out.println(s);
//            return new Exception(s);
//        }
//    }
//
//    /**
//     * Checks if the tag is a defined type (primitive, SEQUENCE, SET, CHOICE, etc.).
//     *
//     * @return true if the tag type is defined, false otherwise.
//     */
//    public boolean isDefinedTag() {
//        return TagHelper.isDecodable(type.toUpperCase()) || isSeqOfOrSetOf() || isSeqOrSet() || isChoice();
//    }
//
//    /**
//     * Checks if the tag's type is a SEQUENCE or SET.
//     *
//     * @return true if the type is SEQUENCE or SET, false otherwise.
//     */
//    public boolean isSeqOrSet() {
//        return type != null && (type.equalsIgnoreCase("SET") || type.equalsIgnoreCase("SEQUENCE"));
//    }
//
//    /**
//     * Checks if the tag's type is a CHOICE.
//     *
//     * @return true if the type is CHOICE, false otherwise.
//     */
//    public boolean isChoice() {
//        return type != null && type.equalsIgnoreCase("CHOICE");
//    }
//
//    /**
//     * Checks if the tag's type is a SEQUENCE OF or SET OF.
//     *
//     * @return true if the type is SEQUENCE OF or SET OF, false otherwise.
//     */
//    public boolean isSeqOfOrSetOf() {
//        return type != null && (type.toUpperCase().startsWith("SET OF") || type.toUpperCase().startsWith("SEQUENCE OF"));
//    }
//
//    /**
//     * Gets the tag number.
//     * @return The tag number.
//     */
//    public String getNo() {
//        return no;
//    }
//
//    public boolean hasNo() {
//        return no != null;
//    }
//
//    /**
//     * Sets the tag number.
//     * @param no The tag number to set.
//     */
//    public void setNo(String no) {
//        this.no = no;
//    }
//
//    /**
//     * Gets the tag type (e.g., APPLICATION).
//     * @return The tag type.
//     */
//    public String getTagType() {
//        return tagType;
//    }
//
//    /**
//     * Sets the tag type.
//     * @param tagType The tag type to set.
//     */
//    public void setTagType(String tagType) {
//        this.tagType = tagType;
//    }
//
//    /**
//     * Gets the data type of the tag.
//     * @return The data type.
//     */
//    public String getType() {
//        return type;
//    }
//
//    /**
//     * Sets the data type of the tag.
//     * @param type The data type to set.
//     */
//    public void setType(String type) {
//        this.type = type;
//    }
//
//    /**
//     * Gets the name of the tag.
//     * @return The tag name.
//     */
//    public String getName() {
//        return name;
//    }
//
//    /**
//     * Sets the name of the tag.
//     * @param name The tag name to set.
//     */
//    public void setName(String name) {
//        this.name = name;
//    }
//
//    /**
//     * Returns an unmodifiable view of the list of member tags for constructed types.
//     *
//     * @return An unmodifiable list of member tags.
//     */
//    public List<Tag> getMemberTags() {
//        return Collections.unmodifiableList(memberTags);
//    }
//
//    /**
//     * Sets the member tags for this configuration. A copy of the provided list is created.
//     *
//     * @param memberTags The list of member tags to set.
//     */
//    public void setMemberTags(List<Tag> memberTags) {
//        this.memberTags = new ArrayList<>(memberTags);
//    }
//
//    /**
//     * Adds a member tag to this configuration.
//     *
//     * @param tag The member tag to add.
//     */
//    public void addMemberTag(Tag tag) {
//        this.memberTags.add(tag);
//    }
//
//    public boolean isEnumerated() {
//        return type.equalsIgnoreCase("ENUMERATED");
//    }
//
////    public static boolean isAvailable(String type){
////        return BERDecoder.getDecoders().contains(type);
////    }
////
////    public static Method getDecodeMethod(String type) {
////        Method decoder = decoderCache.get(type);
////
////        if (decoder == null) {
////            logger.warn("No decoder found for type: '{}'. Falling back to hex string.", type);
////            decoder = decoderCache.get("HEX_STRING");
////        }
////        return decoder;
////    }
//
//
//}
