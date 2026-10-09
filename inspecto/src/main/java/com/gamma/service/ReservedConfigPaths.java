package com.gamma.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The files under a Space config root (which is also the assist write root) that NO import may write, whatever
 * its gate and whoever asks (`SEC-IMPORT-ROLES-ESCALATION-1`). Each one is read by FIXED name by a loader whose
 * own route has a narrower gate than {@code canAuthorWorkbench}, or is state no author may forge:
 * <ul>
 *   <li>identity and governance — {@code roles.toon} (the role → capability table the Authenticator resolves:
 *       an import carrying it could grant its sender any capability), {@code demo-users.toon} (Demo sign-in's
 *       user → role table, read from every Space's config root: a {@code roles: super} Demo User),
 *       {@code access-policies.toon}, the Access Catalog and Access Profiles ({@code registry/access-catalog/},
 *       {@code registry/access-profiles/}, {@code canConfigureAccess}), {@code approval.toon} and
 *       {@code pending-changes/} (reserved for the maker-checker policy and its records), {@code action-requests/}
 *       (the signed Action Request records, `ASSURE-ACTION-REQUESTS-1`), {@code screening-hits/} (the signed
 *       Screening Hit records and their decisions, `SCREENING-1`) and {@code egress.toon} (their egress
 *       allowlist — an import that widened it would be an SSRF door), {@code agent/} (the
 *       assist agent's autonomy {@code policy.json}, {@code approvals.jsonl} and run logs), and the Data
 *       Exchange {@code offers.toon} / {@code grants.toon};</li>
 *   <li>Space settings documents — {@code branding.toon}, {@code geo.toon}, {@code link-analysis.toon},
 *       {@code pipeline-history.toon}, {@code timezone.toon}, {@code icon-map.toon}, {@code scheduler.toon}, {@code nav-menus.toon},
 *       {@code notification-preferences.toon}, {@code partition.toon}, {@code space.toon};</li>
 *   <li>operational state — {@code rename.journal} (what {@code /pipelines/rename/resume} acts on),
 *       {@code recon-state/}, {@code expectation-baselines/} (audited accept/clear ops), the config history
 *       {@code .history/} anywhere, {@code audit/} (the sealed, hash-chained logs — Link Analysis snapshots,
 *       the Entity Fact log — where a forged file is a broken chain), and {@code dataset-publications.tsv}.</li>
 * </ul>
 * Matching is case-insensitive on the normalised, {@code /}-separated config-relative path. ⚠ This is the
 * DENYLIST layer only: {@link ImportPaths} also applies segment rules, a shape allowlist and a real-path check,
 * because a string comparison alone is walked around by Windows aliases ({@code roles.toon.}, {@code ROLES~1.TOO}).
 * {@code ImportLoaderInventoryTest} fails when a loader reads a new fixed name this list neither covers nor
 * allow-lists with a reason.
 */
public final class ReservedConfigPaths {

    private ReservedConfigPaths() {}

    /** Reserved files, matched at the config root. */
    static final Set<String> FILES = Set.of(
            "roles.toon", "demo-users.toon", "access-policies.toon", "approval.toon", "egress.toon", "modules.toon", "approvers.toon", "publication-destinations.toon", "mail-attachments.toon", "attach-approvals.json", "offers.toon", "grants.toon",
            "branding.toon", "geo.toon", "link-analysis.toon", "pipeline-history.toon", "timezone.toon", "icon-map.toon",
            "scheduler.toon", "nav-menus.toon", "notification-preferences.toon", "partition.toon", "space.toon",
            "rename.journal", "dataset-publications.tsv",
            "day-manifest.tsv",   // LA-DAILY-INGEST-1 T8 per-day delivery manifest; a forged copy could fake or hide re-delivery/gap signals (operator, 2026-10-06)
            "safety-policy.toon",   // DUCKLE-C6 Space Safety Policy tier: an import must not narrow, brick or re-moded it
            "case-link.json");   // LA-24: an imported copy would grant a Case team access (operator 2026-09-30)

    /** Reserved directories (a prefix of the config-relative path). */
    static final List<String> DIRS = List.of(
            "pending-changes/", "action-requests/", "screening-hits/", "publication-approvals/", "recon-state/", ".history/", "audit/", "agent/", "expectation-baselines/",
            "registry/access-catalog/", "registry/access-profiles/");

    /** Component kinds no bundle may carry — the access config, gated {@code canConfigureAccess} on its own routes. */
    public static final Set<String> KINDS = Set.of("access-catalog", "access-profile");

    /** Whether {@code relPath} (config-relative; a directory may be given with or without a trailing '/') is reserved. */
    public static boolean reserved(String relPath) {
        String p;
        try {
            // normalised first: "registry/../roles.toon" IS roles.toon
            p = java.nio.file.Path.of(relPath.replace('\\', '/')).normalize().toString()
                    .replace('\\', '/').toLowerCase(Locale.ROOT);
        } catch (RuntimeException unparseable) {
            return true;   // fail closed: a path that cannot be read cannot be shown safe
        }
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        if (FILES.contains(p)) return true;
        for (String d : DIRS) if (p.startsWith(d) || (p + "/").equals(d)) return true;
        return p.contains("/.history/") || p.startsWith(".history/") || p.endsWith("/.history") || p.equals(".history");
    }

    /** The reserved paths among {@code relPaths}, in order. */
    public static List<String> reservedAmong(Iterable<String> relPaths) {
        List<String> out = new ArrayList<>();
        for (String p : relPaths) if (reserved(p)) out.add(p);
        return out;
    }
}
