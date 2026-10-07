package com.gamma.asn.schema;

/** Decodes one primitive value's content bytes into its string form. */
@FunctionalInterface
public interface ValueDecoder {
    String decode(byte[] bytes);
}
