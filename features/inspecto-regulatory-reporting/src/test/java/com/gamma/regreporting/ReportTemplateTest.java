package com.gamma.regreporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ReportTemplate}: the built-in loads, a bad template lists EVERY problem, and a Space template wins. */
class ReportTemplateTest {

    private static final String MINIMAL = """
            id: mini
            title: Mini
            format: csv
            delivery:
              kind: file-drop
              dir: drops/mini
            fields[2]{name,source,required,maxLength}:
              reportId,report.id,true,64
              note,input.note,false,0
            """;

    @Test
    void theBuiltInSampleLoadsCleanly() {
        ReportTemplate.Catalog c = ReportTemplate.load(null);
        assertEquals(List.of(), c.problems());
        ReportTemplate t = c.get("sample-sar");
        assertNotNull(t);
        assertEquals("xml", t.format());
        assertEquals("built-in", t.origin());
        assertEquals("file-drop", t.deliveryKind());
        assertEquals(Set.of("reportingEntity", "narrative", "activityPeriod"), t.inputKeys());
        assertEquals("const:inspecto-sample-sar/1", t.fields().get(0).source(), "a quoted const: value survives TOON");
        ReportTemplate.Field group = t.fields().stream().filter(f -> f.name().equals("incident/title")).findFirst().orElseThrow();
        assertEquals("incident", group.group());
        assertEquals("title", group.leaf());
    }

    @Test
    void aMinimalTemplateParses() throws Exception {
        ReportTemplate t = ReportTemplate.parse(MINIMAL, "mini", "space");
        assertEquals(2, t.fields().size());
        assertTrue(t.fields().get(0).required());
        assertEquals(64, t.fields().get(0).maxLength());
        assertEquals(Set.of("note"), t.inputKeys());
    }

    @Test
    void everyProblemIsReportedNotJustTheFirst() {
        String bad = """
                id: Bad Id
                format: pdf
                surprise: 1
                fields[4]{name,source,required,maxLength}:
                  1bad,report.id,true,0
                  ok,nowhere.id,false,0
                  ok,input.x,false,0
                  inc/title,subject.title,false,0
                """;
        ReportTemplate.Invalid e = assertThrows(ReportTemplate.Invalid.class, () -> ReportTemplate.parse(bad, null, "space"));
        String all = String.join("\n", e.problems());
        assertTrue(all.contains("unknown key 'surprise'"), all);
        assertTrue(all.contains("'id' must match"), all);
        assertTrue(all.contains("'title' is required"), all);
        assertTrue(all.contains("'format' must be one of"), all);
        assertTrue(all.contains("'delivery' is required"), all);
        assertTrue(all.contains("field '1bad'"), all);
        assertTrue(all.contains("field 'ok': source must start with"), all);
        assertTrue(all.contains("'ok' is declared twice"), all);
        assertTrue(all.contains("a group field reads a list"), all);
    }

    @Test
    void anXmlTemplateNeedsASafeRootElementAndAGroupRepeatsOverOneList() {
        String bad = MINIMAL.replace("format: csv", "format: xml\nrootElement: \"<x>\"")
                .replace("fields[2]", "fields[4]")
                + "  g/a,incidents.id,false,0\n  g/b,evidence.uri,false,0\n";
        ReportTemplate.Invalid e = assertThrows(ReportTemplate.Invalid.class, () -> ReportTemplate.parse(bad, "mini", "space"));
        String all = String.join("\n", e.problems());
        assertTrue(all.contains("'rootElement' must match"), all);
        assertTrue(all.contains("group 'g' mixes incidents and evidence"), all);
    }

    @Test
    void theFileNameMustMatchTheId() {
        ReportTemplate.Invalid e = assertThrows(ReportTemplate.Invalid.class, () -> ReportTemplate.parse(MINIMAL, "other", "space"));
        assertTrue(e.getMessage().contains("file is named 'other'"), e.getMessage());
    }

    @Test
    void aSpaceTemplateIsListedAndOverridesABuiltIn(@TempDir Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve(ReportTemplate.SPACE_DIR));
        Files.writeString(dir.resolve("mini.toon"), MINIMAL);
        Files.writeString(dir.resolve("sample-sar.toon"), MINIMAL.replace("id: mini", "id: sample-sar").replace("Mini", "Ours"));
        ReportTemplate.Catalog c = ReportTemplate.load(root);
        assertEquals("space", c.get("mini").origin());
        assertEquals("Ours", c.get("sample-sar").title());
        assertEquals(List.of(), c.problems());
    }

    @Test
    void aBrokenSpaceOverrideDoesNotFallBackToTheBuiltIn(@TempDir Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve(ReportTemplate.SPACE_DIR));
        Files.writeString(dir.resolve("sample-sar.toon"), "id: sample-sar\n");
        ReportTemplate.Catalog c = ReportTemplate.load(root);
        assertNull(c.get("sample-sar"), "a Space that overrode a template and broke it must not file the built-in silently");
        assertEquals("sample-sar", c.problems().get(0).id());
    }
}
