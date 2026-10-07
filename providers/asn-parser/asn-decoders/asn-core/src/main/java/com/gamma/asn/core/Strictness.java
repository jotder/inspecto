package com.gamma.asn.core;

/**
 * DER/CER are validation modes of the same BER core (REDESIGN.md §4.2). CER's remaining
 * canonical check — SET components sorted by tag — is content/schema-level and stays out
 * of the TLV codec; the structural rules are enforced here.
 *
 * @param allowIndefinite               BER/CER yes, DER no
 * @param requireMinimalLength          DER/CER: shortest length encoding, no leading zeros
 * @param requireIndefiniteConstructed  CER: constructed values MUST use indefinite length
 */
public record Strictness(boolean allowIndefinite, boolean requireMinimalLength,
                         boolean requireIndefiniteConstructed) {

    public static final Strictness BER = new Strictness(true, false, false);
    public static final Strictness DER = new Strictness(false, true, false);
    public static final Strictness CER = new Strictness(true, true, true);
}
