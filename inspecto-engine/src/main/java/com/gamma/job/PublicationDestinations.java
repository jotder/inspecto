package com.gamma.job;

import com.gamma.util.egress.EgressPolicy;
import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Space's <b>publication destination allowlist</b> (ASSURE-BI-PUBLICATION-1, operator 2026-09-29): the exact
 * hosts a {@code publish.postgres} Job may write to. It sits ON TOP of {@link EgressPolicy}: the egress policy says
 * which address classes a host may resolve to, this list says which destinations a Space publishes Datasets to at
 * all. Persisted as {@value #FILE} in the Space config root ({@code hosts: [bi.example.com]}), written only by
 * {@code PUT /settings/publication-destinations} ({@code canAdminister}, audited), reserved from every import, and
 * EMPTY by default — a Space with no file publishes nowhere. An unreadable file reads as EMPTY (fail closed).
 */
public final class PublicationDestinations {

    private static final Logger log = LoggerFactory.getLogger(PublicationDestinations.class);

    public static final String FILE = "publication-destinations.toon";
    public static final int MAX_ENTRIES = 100;

    private PublicationDestinations() {}

    /** The allowlisted hosts (lower-cased); empty when none or unreadable. */
    public static List<String> hosts(Path root) {
        if (root == null) return List.of();
        Path f = root.resolve(FILE);
        if (!Files.exists(f)) return List.of();
        try {
            List<String> out = new ArrayList<>();
            if (ToonHelper.load(f.toString()).get("hosts") instanceof List<?> l)
                for (Object o : l) out.add(validated(String.valueOf(o)));
            return out;
        } catch (Exception bad) {
            log.warn("[PUBLISH] {} is unreadable or invalid ({}) — no publication destination is allowed", f, bad.getMessage());
            return List.of();
        }
    }

    /** {@code host} trimmed + lower-cased, refused ({@link IllegalArgumentException}) unless a valid host. */
    public static String validated(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        EgressPolicy.checkHost(h);
        return h;
    }

    /** Whether the Space whose config root is {@code root} allows publishing to {@code host} (exact, case-insensitive). */
    public static boolean permits(Path root, String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return hosts(root).contains(h);
    }
}
