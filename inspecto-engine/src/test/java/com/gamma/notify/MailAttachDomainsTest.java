package com.gamma.notify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ASSURE-XLSX-ATTACHMENTS-1 (operator 2026-09-29): the per-Space recipient domain allowlist for attachments. */
class MailAttachDomainsTest {

    private static final List<MailAttachment> ONE = List.of(new MailAttachment("r.csv", "text/csv", new byte[]{1}));

    @AfterEach
    void clear() {
        System.clearProperty("assist.write.root");
    }

    @Test
    void exactOrSubdomainOnlyCaseInsensitiveAfterPunycode() {
        List<String> allow = MailAttachDomains.normaliseAll(List.of("Example.COM", "bücher.example"));
        assertEquals(List.of("example.com", "xn--bcher-kva.example"), allow);
        assertTrue(MailAttachDomains.permits("ops@example.com", allow));
        assertTrue(MailAttachDomains.permits("OPS@Finance.EXAMPLE.com", allow));
        assertTrue(MailAttachDomains.permits("Ops Team <ops@example.com>", allow));
        assertTrue(MailAttachDomains.permits("a@BÜCHER.example", allow), "IDN form matches its punycode");
        assertTrue(MailAttachDomains.permits("a@xn--bcher-kva.example", allow));
        assertFalse(MailAttachDomains.permits("x@notexample.com", allow), "a suffix is not a subdomain");
        assertFalse(MailAttachDomains.permits("x@example.com.evil.test", allow));
        assertFalse(MailAttachDomains.permits("x@evil.test", allow));
        assertFalse(MailAttachDomains.permits("no-at-sign", allow));
    }

    @Test
    void badEntriesAreRefused() {
        for (String bad : List.of("a@b.example", "*.example.com", "localhost", "", "x/y.example"))
            assertThrows(IllegalArgumentException.class, () -> MailAttachDomains.normalise(bad), bad);
    }

    @Test
    void anAttachmentIsRefusedWhileTheListIsEmpty(@TempDir Path root) {
        System.setProperty("assist.write.root", root.toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MailAccess.overChannels()
                .send(List.of("ops@example.com"), List.of(), "s", "b", ONE));
        assertTrue(e.getMessage().contains("attachments are off"), e.getMessage());
    }

    @Test
    void oneCcOutsideTheListRefusesTheWholeSend(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(MailAttachDomains.FILE), "allow[1]: example.com\n");
        System.setProperty("assist.write.root", root.toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MailAccess.overChannels()
                .send(List.of("ops@example.com"), List.of("leak@evil.test"), "s", "b", ONE));
        assertTrue(e.getMessage().contains("1 recipient(s) outside"), e.getMessage());
        // all allowed: passes the gate (no channel on this classpath, so nothing is sent — false, not a throw)
        assertFalse(MailAccess.overChannels().send(List.of("ops@example.com"), List.of("b@x.example.com"), "s", "b", ONE));
        // no attachment: the list does not apply
        assertFalse(MailAccess.overChannels().send(List.of("x@evil.test"), List.of(), "s", "b", List.of()));
    }

    @Test
    void anUnreadableFileReadsAsEmpty(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(MailAttachDomains.FILE), "allow[1]: not a domain@\n");
        assertEquals(List.of(), MailAttachDomains.entries(root));
    }
}
