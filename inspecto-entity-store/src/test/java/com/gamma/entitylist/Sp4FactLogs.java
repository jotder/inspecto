package com.gamma.entitylist;

import com.gamma.control.ApiContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SP4 test support: (1) a bulk generator that writes a chain-valid fact log in the exact byte format of
 * {@link EntityFactLog#append} without its per-append cost ({@code append} copies the whole fact list and fsyncs, which is
 * O(n^2) and hours at 10^6), and (2) {@link HeadCached}, a PROTOTYPE of the cheapest read-cache fix for risk R-09 (replay is
 * uncached per read). Neither is production code.
 */
final class Sp4FactLogs {

    private Sp4FactLogs() {}

    /**
     * Write {@code n} facts under {@code writeRoot}: seq 1..100 create lists {@code l0..l99}, then a mix of
     * {@code list.member.added} (5 keys), occasional {@code list.member.removed}. Returns the head hash.
     */
    static String generate(Path writeRoot, int n) throws IOException {
        Path dir = Files.createDirectories(new EntityFactLog(writeRoot).directory());
        String prev = "";
        for (int seq = 1; seq <= n; seq++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("seq", (long) seq);
            body.put("at", "2026-10-06T00:00:00Z");
            body.put("actor", "bench");
            body.put("reason", "sp4");
            String list = "l" + (seq <= 100 ? seq - 1 : seq % 100);
            if (seq <= 100) {
                body.put("kind", "list.created");
                body.put("listId", list);
                body.put("title", "T");
                body.put("purpose", "exclude");
                body.put("entityType", "msisdn");
            } else {
                body.put("kind", seq % 10 == 0 ? "list.member.removed" : "list.member.added");
                body.put("listId", list);
                List<String> keys = new ArrayList<>(5);
                for (int k = 0; k < 5; k++) keys.add("+44" + String.format("%09d", (long) seq * 5 + k - (seq % 10 == 0 ? 50 : 0)));
                body.put("keys", keys);
            }
            body.put("prevHash", prev);
            byte[] bytes = ApiContext.JSON.writeValueAsBytes(body);
            Files.write(dir.resolve(String.format("%012d.json", seq)), bytes);
            prev = EntityFactLog.sha256(bytes);
        }
        return prev;
    }

    /**
     * PROTOTYPE cache keyed by the chain head: keeps the last verified {@link EntityFactLog.Log}; a read re-hashes ONLY the
     * head file, and verifies only files newer than the cached head. ⚠ It does NOT notice a tampered or missing file
     * BEFORE the head — a full {@link EntityFactLog#read} (e.g. at start-up, on a timer, on an audit request) must still
     * back it. That is the deliberate trade-off.
     */
    static final class HeadCached {
        private final EntityFactLog log;
        private EntityFactLog.Log cached;

        HeadCached(EntityFactLog log) {
            this.log = log;
        }

        EntityFactLog.Log read() throws IOException {
            if (cached == null) return cached = log.read();
            Path dir = log.directory();
            long seq = cached.headSeq();
            if (seq > 0) {
                Path head = dir.resolve(String.format("%012d.json", seq));
                if (!Files.isRegularFile(head) || !EntityFactLog.sha256(Files.readAllBytes(head)).equals(cached.headHash()))
                    return cached = log.read();   // the head moved or changed: fall back to the full verify
            }
            Path next = dir.resolve(String.format("%012d.json", seq + 1));
            if (!Files.exists(next)) return cached;    // hit
            return cached = log.read();    // new tail: simplest correct answer is the full verify (an incremental one is a few lines more)
        }
    }
}
