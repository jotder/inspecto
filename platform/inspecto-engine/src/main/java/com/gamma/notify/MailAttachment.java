package com.gamma.notify;

import com.gamma.api.PublicApi;

import java.util.Objects;

/**
 * One file carried by a mail (ASSURE-XLSX-ATTACHMENTS-1, reverses {@code BI-4}'s "path, not attachment").
 *
 * <p>⛔ <b>Build one with {@link MailAttachments#fromRunArtifact}</b>, not the constructor, whenever the bytes
 * come from disk: that is where the path jail, the {@code *.secrets} refusal, the content-type allowlist and
 * the size cap are applied. The canonical constructor sanitises the filename and nothing else, so a caller
 * holding bytes it produced in memory is still held to the header-injection rule.
 *
 * @param filename    the name the recipient sees — sanitised here (no CR/LF, quotes, path separators)
 * @param contentType a MIME type from {@link MailAttachments#CONTENT_TYPES}
 * @param content     the bytes; not copied, so the caller must not mutate them after handing them over
 * @since 5.0.0
 */
@PublicApi(since = "5.0.0")
public record MailAttachment(String filename, String contentType, byte[] content) {

    public MailAttachment {
        filename = MailAttachments.sanitizeFilename(filename);
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
    }

    /** Size in bytes. */
    public long size() { return content.length; }
}
