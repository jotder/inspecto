package com.gamma.la.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The embed-grant half of the {@link InvestigationStore} contract (LA-EMBED-VIEW-1, decision D12: a file implementation behind the seam now,
 * Postgres later through THIS contract). A subclass supplies only {@link #fresh()}: a new, empty store. Kept apart from
 * {@link InvestigationStoreContract} so a backend that does not hold grants yet (it answers 503) is not failed by the cases of the rest.
 */
abstract class EmbedGrantStoreContract {

    abstract InvestigationStore fresh() throws Exception;

    private static InvestigationStore withInvestigations(InvestigationStore s, String... ids) throws IOException {
        for (String id : ids) assertTrue(s.create(id, "{\"id\":\"" + id + "\"}"));
        return s;
    }

    @Test
    void aGrantComesBackExactlyAsWrittenAndIsWriteOnce() throws Exception {
        InvestigationStore s = withInvestigations(fresh(), "inv-1");
        String json = "{\"grantId\":\"Abcdefgh12345678\",\"label\":\"  café — テスト  \",\"zeta\":1,\"alpha\":2}";
        assertTrue(s.createEmbedGrant("inv-1", "Abcdefgh12345678", json));
        assertEquals(Optional.of(json), s.embedGrant("Abcdefgh12345678"), "verbatim: no trimming, no re-encoding, no key reordering");
        assertFalse(s.createEmbedGrant("inv-1", "Abcdefgh12345678", "{\"other\":true}"), "an existing id is never overwritten");
        assertEquals(Optional.of(json), s.embedGrant("Abcdefgh12345678"));
    }

    @Test
    void aGrantNeedsAnInvestigationAndAnIdOfTheRightShape() throws Exception {
        InvestigationStore s = withInvestigations(fresh(), "inv-1");
        assertThrows(IOException.class, () -> s.createEmbedGrant("nope", "Abcdefgh12345678", "{}"), "no such Investigation");
        for (String bad : List.of("short", "has space 123456", "../Abcdefgh1234", "a/b/c/d/e/f/g/h/i", "x".repeat(65), ""))
            assertThrows(IOException.class, () -> s.createEmbedGrant("inv-1", bad, "{}"), "'" + bad + "' is not a grant id");
    }

    @Test
    void aLookupOfAnythingThatIsNotAGrantIdIsSimplyAbsent() throws Exception {
        InvestigationStore s = withInvestigations(fresh(), "inv-1");
        assertTrue(s.createEmbedGrant("inv-1", "Abcdefgh12345678", "{\"x\":1}"));
        for (String hostile : List.of("", "short", "../Abcdefgh12345678", "Abcdefgh12345678/..", "Abcdefgh1234567\u0000",
                "x".repeat(200)))
            assertEquals(Optional.empty(), s.embedGrant(hostile), "'" + hostile + "'");
        assertFalse(s.replaceEmbedGrant("../Abcdefgh12345678", "{}"));
        assertFalse(s.replaceEmbedGrant("Zzzzzzzz12345678", "{}"), "replace never creates");
    }

    @Test
    void replaceIsAtomicAndTheListIsPerInvestigationInIdOrder() throws Exception {
        InvestigationStore s = withInvestigations(fresh(), "inv-1", "inv-2");
        assertTrue(s.createEmbedGrant("inv-1", "Bbbbbbbb12345678", "{\"n\":2}"));
        assertTrue(s.createEmbedGrant("inv-1", "Aaaaaaaa12345678", "{\"n\":1}"));
        assertTrue(s.createEmbedGrant("inv-2", "Cccccccc12345678", "{\"n\":3}"));
        assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), s.embedGrants("inv-1"));
        assertEquals(List.of("{\"n\":3}"), s.embedGrants("inv-2"));
        assertEquals(List.of(), s.embedGrants("inv-3"), "an Investigation with none");

        assertTrue(s.replaceEmbedGrant("Bbbbbbbb12345678", "{\"n\":22,\"revoked\":true}"));
        assertEquals(Optional.of("{\"n\":22,\"revoked\":true}"), s.embedGrant("Bbbbbbbb12345678"));
        assertEquals(List.of("{\"n\":1}", "{\"n\":22,\"revoked\":true}"), s.embedGrants("inv-1"));
        assertEquals(List.of("{\"n\":3}"), s.embedGrants("inv-2"), "a replace in one Investigation touches no other");
    }

    @Test
    void aGrantIsASidecarNeverTheLogOrTheHeaderOrASet() throws Exception {
        InvestigationStore s = withInvestigations(fresh(), "inv-1");
        s.append(InvestigationStore.Scope.main("inv-1"), 0, 1, "{\"kind\":\"op\",\"step\":1}", "{\"step\":1}");
        long version = s.version(InvestigationStore.Scope.main("inv-1"));
        List<String> log = s.log(InvestigationStore.Scope.main("inv-1"));
        Optional<String> header = s.header("inv-1");
        Optional<String> set = s.set("inv-1", 1);
        String token = s.logToken(InvestigationStore.Scope.main("inv-1"));

        assertTrue(s.createEmbedGrant("inv-1", "Abcdefgh12345678", "{\"x\":1}"));
        assertTrue(s.replaceEmbedGrant("Abcdefgh12345678", "{\"x\":2}"));

        assertEquals(version, s.version(InvestigationStore.Scope.main("inv-1")), "the log version did not move");
        assertEquals(log, s.log(InvestigationStore.Scope.main("inv-1")));
        assertEquals(header, s.header("inv-1"));
        assertEquals(set, s.set("inv-1", 1));
        assertEquals(token, s.logToken(InvestigationStore.Scope.main("inv-1")), "the token a cache is keyed on did not move");
        assertEquals(List.of("inv-1"), s.ids(), "a grant is not an Investigation");
    }
}
