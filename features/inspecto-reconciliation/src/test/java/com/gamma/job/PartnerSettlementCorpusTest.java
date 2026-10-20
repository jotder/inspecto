package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link PartnerSettlementCorpus}: deterministic, planted and benign sets disjoint, synthetic names and identifiers only. */
class PartnerSettlementCorpusTest {

    @Test
    void theGeneratorIsDeterministic() {
        assertEquals(PartnerSettlementCorpus.generate().files(), PartnerSettlementCorpus.generate().files());
        assertEquals(PartnerSettlementCorpus.generate().plantedFindings, PartnerSettlementCorpus.generate().plantedFindings);
    }

    @Test
    void plantedAndLookAlikeKeysAreDisjoint() {
        PartnerSettlementCorpus c = PartnerSettlementCorpus.generate();
        Set<String> planted = new HashSet<>();
        c.plantedFindings.values().forEach(planted::addAll);
        for (String b : c.benign) assertFalse(planted.contains(b), "a look-alike is also planted: " + b);
        // one key carries one reason: a planted key is never planted twice
        int total = c.plantedFindings.values().stream().mapToInt(Set::size).sum();
        assertEquals(total, planted.size());
        assertEquals(13, total, "8 amount_mismatch, 2 cutoff_difference, 1 fx_difference, 1 missing_statement, 1 unrated_usage");
    }

    @Test
    void everyNameAndIdentifierIsFictional() {
        PartnerSettlementCorpus c = PartnerSettlementCorpus.generate();
        for (String p : c.partners) {
            String[] f = p.split(",");
            assertTrue(f[1].contains("Test"), "a partner name must read as a test fixture: " + f[1]);
            assertTrue(f[3].startsWith("X"), "country codes are the made-up X-range: " + f[3]);
        }
        for (String h : c.holidays) assertTrue(h.startsWith("XA,") || h.startsWith("XB,"), h);
        for (PartnerSettlementCorpus.Agreement a : c.agreements) assertTrue(a.id().startsWith("AG-"), a.id());
        for (java.util.List<PartnerSettlementCorpus.Line> lines : c.statements.values())
            for (PartnerSettlementCorpus.Line l : lines) assertTrue(l.stmt().startsWith("STM-"), l.stmt());
        // no number that looks like a phone number, an account or a long identifier anywhere in the feeds
        for (String csv : c.files().values()) assertFalse(csv.matches("(?s).*\\d{9,}.*"), "a long digit run in a fixture");
    }

    @Test
    void everyRowFallsInsideTheWindowAndNoMoneyHasMoreThanFourDecimals() {
        PartnerSettlementCorpus c = PartnerSettlementCorpus.generate();
        for (PartnerSettlementCorpus.Rerated r : c.rerated) {
            assertFalse(r.day().isBefore(PartnerSettlementCorpus.FIRST));
            assertFalse(r.day().isAfter(PartnerSettlementCorpus.LAST));
            if (r.amount() != null) assertEquals(4, r.amount().scale(), r.csv());
        }
        for (java.util.List<PartnerSettlementCorpus.Line> lines : c.statements.values())
            for (PartnerSettlementCorpus.Line l : lines) assertEquals(4, l.amount().scale(), l.csv());
    }
}
