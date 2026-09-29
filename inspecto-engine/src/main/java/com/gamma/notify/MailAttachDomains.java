package com.gamma.notify;

import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.util.ToonHelper;

import java.net.IDN;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Space's <b>attachment recipient domain allowlist</b> (operator decision 2026-09-29, ASSURE-XLSX-ATTACHMENTS-1):
 * a mail that carries an attachment may go only to addresses whose domain is on this list, exactly or as a
 * subdomain. Persisted as {@value #FILE} ({@code allow: [example.com, finance.example.org]}) in the Space config
 * root; written by {@code PUT /settings/mail-attachments} (canAdminister, audited, reserved from imports).
 *
 * <p>⛔ <b>EMPTY by default, and empty denies.</b> Until an admin names a domain, no attachment leaves the Space at
 * all — {@code attach: true} is effectively off. A missing, unreadable or invalid file reads as empty (fail closed:
 * an empty list only ever denies more). Comparison is on the ASCII (punycode) form, lower-cased, so
 * {@code BÜCHER.example} and {@code xn--bcher-kva.example} are one domain and a homoglyph spelling is not.
 */
public final class MailAttachDomains {

    private MailAttachDomains() {}

    public static final String FILE = "mail-attachments.toon";
    public static final int MAX_ENTRIES = 200;

    /** A domain's comparable form: trimmed, trailing dot dropped, punycode, lower-case. Throws on a non-domain. */
    public static String normalise(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty() || s.contains("@") || s.contains("/") || s.contains(" ") || s.startsWith("*"))
            throw new IllegalArgumentException("'" + raw + "' is not a domain name (write example.com; subdomains are included)");
        String ascii;
        try {
            ascii = IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid domain name: " + bad.getMessage());
        }
        if (!ascii.contains(".")) throw new IllegalArgumentException("'" + raw + "' needs at least one dot");
        return ascii;
    }

    /** Validate + normalise a whole list (the PUT body); throws naming the first bad entry. */
    public static List<String> normaliseAll(List<?> raw) {
        if (raw.size() > MAX_ENTRIES) throw new IllegalArgumentException("at most " + MAX_ENTRIES + " domains");
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            String d = normalise(String.valueOf(o));
            if (!out.contains(d)) out.add(d);
        }
        return out;
    }

    /** The Space's list; EMPTY when there is no root, no file, or the file is unreadable/invalid. */
    public static List<String> entries(Path root) {
        if (root == null) return List.of();
        Path f = root.resolve(FILE);
        if (!Files.isRegularFile(f)) return List.of();
        try {
            Object allow = ToonHelper.load(f.toString()).get("allow");
            return allow instanceof List<?> l ? normaliseAll(l) : List.of();
        } catch (Exception bad) {
            return List.of();
        }
    }

    /** Whether {@code address}'s domain equals, or is a subdomain of, an allowed entry. */
    public static boolean permits(String address, List<String> allowed) {
        if (address == null) return false;
        String a = address.trim();
        int lt = a.lastIndexOf('<');
        if (lt >= 0 && a.endsWith(">")) a = a.substring(lt + 1, a.length() - 1);   // "Name <x@y>"
        int at = a.lastIndexOf('@');
        if (at < 0 || at == a.length() - 1) return false;
        String domain;
        try {
            domain = normalise(a.substring(at + 1));
        } catch (IllegalArgumentException bad) {
            return false;
        }
        for (String d : allowed) if (domain.equals(d) || domain.endsWith("." + d)) return true;
        return false;
    }

    /**
     * Refuse the WHOLE send unless every To and Cc address is allowed by the current Space's list. Called by
     * {@link MailAccess#overChannels()} for every send that carries an attachment.
     */
    public static void requireAllowed(List<String> to, List<String> cc) {
        List<String> allowed = entries(SpaceConfigRoot.current());
        if (allowed.isEmpty())
            throw new IllegalArgumentException("attachments are off in this Space: no recipient domain is allowed yet "
                    + "(an admin sets them under Settings, PUT /settings/mail-attachments)");
        List<String> refused = new ArrayList<>();
        for (List<String> list : List.of(to == null ? List.<String>of() : to, cc == null ? List.<String>of() : cc))
            for (String a : list) if (a != null && !a.isBlank() && !permits(a, allowed)) refused.add(a.trim());
        if (!refused.isEmpty())
            throw new IllegalArgumentException("mail with an attachment refused: " + refused.size()
                    + " recipient(s) outside the allowed domains " + allowed + " — nothing was sent");
    }
}
