package com.gamma.notify;

import com.gamma.config.safety.PathJail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ASSURE-XLSX-ATTACHMENTS-1: where an attachment may come from, its type, its size, its filename. */
class MailAttachmentsTest {

    @AfterEach
    void clearCaps() {
        System.clearProperty(MailAttachments.MAX_ATTACHMENT_PROPERTY);
        System.clearProperty(MailAttachments.MAX_MESSAGE_PROPERTY);
    }

    @Test
    void aRunArtifactUnderTheRootIsReadWithItsAllowlistedType(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("weekly_20260929_060000.csv"), "a,b\n1,2\n");
        MailAttachment a = MailAttachments.fromRunArtifact(file, List.of(root), root);
        assertEquals("weekly_20260929_060000.csv", a.filename());
        assertEquals("text/csv", a.contentType());
        assertEquals("a,b\n1,2\n", new String(a.content()));
    }

    @Test
    void aPathInsideConfigSecretsIsRefusedEvenUnderTheRoot(@TempDir Path root) throws Exception {
        Path secrets = Files.createDirectories(root.resolve("config.secrets"));
        Path key = Files.writeString(secrets.resolve("pending.csv"), "key material");
        PathJail.Escape e = assertThrows(PathJail.Escape.class,
                () -> MailAttachments.fromRunArtifact(key, List.of(root), root));
        assertTrue(e.getMessage().contains("secrets"), e.getMessage());
    }

    @Test
    void aPathOutsideEveryAllowedRootIsRefused(@TempDir Path root, @TempDir Path elsewhere) throws Exception {
        Path outside = Files.writeString(elsewhere.resolve("leak.csv"), "x");
        assertThrows(PathJail.Escape.class, () -> MailAttachments.fromRunArtifact(outside, List.of(root), root));
    }

    @Test
    void aTypeOffTheAllowlistIsRefused(@TempDir Path root) throws Exception {
        Path exe = Files.writeString(root.resolve("report.exe"), "MZ");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MailAttachments.fromRunArtifact(exe, List.of(root), root));
        assertTrue(e.getMessage().contains("not on the allowlist"), e.getMessage());
    }

    @Test
    void anOverCapFileFailsBeforeItIsReadAndNamesTheKnob(@TempDir Path root) throws Exception {
        System.setProperty(MailAttachments.MAX_ATTACHMENT_PROPERTY, "4");
        Path big = Files.writeString(root.resolve("big.csv"), "12345");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MailAttachments.fromRunArtifact(big, List.of(root), root));
        assertTrue(e.getMessage().contains("5 bytes, over the 4-byte cap")
                && e.getMessage().contains(MailAttachments.MAX_ATTACHMENT_PROPERTY), e.getMessage());
    }

    @Test
    void theMessageTotalIsCappedSeparately() {
        System.setProperty(MailAttachments.MAX_MESSAGE_PROPERTY, "5");
        List<MailAttachment> two = List.of(new MailAttachment("a.csv", "text/csv", new byte[3]),
                new MailAttachment("b.csv", "text/csv", new byte[3]));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MailAttachments.check(two));
        assertTrue(e.getMessage().contains("per-message cap"), e.getMessage());
        MailAttachments.check(two.subList(0, 1));   // one alone fits
    }

    @Test
    void checkRefusesAContentTypeOffTheAllowlist() {
        assertThrows(IllegalArgumentException.class, () -> MailAttachments.check(
                List.of(new MailAttachment("x.html", "text/html", new byte[1]))));
    }

    @Test
    void theDefaultCapsAreSafe() {
        assertEquals(10L * 1024 * 1024, MailAttachments.maxAttachmentBytes());
        assertEquals(20L * 1024 * 1024, MailAttachments.maxMessageBytes());
    }

    @Test
    void aFilenameCannotInjectAHeaderOrAPath() {
        String s = MailAttachments.sanitizeFilename("r.csv\r\nBcc: victim@example.com");
        assertFalse(s.contains("\r") || s.contains("\n") || s.contains(":") || s.contains(" "), s);
        assertEquals("passwd", MailAttachments.sanitizeFilename("../../etc/passwd"));
        assertEquals("x.csv", MailAttachments.sanitizeFilename("C:\\temp\\x.csv"));
        assertEquals("a_b_.csv", MailAttachments.sanitizeFilename("a\"b;.csv"));
        assertEquals("hidden", MailAttachments.sanitizeFilename("..hidden"));
        assertEquals("attachment", MailAttachments.sanitizeFilename("\r\n"));
        assertEquals(100, MailAttachments.sanitizeFilename("a".repeat(300) + ".csv").length());
        // the record applies it too, so no caller can hand a raw name to a channel
        assertEquals("x_y.csv", new MailAttachment("x\ny.csv", "text/csv", new byte[0]).filename());
    }
}
