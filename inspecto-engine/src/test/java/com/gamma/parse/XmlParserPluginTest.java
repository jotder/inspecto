package com.gamma.parse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlParserPluginTest {

    private final XmlParserPlugin xml = new XmlParserPlugin();

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final String ORDERS = """
            <orders>
              <order id="1001"><customer tier="gold"><name>Ada</name></customer><amount>42.5</amount></order>
              <order id="1002"><customer tier="silver"><name>Grace</name></customer><amount>17.25</amount></order>
            </orders>""";

    @Test
    void defaultRecordsAreTheRootsDirectChildren() throws Exception {
        ParseResult.Tree t = assertInstanceOf(ParseResult.Tree.class, xml.preview(b(ORDERS), Map.of()));
        assertEquals(2, t.recordCount());
        ParseResult.Node rec = t.nodes().get(0);
        assertEquals("order", rec.label());
        assertEquals("element", rec.type());
        assertNull(rec.value());
        // @id attribute leaf, customer container, amount text leaf
        assertEquals(List.of("@id", "customer", "amount"),
                rec.children().stream().map(ParseResult.Node::label).toList());
        assertEquals("1001", rec.children().get(0).value());
        assertEquals("attr", rec.children().get(0).type());
        ParseResult.Node customer = rec.children().get(1);
        assertEquals(List.of("@tier", "name"),
                customer.children().stream().map(ParseResult.Node::label).toList());
        assertEquals("Ada", customer.children().get(1).value());
        assertEquals("42.5", rec.children().get(2).value());
    }

    @Test
    void recordElementByLocalNameAndBySlashPath() throws Exception {
        String doc = "<a><b><rec><v>1</v></rec></b><rec><v>2</v></rec></a>";
        ParseResult.Tree byName = (ParseResult.Tree) xml.preview(b(doc),
                Map.of("xml", Map.of("record_element", "rec")));
        assertEquals(2, byName.recordCount());
        ParseResult.Tree byPath = (ParseResult.Tree) xml.preview(b(doc),
                Map.of("xml", Map.of("record_element", "b/rec")));
        assertEquals(1, byPath.recordCount());
        assertEquals("1", byPath.nodes().get(0).children().get(0).value());
    }

    @Test
    void mixedContentKeepsTextAsATextLeaf() throws Exception {
        ParseResult.Tree t = (ParseResult.Tree) xml.preview(
                b("<r><item>note <b>bold</b></item><item>plain</item></r>"), Map.of());
        ParseResult.Node mixed = t.nodes().get(0);
        assertEquals(List.of("b", "#text"), mixed.children().stream().map(ParseResult.Node::label).toList());
        ParseResult.Node plain = t.nodes().get(1);
        assertEquals("plain", plain.value());
        assertTrue(plain.children().isEmpty());
    }

    @Test
    void malformedXmlIsACallerError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> xml.preview(b("<a><oops</a>"), Map.of()));
        assertTrue(e.getMessage().contains("not well-formed"));
    }

    @Test
    void noMatchingRecordElementIsACallerError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> xml.preview(b("<a><b/></a>"), Map.of("xml", Map.of("record_element", "ghost"))));
        assertTrue(e.getMessage().contains("ghost"));
    }

    @Test
    void doctypesAreRefusedOutright() {
        // XXE hardening: SUPPORT_DTD=false makes any DOCTYPE a stream error → caller error.
        String xxe = "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r><a>&x;</a></r>";
        assertThrows(IllegalArgumentException.class, () -> xml.preview(b(xxe), Map.of()));
    }

    @Test
    void recordCountCountsAllMatchesWhileNodesAreCapped() throws Exception {
        StringBuilder doc = new StringBuilder("<rs>");
        for (int i = 0; i < 60; i++) doc.append("<r><n>").append(i).append("</n></r>");
        doc.append("</rs>");
        ParseResult.Tree capped = (ParseResult.Tree) xml.preview(b(doc.toString()),
                Map.of("xml", Map.of("max_records", 10)));
        assertEquals(60, capped.recordCount());
        assertEquals(10, capped.nodes().size());
        // Default cap is DEFAULT_RECORDS (50).
        ParseResult.Tree dflt = (ParseResult.Tree) xml.preview(b(doc.toString()), Map.of());
        assertEquals(XmlParserPlugin.DEFAULT_RECORDS, dflt.nodes().size());
    }

    @Test
    void suggestProposesTheRepeatedRootChild() {
        Map<String, Object> clue = xml.suggest(b(ORDERS));
        assertEquals(Map.of("xml", Map.of("record_element", "order")), clue);
        assertEquals(Map.of(), xml.suggest(b("not xml at all")));
    }
}
