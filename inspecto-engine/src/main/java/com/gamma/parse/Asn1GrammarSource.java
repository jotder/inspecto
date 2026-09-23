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
 *   <li>otherwise the <b>file reference</b> must name an ASN.1 module ({@code .asn} / {@code .asn1}), is
 *       resolved like every other config ref — {@link PathJail#resolveConfigRef} against {@code base}
 *       ({@code SCHEMA-FILE-RESOLVES-AGAINST-CWD-1}) — and is jailed ONCE with
 *       {@link PathJail#requireUnderAny} against {@link PathJail#allowedRoots()} BEFORE the readability
 *       probe, so an escaping ref is refused outright rather than reported "not readable" (which would
 *       leak whether a path outside the roots exists). The resolver's own existence check (beside the
 *       base, or the refused ambiguous working-directory spelling) runs before the jail, which is why the
 *       extension is checked before either — it only ever probes a module name.</li>
 * </ol>
 *
 * <p><b>The base.</b> The ingest passes the path {@code PipelineConfigParser} already resolved beside the
 * Pipeline's own config file ({@code Schemas.ingesterGrammar()}, absolute — so the base is moot there). The
 * parser resolves and does NOT jail: this class is the only jail on either path. The preview has no config
 * file, so it passes the directory the Pipeline's config file lives in when the caller names one (the
 * drawer sends its Pipeline's {@code subdir}) — the same base, so the same spelling — and only with no
 * Pipeline context the Space config root ({@code BUNDLE-ASN1-GRAMMAR-FILE-1}).
 *
 * <p>⚠ <b>The extension is enforced first ({@code .asn} / {@code .asn1}), before anything touches the
 * disk.</b> The preview route is compute-only and carries no capability, and a compile error can echo the
 * text it choked on — so an unrestricted ref would let a preview read any file under the roots (a
 * connection or secrets TOON) back through an error message; checking the name first also means a
 * non-module ref is refused without so much as an existence probe. A grammar reference names a grammar
 * module, nothing else.
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
     * @param fileRef   the {@code .asn} file reference (a {@code String} or a resolved {@code Path}), or null/blank
     * @param fileField the config key {@code fileRef} came from, for messages
     * @param base      what a relative {@code fileRef} resolves against; {@code null} keeps the
     *                  working-directory reading ({@link PathJail#resolveConfigRef}'s no-home rule)
     * @return the module, or {@code null} when neither spelling is set (the caller decides whether that
     *         is structural-preview mode or an error)
     */
    public static Module resolve(Object text, String textField, Object fileRef, String fileField, Path base)
            throws IOException {
        String inline = trimOrEmpty(text);
        if (!inline.isEmpty()) return new Module(inline, textField);
        String ref = trimOrEmpty(fileRef == null ? null : fileRef.toString());
        if (ref.isEmpty()) return null;

        String name = ref.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (!name.endsWith(".asn") && !name.endsWith(".asn1"))
            throw new IllegalArgumentException(fileField + " must name an ASN.1 module file (.asn or .asn1), got: " + ref);
        Path file = PathJail.requireUnderAny(PathJail.allowedRoots(),
                PathJail.resolveConfigRef(base, ref, fileField).toString(), fileField);
        if (!Files.isRegularFile(file) || !Files.isReadable(file))
            throw new IllegalArgumentException(fileField + " not readable: " + file);
        return new Module(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }
}
