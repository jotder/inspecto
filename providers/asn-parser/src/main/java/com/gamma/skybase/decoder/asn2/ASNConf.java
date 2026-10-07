package com.gamma.skybase.decoder.asn2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link DataDef} for handling ASN.1 schema definitions.
 * This class parses an ASN.1 schema file, builds a tree of {@link Asn1Element} objects,
 * and populates the tag-to-name and tag-to-number mappings.
 */
public class ASNConf extends DataDef {
    private static final Logger logger = LoggerFactory.getLogger(ASNConf.class);
    private final String rootTagName;
    private final Map<String, Asn1Element> allTags = new LinkedHashMap<>();
    private String treeTxtLog;

    /**
     * Constructs an ASNConf instance, which involves parsing the ASN.1 schema and building the tag configuration tree.
     *
     * @param dataDefFile The path to the ASN.1 schema definition file.
     * @param rootTagName The name of the root tag in the schema from which to start parsing.
     * @param skipLines   The number of lines to skip at the beginning of the schema file.
     * @throws IOException if an error occurs while reading the schema file.
     */
    public ASNConf(String dataDefFile, String rootTagName, int skipLines) throws IOException {
        super(dataDefFile, skipLines);
        this.rootTagName = rootTagName;
        parseASN1Grammar(tagDEF);
        Asn1Element rootTag = allTags.get(rootTagName);
        if (rootTag != null) {
            // Create a deep copy for tree building to avoid modifying the original allTags map
            treeTxtLog = buildASNTree("", null, rootTag.dup());
            logger.debug("Generated ASN.1 Tree Log:\n{}", treeTxtLog);
        } else {
            logger.error("Root tag '{}' not found in ASN definition.", rootTagName);
        }
    }

    public String getASTLog() {
        return treeTxtLog;
    }

    /**
     * Parses the raw ASN.1 schema text to build an initial map of all defined tags.
     * This method cleans and processes each line to construct {@link Asn1Element} objects.
     *
     * @param asn1Txt The raw ASN.1 schema as a single string.
     */
    public void parseASN1Grammar(String asn1Txt) {
        // 1. Initial line filtering and cleaning
        List<String> cleanedLines = new BufferedReader(new StringReader(asn1Txt)).lines()
                .skip(skipLines)
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("--") && !l.startsWith("..") && !l.startsWith("BEGIN") && !l.startsWith("END"))
                .map(l -> l.contains("--") ? l.substring(0, l.indexOf("--")).trim() : l)
                .map(l -> l.replace("OPTIONAL", "").replace('\t', ' ').replace(',', ' ').trim())
                .map(l -> l.replaceAll(" +", " "))
                .map(l -> l.replaceAll("\\s*\\(\\s*SIZE\\s*\\([^)]*\\)\\s*\\)", ""))
                .map(l -> l.replaceAll("\\s+", " ").trim())
                .map(l -> l.contains("(") ? l.trim().substring(0, l.indexOf('(')) : l)
                .collect(Collectors.toList());

        // 2. Handle multi-line definitions using braces
        List<String> lines = new ArrayList<>();
        for (String line : cleanedLines) {
            if (line.contains("{") && line.length() > 1) {
                String t = line.trim().substring(0, line.indexOf('{')).trim();
                lines.add(t);
                lines.add("{");
            } else {
                lines.add(line);
            }
        }

        // 3. Parse lines into Asn1Element objects
        Asn1Element currentTag = null;
        boolean inBlock = false;
        for (String line : lines) {
            if (line.contains("{")) {
                inBlock = true;
                continue;
            }

            if (validTagLine(line)) {
                Asn1Element t = Asn1Parser.parseLine(line);
                if (t != null) {
                    if (inBlock && currentTag != null) {
                        currentTag.addChild(t);
                    } else {
                        currentTag = t;
                        allTags.put(t.name, t);
                    }
                }
            } else if (line.contains("}")) {
                if (currentTag != null) {
                    allTags.put(currentTag.name, currentTag);
                }
                inBlock = false;
                currentTag = null;
            }
        }
    }

    private boolean validTagLine(String line) {
        boolean hasMinLength = line.length() > 2;
        boolean isAssignment = line.contains("::=");
        boolean hasTagBrackets = line.contains("[") && line.contains("]");
        return hasMinLength && (isAssignment || hasTagBrackets);
    }

    private String updateTagCache(String path, Asn1Element tag) {
        tag.tagNo = path;
        tagNoConf.put(path, tag);
        return path + "\t\t--> " + tag;
    }

    private String getUpdatedPath(String path, Asn1Element tag) {
        if (tag.hasTagNo()) {
            return path.isEmpty() ? tag.tagNo : path + "." + tag.tagNo;
        }
        return path;
    }

    /**
     * Recursively populates the tag number and tag name configuration maps (tagNoConf, tagNameConf).
     * This method traverses the tag hierarchy starting from the root tag.
     *
     * @param path   The fully qualified path of the parent tag (e.g., "1.2.3").
     * @param parent The parent {@link Asn1Element}.
     * @param tag    The current {@link Asn1Element} to process.
     */
    private String buildASNTree(String path, Asn1Element parent, Asn1Element tag) {
        if (tag == null) {
//            logger.warn("buildASNTree called with a null tag. Parent: {}", parent);
            return "";
        }

        String treeTxt = "";
        if (parent == null) { // Root Tag
            treeTxt += updateTagCache(path, tag);
        }

        // Resolve intermediate user-defined types
        if (TagHelper.isUserDefined(tag)) {
            Asn1Element temp = findIntermediate(tag);
            if (temp != null) {
                tag.elementType = temp.elementType;
                tag.constructed = temp.constructed;
                tag.children = temp.children;
                tag.containerType = temp.containerType;
            }
        }

        switch (tag.containerType) {
            case "SEQUENCE OF":
                String pathSeqOf = path.isEmpty() ? "16" : path + ".16";
                treeTxt += updateTagCache(path, tag);
                if (TagHelper.isPrimitive(tag)) break;
                Asn1Element seqElement = allTags.get(tag.elementType);
                if (seqElement != null) {
                    treeTxt += buildASNTree(pathSeqOf, tag, seqElement.dup());
                }
                break;

            case "SET OF":
                String pathSetOf = path.isEmpty() ? "17" : path + ".17";
                treeTxt += updateTagCache(path, tag);
                Asn1Element setElement = allTags.get(tag.elementType);
                if (setElement != null) {
                    treeTxt += buildASNTree(pathSetOf, tag, setElement.dup());
                }
                break;

            case "SEQUENCE":
            case "SET":
                if (tag.getMemberTags().isEmpty())
                    logger.warn("Container tag '{}' has no member tags.", tag.name);

                char first = Character.toLowerCase(tag.name.charAt(0));
                tag.name = first + tag.name.substring(1);
                treeTxt += updateTagCache(path, tag);
                treeTxt += processMemberTags(path, tag);
                break;

            case "CHOICE":
                if (tag.getMemberTags().isEmpty()) logger.warn("CHOICE tag '{}' has no member tags.", tag.name);
                treeTxt += processMemberTags(path, tag);
                break;

            default:
                if (TagHelper.isUnvContainer(tag.containerType))
                    return buildASNTree(path, parent, tag);

                if (TagHelper.isPrimitive(tag)) {
                    tag.tagNo = path;
                    treeTxt += updateTagCache(path, tag);
                    break;
                }
                if (TagHelper.isUserDefined(tag)) {
                    Asn1Element t1 = allTags.get(tag.elementType);
                    if (t1 == null) {
                        logger.error("User-defined type '{}' not found in allTags map for tag '{}'", tag.elementType, tag.name);
                        return "";
                    }
                    tag.tagNo = path;
                    return buildASNTree(path, tag, t1.dup());
                }
        }
        return "\n" + treeTxt;
    }

    /**
     * Parses the member tags of a constructed type (SEQUENCE, SET, CHOICE) and populates their configuration.
     *
     * @param path   The tag number of the parent.
     * @param curTag The current constructed tag whose members are to be parsed.
     * @return A string log of the processed tree.
     */
    private String processMemberTags(String path, Asn1Element curTag) {
        StringBuilder treeTxt = new StringBuilder();
        for (Asn1Element member : curTag.getMemberTags()) {
            String p = getUpdatedPath(path, member);
            treeTxt.append(buildASNTree(p, curTag, member));
        }
        return "\n" + treeTxt;
    }

    /**
     * Resolves intermediate or referenced tags. If a tag's type is not a primitive or standard constructed type,
     * this method looks it up in the `allTags` map and recursively resolves it.
     *
     * @param tag The tag to resolve.
     * @return The fully resolved {@link Asn1Element}, or null if resolution fails.
     */
    private Asn1Element findIntermediate(Asn1Element tag) {
        if (!TagHelper.isUserDefined(tag)) {
            return tag;
        }

        Asn1Element typeElement = allTags.get(tag.elementType);
        if (typeElement == null)
            return null;

        Asn1Element nestedTag = typeElement.dup();
        if (TagHelper.isUnvContainer(nestedTag) || TagHelper.isPrimitive(nestedTag)) {
            return nestedTag;
        } else { // User Defined, recurse until a non-user-defined type is found
            Asn1Element finalResolvedTag = findIntermediate(nestedTag);
            if (finalResolvedTag == null) {
//                logger.warn("Intermediate tag resolution failed for '{}'. This might indicate a grammar issue.", nestedTag.name);
                return nestedTag; // Return the last known tag
            }
            return finalResolvedTag;
        }
    }

    public String getTxTemplate() {
        Asn1Element rt = tagNoConf.get("");
        return getTransformTemplate(rt);
    }

    public Map<String, Asn1Element> nodes = new LinkedHashMap<>();

    public String getTransformTemplate(Asn1Element rt) {
        String trimmedGrammar = generateGrammar(rt, "");
        logger.debug("Generated Transformation Grammar:\n{}", trimmedGrammar);

        Set<String> recordSetHandled = new HashSet<>();
        String text = "{\n";
        text += nodes.entrySet().stream()
                .filter(e -> !recordSetHandled.contains(e.getValue().name))
                .map((ele) -> {
                    String k = ele.getKey();
                    Asn1Element v = ele.getValue();
                    String recordText = "\n\"" + k + "\": {\n";

                    recordText += v.children.stream()
                            .map(e -> {
                                if (e.isSeqOrSetOf()) {
                                    recordSetHandled.add(e.name);
                                }
                                return e.getTransTemplate("\t");
                            })
                            .collect(Collectors.joining(",\n"));

                    recordText += "\n}";
                    return recordText;
                }).collect(Collectors.joining(",\n"));
        text += "\n}\n";
        return text;
    }

    public String generateGrammar(Asn1Element tag, String pad) {
        if (tag == null) {
            return "";
        }
        String text = "";
        Asn1Element v = tag.dup();
        AtomicReference<String> pad1 = new AtomicReference<>(pad);
        pad1.set(pad + "\t");
        try {
            String comments = "        \t\t \"@comment\": \" " + v.containerType + " " + v.elementType + "\"\n";
            if (v.isSeqOrSetOf()) {
                String p = pad1.get();
                String t1 = "\n" + p + "\"" + v.name + "\": {" + comments;
                t1 += p + "\t \"@comment\": \"define joining strategy with the nested records here\"\n";
                String ut = (v.isSeqOf()) ? "16" : "17";
                if (v.hasTagNo()) ut = v.tagNo + "." + ut;

                if (TagHelper.isPrimitive(v.elementType)) ut = "";

                Asn1Element x = tagNoConf.get(ut);
                nodes.put(v.name, v);

                if (!TagHelper.isPrimitive(v.elementType)) t1 += generateGrammar(x, p);

                for (Asn1Element e : v.children) t1 += generateGrammar(e, p);
                t1 += p + "}\n";
                text += t1;
            } else if (v.isSeqOrSet() || v.isChoice()) {
                String p = pad1.get();
                String t2 = p + "\"" + v.name + "\": {" + comments;
                nodes.put(v.name, v);
                for (Asn1Element e : v.children) {
                    t2 += generateGrammar(e, p);
                }
                t2 += p + "}\n";
                text += t2;
            } else {
                text += pad1.get() + "\"" + v.name + "\": \"\"," + comments;
            }
        } catch (Exception e) {
            logger.error("Error generating grammar for tag '{}'", tag.name, e);
        }
        return text;
    }
}
