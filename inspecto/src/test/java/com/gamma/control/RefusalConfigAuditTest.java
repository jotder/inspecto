package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/** A Pipeline config write that changes a content-refusal key is audited, naming the change; others are not. */
class RefusalConfigAuditTest {

    private static List<Event> write(Path f, String toon) throws Exception {
        List<Event> seen = new ArrayList<>();
        BiConsumer<EventLog, Event> tap = (log, e) -> { if (e.toString().contains("pipeline.refusal.changed")) seen.add(e); };
        EventLog.addTap(tap);
        try {
            RefusalConfigAudit.write(f, toon.getBytes(StandardCharsets.UTF_8), ".cfg-");
        } finally {
            EventLog.removeTap(tap);
        }
        return seen;
    }

    @Test
    void turningTheScanOffOrWideningTheExemptListIsAudited(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("p_pipeline.toon");
        String on = "name: p\nprocessing:\n  refusal: restricted_quarantine\n  refusal_scan: card_number\n";
        assertEquals(1, write(f, on).size(), "creating a config that sets the keys is a change");
        assertEquals(0, write(f, on + "  threads: 2\n").size(), "an unrelated edit is not a refusal change");
        List<Event> off = write(f, "name: p\nprocessing:\n  refusal: restricted_quarantine\n  refusal_scan: off\n  threads: 2\n");
        assertEquals(1, off.size());
        assertTrue(off.get(0).toString().contains("card_number") && off.get(0).toString().contains("off"), off.toString());
        List<Event> exempt = write(f, "name: p\nprocessing:\n  refusal: restricted_quarantine\n  refusal_scan: off\n  threads: 2\n"
                + "  refusal_scan_exempt[1]: ORDER_ID\n");
        assertEquals(1, exempt.size());
        assertTrue(exempt.get(0).toString().contains("ORDER_ID"), exempt.toString());
        assertTrue(Files.readString(f).contains("ORDER_ID"), "the write itself happened");
    }
}
