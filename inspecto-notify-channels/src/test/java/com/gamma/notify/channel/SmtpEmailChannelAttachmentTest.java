package com.gamma.notify.channel;

import com.gamma.notify.MailAttachment;
import com.gamma.notify.MailAttachments;
import com.gamma.notify.Notification;
import org.junit.jupiter.api.Test;

import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-XLSX-ATTACHMENTS-1: the MIME structure {@link SmtpEmailChannel} builds for attachments, asserted on
 * the message as it would go on the wire (serialised, then re-parsed) — no live SMTP server.
 */
class SmtpEmailChannelAttachmentTest {

    private static final SmtpEmailChannel CH = new SmtpEmailChannel("mail.example.com", 25,
            "inspecto@example.com", "fixed@example.com", null, null, false);

    private static Notification n() {
        return Notification.create("job", "JOB_RUN", null, "Report 'weekly' ready", "delivered", null);
    }

    /** Serialise and re-parse: what a recipient's client would actually see. */
    private static MimeMessage wire(MimeMessage m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    void attachmentsMakeAMultipartMixedWithTheTextFirst() throws Exception {
        byte[] xlsx = {'P', 'K', 3, 4, 0, 1, 2};
        MimeMessage m = wire(CH.message(n(), "ops@example.com", null, List.of(
                new MailAttachment("weekly.xlsx", MailAttachments.CONTENT_TYPES.get("xlsx"), xlsx),
                new MailAttachment("weekly.csv", "text/csv", "a,b\n".getBytes(StandardCharsets.UTF_8)))));

        assertTrue(m.getContentType().startsWith("multipart/mixed"), m.getContentType());
        MimeMultipart mp = (MimeMultipart) m.getContent();
        assertEquals(3, mp.getCount());
        assertTrue(mp.getBodyPart(0).isMimeType("text/plain"));
        assertTrue(((String) mp.getBodyPart(0).getContent()).contains("delivered"));

        MimeBodyPart a = (MimeBodyPart) mp.getBodyPart(1);
        assertEquals(Part.ATTACHMENT, a.getDisposition());
        assertEquals("weekly.xlsx", a.getFileName());
        assertTrue(a.isMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertEquals("base64", a.getEncoding());
        assertArrayEquals(xlsx, a.getInputStream().readAllBytes());
        assertEquals("weekly.csv", mp.getBodyPart(2).getFileName());
    }

    @Test
    void noAttachmentsStaysPlainText() throws Exception {
        MimeMessage m = wire(CH.message(n(), "ops@example.com", null, List.of()));
        assertTrue(m.isMimeType("text/plain"), m.getContentType());
    }

    @Test
    void aFilenameCannotInjectHeaders() throws Exception {
        // The record already sanitises; a subclass-free way to hand the channel a raw name does not exist, so
        // assert what reaches the wire: no second header, and the Bcc never appears as a message header.
        MimeMessage m = wire(CH.message(n(), "ops@example.com", null, List.of(
                new MailAttachment("r.csv\r\nBcc: victim@example.com\r\nX-Evil: 1", "text/csv", new byte[]{1}))));
        assertNull(m.getHeader("Bcc"));
        MimeBodyPart a = (MimeBodyPart) ((MimeMultipart) m.getContent()).getBodyPart(1);
        assertNull(a.getHeader("Bcc"));
        assertNull(a.getHeader("X-Evil"));
        String name = a.getFileName();
        assertFalse(name.contains("\r") || name.contains("\n") || name.contains(":"), name);
    }

    @Test
    void theChannelReChecksTheCaps() {
        System.setProperty(MailAttachments.MAX_ATTACHMENT_PROPERTY, "2");
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CH.message(n(),
                    "ops@example.com", null, List.of(new MailAttachment("a.csv", "text/csv", new byte[3]))));
            assertTrue(e.getMessage().contains("cap"), e.getMessage());
        } finally {
            System.clearProperty(MailAttachments.MAX_ATTACHMENT_PROPERTY);
        }
    }
}
