package com.gamma.skybase.decoder.asn2;

import java.util.Map;

/**
 * Defines the contract for providing ASN.1 tag definitions.
 * This interface decouples the ASN.1 readers from the concrete configuration implementation,
 * allowing for greater flexibility and easier testing.
 */
public interface TagDefinitionProvider {

    /**
     * Gets the map of all tag configurations, keyed by their fully qualified tag number (e.g., "1.2.3").
     *
     * @return A map of tag numbers to their {@link Asn1Element} definitions.
     */
    Map<String, Asn1Element> getTagNoConf();

    /**
     * Gets the definition for a specific tag by its fully qualified tag number.
     *
     * @param tagId The tag number (e.g., "1.2.3").
     * @return The {@link Asn1Element} definition, or null if not found.
     */
    Asn1Element getTagDefById(String tagId);
}
