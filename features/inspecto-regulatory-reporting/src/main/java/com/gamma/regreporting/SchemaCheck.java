package com.gamma.regreporting;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * XSD validation of an xml {@link ReportTemplate} that names a {@code schema}. The XSD is a file name looked up beside
 * the Space's templates ({@code <config root>/regulatory-report-templates/<schema>}), then beside the built-ins on the
 * module classpath. JDK {@code javax.xml.validation} only; external schema / DTD access is switched off, so an XSD
 * cannot pull anything in by URL ({@code xs:import} / {@code xs:include} of another file is refused too: one file).
 */
final class SchemaCheck {

    private SchemaCheck() {}

    /** The compiled schema, or {@link IllegalArgumentException} naming why it cannot be loaded. */
    static Schema load(Path configRoot, String name) {
        byte[] xsd = read(configRoot, name);
        try {
            SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            return f.newSchema(new StreamSource(new ByteArrayInputStream(xsd), name));
        } catch (SAXException bad) {
            throw new IllegalArgumentException("schema '" + name + "' does not compile: " + bad.getMessage(), bad);
        }
    }

    private static byte[] read(Path configRoot, String name) {
        if (name == null || !ReportTemplate.SCHEMA_FILE.matcher(name).matches())
            throw new IllegalArgumentException("schema '" + name + "' is not an XSD file name");
        try {
            if (configRoot != null) {
                Path p = configRoot.toAbsolutePath().normalize().resolve(ReportTemplate.SPACE_DIR).resolve(name);
                if (Files.isRegularFile(p)) return Files.readAllBytes(p);
            }
            try (InputStream in = SchemaCheck.class.getResourceAsStream("/META-INF/inspecto/regulatory-templates/" + name)) {
                if (in != null) return in.readAllBytes();
            }
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("schema '" + name + "' is unreadable: " + unreadable.getMessage(), unreadable);
        }
        throw new IllegalArgumentException("schema '" + name + "' is not in " + ReportTemplate.SPACE_DIR
                + "/ nor among the built-in schemas");
    }

    /** Every violation of {@code xml} against {@code schema}, with line:column; empty when it is valid. */
    static List<String> violations(Schema schema, String xml) {
        List<String> out = new ArrayList<>();
        Validator v = schema.newValidator();
        try {
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) {}
                @Override public void error(SAXParseException e) { out.add(at(e)); }
                @Override public void fatalError(SAXParseException e) { out.add(at(e)); }
            });
            v.validate(new StreamSource(new StringReader(xml)));
        } catch (SAXException | IOException stopped) {
            if (out.isEmpty()) out.add(stopped.getMessage());
        }
        return out;
    }

    private static String at(SAXParseException e) {
        return "line " + e.getLineNumber() + ":" + e.getColumnNumber() + " " + e.getMessage();
    }
}
