package com.gamma.entitylist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SEP-08: moving the fact log from {@code inspecto-geo-link} to this module changed NO on-disk format or path, so a
 * Space written BEFORE the move keeps verifying its chain and folding. The fixture under {@code legacy-space/} is a
 * four-fact log (a list, its members, an Identity Fact — the log is shared — and a removal) plus a {@code mask.key},
 * written independently of this module's classes: files named {@code <12-digit seq>.json}, each body carrying the
 * SHA-256 of the previous file's bytes as {@code prevHash}, under {@code <write root>/audit/entity-facts/}.
 */
class LegacySpaceFactLogTest {

    private static Path copyOfFixture(Path into) throws IOException, URISyntaxException {
        Path from = Path.of(LegacySpaceFactLogTest.class.getResource("/legacy-space").toURI());
        try (Stream<Path> all = Files.walk(from)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                Path to = into.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to); else Files.copy(p, to);
            }
        }
        return into;
    }

    @Test
    void aLogWrittenBeforeTheMoveStillVerifiesAndFolds(@TempDir Path root) throws Exception {
        copyOfFixture(root);
        EntityFactLog log = new EntityFactLog(root);

        EntityFactLog.Log head = log.read();   // throws BrokenChainException when the chain does not verify
        assertEquals(4, head.headSeq());
        assertEquals("6d4c81b045a490ff3998cbf832337262dd583b958f98564be74223911fcb4337", head.headHash());

        EntityRegistry.EntityList wl = EntityRegistry.fold(head.facts(), head.headSeq()).get("wl");
        assertEquals(List.of("+442"), List.copyOf(wl.members()), "+441 was removed at seq 4");
        assertEquals("e164", wl.normaliser());
        assertEquals(List.of("+441", "+442"), List.copyOf(EntityRegistry.fold(head.facts(), 2).get("wl").members()),
                "an as-of read still sees the earlier state");
        assertEquals(List.of("imsi:9", "msisdn:+441"),
                List.copyOf(EntityRegistry.resolve(head.facts(), head.headSeq()).get("imsi:9").members()),
                "the Identity Fact sharing the log folds beside the lists");
    }

    @Test
    void aNewFactAppendsOntoALegacyLogAndTheChainStillVerifies(@TempDir Path root) throws Exception {
        copyOfFixture(root);
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log next = log.append(log.read(), "analyst-2", "after the move", "list.member.added", "wl",
                Map.of("keys", List.of("+443")));
        assertEquals(5, next.headSeq());
        assertEquals(next.headHash(), log.read().headHash());
        assertEquals("6d4c81b045a490ff3998cbf832337262dd583b958f98564be74223911fcb4337",
                log.read().facts().get(4).body().get("prevHash"), "chained to the legacy head's bytes");
    }

    @Test
    void aTamperedLegacyFactIsStillRefused(@TempDir Path root) throws Exception {
        copyOfFixture(root);
        Path second = root.resolve("audit/entity-facts/000000000002.json");
        Files.writeString(second, Files.readString(second).replace("+442", "+999"));
        assertThrows(EntityFactLog.BrokenChainException.class, () -> new EntityFactLog(root).read());
    }

    @Test
    void theLegacyMaskKeyStillMintsTheSamePseudonym(@TempDir Path root) throws Exception {
        copyOfFixture(root);
        byte[] key = MaskTokens.key(root.resolve("audit/entity-facts"));
        assertEquals("masked:96d79faf8f68a1d0", MaskTokens.token(key, "+442"),
                "HMAC-SHA256 under the key already on disk — a pseudonym minted before the move is the one minted after");
    }
}
