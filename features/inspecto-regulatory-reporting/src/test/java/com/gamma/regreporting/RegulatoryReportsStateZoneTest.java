package com.gamma.regreporting;

import com.gamma.spi.auth.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SE-4 step 1 for the Regulatory Report store: read from {@code state/regulatory-reports} first, then {@code config/};
 * with both the state one wins, and this release refuses to rewrite (or deliver) a record a newer release owns.
 */
class RegulatoryReportsStateZoneTest {

    private static final String ID = "rr-20261113000000-abcdef";

    private static Map<String, Object> rec(String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", ID);
        r.put("recordType", RegulatoryReports.DOMAIN);
        r.put("status", status);
        r.put("expiresAt", "2000-01-01T00:00:00Z");
        return r;
    }

    private static Path inConfig(Path root) {
        return root.resolve(RegulatoryReports.DIR).resolve(ID + ".json");
    }

    private static Path inState(Path root) throws Exception {
        return Files.createDirectories(root.resolveSibling("state").resolve(RegulatoryReports.DIR)).resolve(ID + ".json");
    }

    @Test
    void aRecordUnderStateIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        RegulatoryReports.save(root, rec(RegulatoryReports.PENDING));
        Files.move(inConfig(root), inState(root));
        Map<String, Object> back = RegulatoryReports.read(root, ID);
        assertFalse(RegulatoryReports.invalid(back), "its MAC covers the content and the file name, not the folder: " + back);
        assertEquals(List.of(ID), RegulatoryReports.list(root).stream().map(x -> x.get("id")).toList());
    }

    @Test
    void aRecordUnderConfigIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        RegulatoryReports.save(root, rec(RegulatoryReports.PENDING));
        assertFalse(Files.exists(root.resolveSibling("state")), "nothing is written to state/");
        assertEquals(RegulatoryReports.PENDING, RegulatoryReports.read(root, ID).get("status"));
        assertEquals(1, RegulatoryReports.list(root).size());
    }

    @Test
    void withBothTheStateRecordWinsAndIsNeverRewrittenHere(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        RegulatoryReports.save(root, rec(RegulatoryReports.PENDING));
        byte[] pending = Files.readAllBytes(inConfig(root));
        RegulatoryReports.save(root, rec(RegulatoryReports.DECLINED));
        Files.write(inState(root), pending);

        Map<String, Object> back = RegulatoryReports.read(root, ID);
        assertEquals(RegulatoryReports.PENDING, back.get("status"), "state/ wins");
        assertEquals(1, RegulatoryReports.list(root).size(), "one record per id across both places");
        ApiException e = assertThrows(ApiException.class, () -> RegulatoryReports.save(root, back));
        assertEquals(409, e.status);
        assertThrows(ApiException.class, () -> RegulatoryReportRoutes.submit(root, back, "approver-1", "user"),
                "a retry never delivers what this release cannot record");
        assertFalse(RegulatoryReports.expireIfDue(root, back), "a newer release's record is never expired here");
    }
}
