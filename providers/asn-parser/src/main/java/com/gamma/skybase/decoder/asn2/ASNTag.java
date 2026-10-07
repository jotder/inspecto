package com.gamma.skybase.decoder.asn2;

/**
 * Represents a parsed ASN.1 tag, encapsulating its properties such as class, value, and whether it is constructed.
 * This is an immutable value object.
 */
public class ASNTag extends BERTags {
    private final int clazz;
    private final int tValue;
    private boolean explicit;
    private final boolean isConstructed;

    /**
     * Constructs an ASNTag.
     *
     * @param tClass         The tag class (e.g., UNIVERSAL, APPLICATION, CONTEXT, PRIVATE).
     * @param tValue        The tag number (value).
     * @param isConstructed True if the tag is constructed (i.e., its value is a sequence of other TLV structures), false if it is primitive.
     */

    public ASNTag(int tClass, int tValue, boolean isConstructed) {
        super();
        this.clazz = tClass;
        this.tValue = tValue;
        this.isConstructed = isConstructed;
        this.explicit = isConstructed && tClass != UNIVERSAL;
    }

    /**
     * Gets the class of the tag.
     * @return The tag class constant (e.g., {@link BERTags#UNIVERSAL}).
     */
    public int getClazz() {
        return clazz;
    }

    /**
     * Gets the tag number (value).
     * @return The integer value of the tag.
     */
    public int gettValue() {
        return tValue;
    }

    /**
     * Checks if the tagging is explicit.
     * @return true if explicit, false otherwise.
     */
    public boolean isExplicit() {
        return explicit;
    }

    /**
     * Checks if the tagging is implicit.
     * @return true if implicit, false otherwise.
     */
    public boolean isImplicit() {
        return !explicit;
    }

    /**
     * Checks if the tag is constructed.
     * @return true if the tag is constructed, false if it is primitive.
     */
    public boolean isConstructed() {
        return isConstructed;
    }

    //<editor-fold desc="Convenience methods for tag checking">
    public boolean isSequence() {
        return tValue == SEQUENCE || tValue == SEQUENCE_OF;
    }

    public boolean isSet() {
        return tValue == SET || tValue == SET_OF;
    }

    public boolean isInteger() {
        return tValue == INTEGER;
    }

    public boolean isBitString() {
        return tValue == BIT_STRING;
    }

    public boolean isOctetString() {
        return tValue == OCTET_STRING;
    }

    public boolean isNull() {
        return tValue == NULL;
    }

    public boolean isObjectIdentifier() {
        return tValue == OBJECT_IDENTIFIER;
    }

    public boolean isEnumerated() {
        return tValue == ENUMERATED;
    }

    public boolean isUTCTime() {
        return tValue == UTC_TIME;
    }

    public boolean isGeneralizedTime() {
        return tValue == GENERALIZED_TIME;
    }

    public boolean isPrintableString() {
        return tValue == PRINTABLE_STRING;
    }

    public boolean isIA5String() {
        return tValue == IA5_STRING;
    }

    public boolean isUTF8String() {
        return tValue == UTF8_STRING;
    }
    //</editor-fold>

    /**
     * Gets a descriptive name for the tag.
     * @return The name of the tag (e.g., "SEQUENCE", "CONTEXT[0]").
     */
    public String getTypeName() {
        switch (clazz) {
            case UNIVERSAL:
                switch (tValue) {
                    case BOOLEAN: return "BOOLEAN";
                    case INTEGER: return "INTEGER";
                    case BIT_STRING: return "BIT STRING";
                    case OCTET_STRING: return "OCTET STRING";
                    case NULL: return "NULL";
                    case OBJECT_IDENTIFIER: return "OBJECT IDENTIFIER";
                    case EXTERNAL: return "EXTERNAL";
                    case ENUMERATED: return "ENUMERATED";
                    case UTF8_STRING: return "UTF8STRING";
                    case SEQUENCE: return "SEQUENCE";
                    case SET: return "SET";
                    case NUMERIC_STRING: return "NUMERIC STRING";
                    case PRINTABLE_STRING: return "PRINTABLE STRING";
                    case T61_STRING: return "T61 STRING";
                    case VIDEOTEX_STRING: return "VIDEOTEX STRING";
                    case IA5_STRING: return "IA5 STRING";
                    case UTC_TIME: return "UTC TIME";
                    case GENERALIZED_TIME: return "GENERALIZED TIME";
                    case GRAPHIC_STRING: return "GRAPHIC STRING";
                    case VISIBLE_STRING: return "VISIBLE STRING";
                    case GENERAL_STRING: return "GENERAL STRING";
                    case UNIVERSAL_STRING: return "UNIVERSAL STRING";
                    case BMP_STRING: return "BMP STRING";
                    default: return "UNIVERSAL_" + tValue;
                }
            case APPLICATION:
                return "APPLICATION[" + tValue + "]";
            case CONTEXT:
                return "CONTEXT[" + tValue + "]";
            case PRIVATE:
                return "PRIVATE[" + tValue + "]";
            default:
                return "UNKNOWN_CLASS[" + tValue + "]";
        }
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ASNTag{");
        sb.append(getTypeName());
        if (isConstructed) {
            sb.append(" (Constructed)");
        } else {
            sb.append(" (Primitive)");
        }
        sb.append(explicit ? ", explicit" : ", implicit");
        sb.append('}');
        return sb.toString();
    }
}
