package com.gamma.service;

import com.gamma.service.SpaceInboxAudit.InboxDecl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two Spaces polling one inbox — scale-out plan §5.3.
 *
 * <p>🔴 Why it matters: nothing claims an inbox file before it is read ({@code MarkerManager} marks only
 * after a batch commits), so two Spaces on one directory both ingest the same files. The symptom is silent
 * double-ingestion, which is why this is worth a loud finding rather than being left to convention.
 */
class SpaceInboxAuditTest {

    @Test
    void twoSpacesOnOneInboxAreReportedWithBothNamed() {
        List<String> findings = SpaceInboxAudit.sharedInboxFindings(List.of(
                new InboxDecl("tenant-a", "orders", "/data/shared/inbox"),
                new InboxDecl("tenant-b", "orders", "/data/shared/inbox")));

        assertEquals(1, findings.size(), "one finding for the one shared inbox");
        String f = findings.get(0);
        assertTrue(f.contains("tenant-a/orders"), "the first declarer is named: " + f);
        assertTrue(f.contains("tenant-b/orders"),
                "⛔ the other declarer must be named too, or the operator cannot act: " + f);
    }

    /** ⛔ The discriminator: separate inboxes are the normal arrangement and must stay silent. */
    @Test
    void spacesWithTheirOwnInboxesAreHealthy() {
        assertTrue(SpaceInboxAudit.sharedInboxFindings(List.of(
                new InboxDecl("tenant-a", "orders", "/data/a/inbox"),
                new InboxDecl("tenant-b", "orders", "/data/b/inbox"))).isEmpty(),
                "each Space polling its own inbox is exactly right");
    }

    /**
     * ⛔ Two pipelines in ONE Space sharing an inbox is legal and must NOT be reported.
     *
     * <p>They are polled by one {@code CollectorService} on one pod — the very thing that makes it safe.
     * The invariant is about Spaces; flagging this would bury the real signal under a legal arrangement.
     */
    @Test
    void twoPipelinesInTheSameSpaceMayShareAnInbox() {
        assertTrue(SpaceInboxAudit.sharedInboxFindings(List.of(
                new InboxDecl("tenant-a", "orders", "/data/a/inbox"),
                new InboxDecl("tenant-a", "returns", "/data/a/inbox"))).isEmpty(),
                "one Space, one poller — sharing within a Space is not the hazard");
    }

    /** Paths that differ only in spelling are the SAME directory — the check must normalise. */
    @Test
    void theComparisonNormalisesEquivalentPaths() {
        List<String> findings = SpaceInboxAudit.sharedInboxFindings(List.of(
                new InboxDecl("tenant-a", "orders", "/data/shared/inbox"),
                new InboxDecl("tenant-b", "orders", "/data/shared/./sub/../inbox")));

        assertEquals(1, findings.size(),
                "'/data/shared/./sub/../inbox' IS '/data/shared/inbox' — comparing raw strings would "
                        + "miss the collision, and a near-miss spelling is exactly how this gets authored");
    }

    /** A blank or absent inbox is not a collision — it is a different config problem entirely. */
    @Test
    void blankInboxesAreIgnored() {
        assertTrue(SpaceInboxAudit.sharedInboxFindings(List.of(
                new InboxDecl("tenant-a", "orders", ""),
                new InboxDecl("tenant-b", "orders", null),
                new InboxDecl("tenant-c", "orders", "   "))).isEmpty(),
                "an unset inbox is the parser's business, not this audit's");
    }

    /** Three Spaces on one inbox is one finding naming all three, not three findings. */
    @Test
    void threeSpacesOnOneInboxAreOneFindingNamingAll() {
        List<String> findings = SpaceInboxAudit.sharedInboxFindings(List.of(
                new InboxDecl("a", "p", "/data/shared"),
                new InboxDecl("b", "p", "/data/shared"),
                new InboxDecl("c", "p", "/data/shared")));

        assertEquals(1, findings.size(), "one inbox, one finding");
        assertTrue(findings.get(0).contains("a/p") && findings.get(0).contains("b/p")
                && findings.get(0).contains("c/p"), findings.get(0));
    }

    @Test
    void noDeclarationsIsHealthy() {
        assertTrue(SpaceInboxAudit.sharedInboxFindings(List.of()).isEmpty());
    }
}
