package com.gamma.geolink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The identity fact log's chain (LA-17, design §4.2) and the fold over it. The property: a log that does not verify is
 * REFUSED, never folded as far as it happens to read — and a sealed seq is never overwritten.
 */
class EntityFactLogTest {

    private static EntityFactLog.Log threeFacts(EntityFactLog log) throws Exception {
        EntityFactLog.Log head = log.read();
        head = log.append(head, "a1", "open", "list.created", "wl",
                Map.of("title", "T", "purpose", "watch", "entityType", "msisdn"));
        head = log.append(head, "a1", "add", "list.member.added", "wl", Map.of("keys", List.of("+441", "+442")));
        return log.append(head, "a2", "drop", "list.member.removed", "wl", Map.of("keys", List.of("+441")));
    }

    private static Path file(Path root, int seq) {
        return root.resolve("audit/entity-facts").resolve(String.format("%012d.json", seq));
    }

    @Test
    void appendsChainEachFactToThePreviousFilesBytes(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        assertEquals(0, log.read().headSeq());
        assertEquals("", log.read().headHash());

        EntityFactLog.Log written = threeFacts(log);
        EntityFactLog.Log read = log.read();
        assertEquals(3, read.headSeq());
        assertEquals(EntityFactLog.sha256(Files.readAllBytes(file(root, 3))), read.headHash(), "head = hash of the last file");
        assertEquals(written.headHash(), read.headHash());
        assertEquals("", read.facts().get(0).body().get("prevHash"));
        assertEquals(EntityFactLog.sha256(Files.readAllBytes(file(root, 1))), read.facts().get(1).body().get("prevHash"));
        assertEquals(EntityFactLog.sha256(Files.readAllBytes(file(root, 2))), read.facts().get(2).body().get("prevHash"));
        assertEquals(List.of("seq", "at", "actor", "reason", "kind", "listId", "keys", "prevHash"),
                List.copyOf(read.facts().get(1).body().keySet()));
    }

    @Test
    void aTamperedFactFailsTheReadLoudly(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        threeFacts(log);
        Path second = file(root, 2);
        Files.writeString(second, Files.readString(second).replace("+442", "+449"));
        EntityFactLog.BrokenChainException e = assertThrows(EntityFactLog.BrokenChainException.class, log::read);
        assertTrue(e.getMessage().contains("seq 3"), e.getMessage());
        assertFalse(e.getMessage().contains(root.toString()), "the message names no server path");
    }

    @Test
    void aMissingOrRenumberedFactFailsTheRead(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        threeFacts(log);
        byte[] second = Files.readAllBytes(file(root, 2));
        Files.delete(file(root, 2));
        assertTrue(assertThrows(EntityFactLog.BrokenChainException.class, log::read).getMessage().contains("missing"));

        Files.write(file(root, 2), Files.readAllBytes(file(root, 1)));   // a copy of fact 1 under seq 2
        assertTrue(assertThrows(EntityFactLog.BrokenChainException.class, log::read).getMessage().contains("records seq 1"));

        Files.write(file(root, 2), second);
        assertEquals(3, log.read().headSeq(), "restored bytes verify again");
        Files.writeString(file(root, 3), "not json");
        assertThrows(EntityFactLog.BrokenChainException.class, log::read);
    }

    @Test
    void aSealedSeqIsNeverOverwritten(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log stale = log.read();
        threeFacts(log);
        byte[] before = Files.readAllBytes(file(root, 1));
        assertThrows(FileAlreadyExistsException.class,
                () -> log.append(stale, "x", "late", "list.retired", "wl", Map.of()));
        assertArrayEquals(before, Files.readAllBytes(file(root, 1)));
        try (var s = Files.list(root.resolve("audit/entity-facts"))) {
            assertEquals(3, s.count(), "no staging file is left behind");
        }
        assertEquals(3, log.read().headSeq());
    }

    @Test
    void publishingOntoAnExistingSeqFailsAndLeavesTheSealedFactByteIdentical(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        threeFacts(log);
        byte[] sealed = Files.readAllBytes(file(root, 2));
        Path tmp = Files.writeString(root.resolve("audit/entity-facts/.fact-x.tmp"), "{\"seq\":2,\"forged\":true}");
        assertThrows(FileAlreadyExistsException.class, () -> EntityFactLog.publishNew(tmp, file(root, 2)));
        assertArrayEquals(sealed, Files.readAllBytes(file(root, 2)));
        assertEquals(3, log.read().headSeq(), "the chain still verifies");
    }

    /** Concurrent first readers of a fresh directory all get the same whole key — never a half-written one. */
    @Test
    void concurrentFirstReadersOfTheMaskKeyAllGetTheSameKey(@TempDir Path dir) throws Exception {
        int n = 16;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> keys = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<String> first = () -> {
                    go.await();
                    return HexFormat.of().formatHex(EntityMasking.key(dir));
                };
                keys.add(pool.submit(first));
            }
            go.countDown();
            String want = keys.get(0).get();
            assertEquals(64, want.length(), "a whole 32-byte key");
            for (Future<String> k : keys) assertEquals(want, k.get());
        } finally {
            pool.shutdownNow();
        }
        try (var s = Files.list(dir)) {
            assertEquals(List.of("mask.key"), s.map(p -> p.getFileName().toString()).toList(), "no staging file left");
        }
    }

    @Test
    void theFoldReadsAnyPosition(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log head = log.append(threeFacts(log), "a1", "done", "list.retired", "wl", Map.of());
        assertTrue(EntityRegistry.fold(head.facts(), 0).isEmpty());
        assertEquals(List.of(), List.copyOf(EntityRegistry.fold(head.facts(), 1).get("wl").members()));
        assertEquals(List.of("+441", "+442"), List.copyOf(EntityRegistry.fold(head.facts(), 2).get("wl").members()));
        EntityRegistry.EntityList at3 = EntityRegistry.fold(head.facts(), 3).get("wl");
        assertEquals(List.of("+442"), List.copyOf(at3.members()));
        assertFalse(at3.retired());
        assertEquals(3, at3.lastSeq());
        EntityRegistry.EntityList at4 = EntityRegistry.fold(head.facts(), 4).get("wl");
        assertTrue(at4.retired());
        assertEquals(List.of("+442"), List.copyOf(at4.members()), "a retired list keeps its members");
        assertEquals("a1", at4.createdBy());
        assertEquals("msisdn", at4.entityType());
    }
}
