package com.gamma.asn.schema;

import com.gamma.asn.core.TagClass;
import com.gamma.asn.core.Tlv;

public record TagKey(TagClass tagClass, long number) {

    public static TagKey of(Tlv tlv) {
        return new TagKey(tlv.tagClass(), tlv.tagNumber());
    }

    public static TagKey universal(int number) {
        return new TagKey(TagClass.UNIVERSAL, number);
    }
}
