package com.gamma.entitystore;

import com.gamma.spi.http.ApiContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.entitystore.EntityTypes;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The host-free read/append helpers over the shared entity fact log, used by the Entity List routes
 * ({@code inspecto-entity-list}) AND by Link Analysis ({@code inspecto-la-api}, {@code inspecto-la-core}), which must not
 * depend on the routes module (LA separation D-1 step 5b). They lived as {@code public static} members of
 * {@code EntityListRoutes}; the behaviour is unchanged.
 */
public final class EntityListFacts {

    public static final Pattern LIST_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    private static final int MAX_REASON = 1_000;

    private EntityListFacts() {}

    public static EntityFactLog.Log read(EntityFactLog log) throws IOException {
        try {
            return log.read();
        } catch (EntityFactLog.BrokenChainException broken) {
            throw new ApiException(500, ErrorCodes.INTEGRITY_VIOLATION, broken.getMessage());
        }
    }

    public static EntityFactLog.Log append(EntityFactLog log, EntityFactLog.Log head, HttpExchange ex, String reason,
                                           String kind, String id, Map<String, Object> payload) throws IOException {
        try {
            return log.append(head, ApiContext.actor(ex), reason, kind, id, payload);
        } catch (FileAlreadyExistsException raced) {
            // Another process appended the same seq between our read and our move — nothing was overwritten.
            throw new ApiException(409, ErrorCodes.CONFLICT, "the identity fact log moved on concurrently; retry");
        }
    }

    public static String reason(Map<String, Object> body) {
        String r = ApiContext.str(body, "reason");
        if (r == null || r.length() > MAX_REASON)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'reason', 1.." + MAX_REASON
                    + " characters");
        return r.trim();
    }

    public static Optional<EntityTypes.EntityType> type(Path root, String id) {
        return LinkAnalysisSettings.forRoot(root).effectiveEntityTypes().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    // ── LA-GROW-EXCLUDE-1 phase 4: admin category exclusion lists (GE-6, GE-8, GE-9, GE-11) ─────────────────────

    /** The grant that owns category lists (create, members, retire, attributes of an {@code ownerKind: admin} list). */
    public static final String MANAGE_CATEGORIES = "canManageExclusionCategories";
    /** The one other grant that sees a restricted list's members: the existing "may see raw keys" (D-U6). */
    public static final String REVEAL = "canRevealLinkEntities";
    /** The reason every auto-applied {@code excludeBy} step records (GE-9). */
    public static final String AUTO_APPLY_REASON = "auto-applied category list";

    /**
     * Whether this request may see the members of a {@code restricted} list (GE-8): a Subject holding
     * {@link #MANAGE_CATEGORIES} or {@link #REVEAL}. No Subject (Basic, or no auth module) is unrestricted, as every
     * capability gate is.
     */
    public static boolean seesRestricted(HttpExchange ex) {
        return ApiContext.subject(ex).map(s -> s.capabilities().contains(MANAGE_CATEGORIES)
                || s.capabilities().contains(REVEAL)).orElse(true);
    }

    /**
     * A route that would disclose membership of {@code l} (a match, a Dataset over it, a seed from it, a screening
     * against it) refuses a restricted list to a caller who may not see its members (GE-8): 403.
     */
    public static void requireSeesMembers(HttpExchange ex, EntityRegistry.EntityList l, String what) {
        if (l.restricted() && !seesRestricted(ex))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "entity list '" + l.id() + "' is restricted: only a holder "
                    + "of " + MANAGE_CATEGORIES + " or " + REVEAL + " may " + what + " it");
    }

    /**
     * A count of nodes a restricted list removed, as a caller who may not see its members reads it (plan §7: removing
     * 1 node from a 3-node graph would name it): rounded to the nearest 5 and never below 5; 0 stays 0.
     */
    public static int roundedCount(int n) {
        if (n <= 0) return 0;
        return Math.max(5, (n + 2) / 5 * 5);
    }

    public static boolean masked(Path root, EntityRegistry.EntityList l) {
        return switch (LinkAnalysisSettings.forRoot(root).effectiveMaskingMode()) {
            case "none" -> false;
            case "all" -> true;
            default -> type(root, l.entityType()).map(EntityTypes.EntityType::masked).orElse(true);
        };
    }
}
