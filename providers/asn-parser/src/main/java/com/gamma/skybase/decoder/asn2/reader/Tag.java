package com.gamma.skybase.decoder.asn2.reader;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

public final class Tag implements Serializable {

    private static final long serialVersionUID = -4119353507169242662L;

    private final TagClass tagClass;
    private final int value;
    private final boolean explicit;
    private final boolean constructed;

    public Tag(TagClass tagClass, int value, boolean explicit, boolean constructed) {
        this.tagClass = Objects.requireNonNull(tagClass, "tagClass must not be null");
        this.value = value;
        this.explicit = explicit;
        this.constructed = constructed;
    }

    public Tag(TagClass tagClass, int value, boolean explicit) {
        this(tagClass, value, explicit, false);
    }

    public Tag(TagClass tagClass, int value) {
        this(tagClass, value, true, false);
    }

    public Tag(UniversalTag universalTag, boolean explicit, boolean constructed) {
        this(TagClass.UNIVERSAL, universalTag.getValue(), explicit, constructed);
    }

    public Tag(UniversalTag universalTag, boolean explicit) {
        this(universalTag, explicit, false);
    }

    public Tag(UniversalTag universalTag) {
        this(universalTag, true, false);
    }

    public TagClass getTagClass() {
        return tagClass;
    }

    public int getValue() {
        return value;
    }

    public Optional<UniversalTag> getUniversalTag() {
        if (isUniversal()) {
            try {
                return Optional.of(UniversalTag.fromValue(value));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public boolean isExplicit() {
        return explicit;
    }

    public boolean isConstructed() {
        return constructed;
    }

    public boolean isUniversal() {
        return tagClass == TagClass.UNIVERSAL;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Tag tag = (Tag) o;
        return value == tag.value &&
               explicit == tag.explicit &&
               constructed == tag.constructed &&
               tagClass == tag.tagClass;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tagClass, value, explicit, constructed);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tag[");
        sb.append(tagClass);
        if (isUniversal()) {
            getUniversalTag().ifPresent(universalTag -> sb.append(" (").append(universalTag).append(")"));
        }
        sb.append(" value=").append(value);
        sb.append(", constructed=").append(constructed);
        sb.append(", explicit=").append(explicit);
        sb.append("]");
        return sb.toString();
    }
}
