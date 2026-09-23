package com.gamma.parse;

import com.gamma.config.safety.PathJail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static com.gamma.util.Values.trimOrEmpty;

/**
 * Where an ASN.1 module's source comes from — the ONE rule the preview ({@link Asn1ParserPlugin}) and
 * the ingest ({@code Asn1RecordIngester}) share, so a grammar that previews is the grammar that ingests.
 *
 * <p>Operator decision 2026-09-23 (BACKLOG "Parsing (Stage-1)"): a stored module is a path-jailed
 * {@code .asn} FILE under the Space's config, not a registry kind. Two spellings, one precedence:
 * <ol>
 *   <li><b>inline text wins</b> when both are set — the "parsing: keys win" overlay rule the ingester
 *       already applied;</li>
 *   <li>otherwise the <b>file reference</b> is jailed with {@link PathJail#requireUnderAny} against
 *       {@link PathJail#allowedRoots()} — relative refs resolve exactly as every other config ref the
 *       ingester reads does (see {@link PathJail}'s class doc) — BEFORE any readability probe, so an
 *       escaping ref is refused outright rather than reported "not readable" (which would leak whether
 *       a path outside the roots exists).</li>
 * </ol>
 *
 * <p>⚠ <b>The extension is enforced ({@code .asn} / {@code .asn1}).</b> The preview route is
 * compute-only and carries no capability, and a compile error can echo the text it choked on — so an
 * unrestricted ref would let a preview read any file under the roots (a connection or secrets TOON)
 * back through an error message. A grammar reference names a grammar module, nothing else.
 */
public final class Asn1GrammarSource {

    private Asn1GrammarSource() {
    }

    /** The resolved module: its X.680 source and a label for error messages (the field, or the file). */
    public record Module(String source, String label) {
    }

    /**
     * @param text      the inline module text, or null/blank
     * @param textField the config key {@code text} came from, for messages
     * @param fileRef   the {@code .asn} file reference, or null/blank
     * @param fileField the config key {@code fileRef} came from, for messages
     * @return the module, or {@code null} when neither spelling is set (the caller decides whether that
     *         is structural-preview mode or an error)
     */
    public static Module resolve(Object text, String textField, Object fileRef, String fileField) throws IOException {
        String inline = trimOrEmpty(text);
        if (!inline.isEmpty()) return new Module(inline, textField);
        String ref = trimOrEmpty(fileRef);
        if (ref.isEmpty()) return null;

        Path file = PathJail.requireUnderAny(PathJail.allowedRoots(), ref, fileField);
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".asn") && !name.endsWith(".asn1"))
            throw new IllegalArgumentException(fileField + " must name an ASN.1 module file (.asn or .asn1), got: " + ref);
        if (!Files.isRegularFile(file) || !Files.isReadable(file))
            throw new IllegalArgumentException(fileField + " not readable: " + file);
        return new Module(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }
}
