package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.la.core.InvestigationStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The cheap identity of one Working Set answer (PERF-P4-3): the validator for {@code If-None-Match} and the key of
 * {@link WorkingSetRoutes}' relation cache. It is built from the store's {@code logToken} (a stat, never a read of the
 * log), so an unchanged log costs no log read, no hash and no evaluation.
 *
 * <p>Everything that changes the body is in it: the log (the scope's token, plus the main log's token and fork step for a
 * Draft), which table, the page ({@code limit}/{@code offset}), the {@code at} pin, and the masking profile in force -
 * the Space's mode and Entity Types and the bound columns' classes. ⛔ The profile is derived at request time: the same
 * log under a tighter masking is a different body, so it must be a different tag.
 *
 * <p>The tag is opaque and carries no id or path (the cache key is hashed). It is only ever compared AFTER the caller's
 * gate: {@link WorkingSetRoutes#serve} runs behind {@code InvestigationRoutes.openForRead}, so a 304 is never an answer
 * to a caller the gate would refuse.
 */
final class WorkingSetEtag {

    private WorkingSetEtag() {}

    /** The store's change token for the view's log: for a Draft, the main log's too (its prefix is part of the answer). */
    static String logToken(InvestigationRoutes.Inv inv) throws IOException {
        String own = inv.store().logToken(inv.scope());
        if (inv.draft() == null) return own;
        return inv.store().logToken(InvestigationStore.Scope.main(inv.id())) + "|" + inv.draft().baseStep() + "|" + own;
    }

    /** What decides which ids are masked, other than the log itself: mode, Entity Types and (typed mode) the bound columns' classes. */
    static String maskingProfile(InvestigationRoutes.Inv inv) {
        LinkAnalysisSettings settings = LinkAnalysisSettings.forRoot(inv.writeRoot());
        String mode = settings.effectiveMaskingMode();
        List<EntityTypes.EntityType> types = settings.effectiveEntityTypes();
        String columns = !"none".equals(mode) && !"all".equals(mode) ? String.valueOf(EntityMasking.maskedColumns(inv, types)) : "";
        return mode + "|" + types + "|" + columns;
    }

    /** The strong tag for one request answer. {@code token} is {@link #logToken} read once by the caller; {@code contentKey} is the relation key (the log SHA-256 last verified), so a tag never outlives a content re-validation that found a different log. */
    static String of(InvestigationRoutes.Inv inv, String token, String contentKey, String of, int limit, int offset, int at) {
        String raw = String.join("\u0000", inv.cacheKey(), token, contentKey, of, Integer.toString(limit), Integer.toString(offset),
                Integer.toString(at), inv.draft() == null ? "" : inv.draft().draftId(), maskingProfile(inv));
        return "\"" + hash(raw) + "\"";
    }

    /** The relation cache key: the log's identity and the pin, never the page or the masking (those apply after the cache). */
    static String relationKey(InvestigationRoutes.Inv inv, String token, int at) {
        return inv.cacheKey() + "\u0000" + hash(token) + (at < 0 ? "" : "@" + at);
    }

    /** True when the caller's {@code If-None-Match} holds {@code etag} ({@code *} included; a weak prefix is ignored). */
    static boolean isFresh(HttpExchange ex, String etag) {
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm == null) return false;
        if (inm.trim().equals("*")) return true;
        for (String tag : inm.split(",")) {
            String t = tag.trim();
            if (t.startsWith("W/")) t = t.substring(2);
            if (t.equals(etag)) return true;
        }
        return false;
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
