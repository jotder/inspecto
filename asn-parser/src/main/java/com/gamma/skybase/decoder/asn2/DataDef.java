package com.gamma.skybase.decoder.asn2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An abstract base class for holding and managing data definitions, typically from an ASN.1 schema file.
 * It provides the basic structure for storing tag configurations and reading the definition file.
 * This class now implements {@link TagDefinitionProvider} to decouple the readers from this specific implementation.
 */
public abstract class DataDef implements TagDefinitionProvider {

    /**
     * A map holding tag configurations keyed by their fully qualified tag numbers (e.g., "1.2.3").
     */
    protected final Map<String, Asn1Element> tagNoConf = new LinkedHashMap<>();

    /**
     * The number of lines to skip at the beginning of the definition file.
     */
    protected final int skipLines;

    /**
     * The raw string content of the tag definition file.
     */
    protected final String tagDEF;

    /**
     * Constructs a DataDef instance by reading a definition file.
     *
     * @param dataDefFile The path to the data definition file.
     * @param skipLines   The number of lines to skip from the top of the file.
     * @throws IOException if an error occurs while reading the file.
     */
    protected DataDef(String dataDefFile, int skipLines) throws IOException {
        this.skipLines = skipLines;
        this.tagDEF = readStringFromFile(Paths.get(dataDefFile));
    }

    /**
     * Reads the entire content of a file into a string.
     *
     * @param path The path to the file.
     * @return The content of the file as a string.
     * @throws IOException if an error occurs while reading the file.
     */
    private static String readStringFromFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Override
    public final Asn1Element getTagDefById(String currentTag) {
        return tagNoConf.get(currentTag);
    }

    @Override
    public final Map<String, Asn1Element> getTagNoConf() {
        return Collections.unmodifiableMap(tagNoConf);
    }

    /**
     * Abstract method to be implemented by subclasses to generate a transformation template.
     * @return A string representing the transformation template.
     */
    abstract String getTxTemplate();
}
