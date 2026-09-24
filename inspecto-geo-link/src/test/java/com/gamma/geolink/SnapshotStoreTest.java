package com.gamma.geolink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code AUDIT-LOG-UNBOUNDED-READ-1}: {@link SnapshotStore#attachmentsOf} streams the one
 * {@code attachments.jsonl} every snapshot shares, instead of loading it whole. Same answer as before —
 * the snapshot's Case ids in recorded order — on a large file and with a torn trailing line.
 */
class SnapshotStoreTest {

    @Test
    void attachmentsOfReadsALargeSharedFileInOrder(@TempDir Path root) throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        store.attach("snap-a", "case-first", "2026-09-24T00:00:00Z");
        Path f = root.resolve("audit").resolve("snapshots").resolve("attachments.jsonl");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50_000; i++)
            sb.append("{\"snapshotId\":\"snap-b\",\"caseId\":\"c").append(i).append("\",\"at\":\"t\"}\n");
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        store.attach("snap-a", "case-last", "2026-09-24T00:00:01Z");

        assertEquals(List.of("case-first", "case-last"), store.attachmentsOf("snap-a"));
        assertEquals(50_000, store.attachmentsOf("snap-b").size());
    }

    @Test
    void aTornTrailingLineIsIgnoredAsBefore(@TempDir Path root) throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        store.attach("snap-a", "case-1", "2026-09-24T00:00:00Z");
        Path f = root.resolve("audit").resolve("snapshots").resolve("attachments.jsonl");
        Files.writeString(f, "{\"snapshotId\":\"snap-a\",\"caseId\":\"case-2", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        assertEquals(List.of("case-1"), store.attachmentsOf("snap-a"),
                "a Case id with no closing quote is not recorded");
    }

    @Test
    void noAttachmentsFileReadsEmpty(@TempDir Path root) throws Exception {
        assertEquals(List.of(), new SnapshotStore(root).attachmentsOf("snap-a"));
    }
}
