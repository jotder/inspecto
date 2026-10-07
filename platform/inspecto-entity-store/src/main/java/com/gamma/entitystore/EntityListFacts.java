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

    public static boolean masked(Path root, EntityRegistry.EntityList l) {
        return switch (LinkAnalysisSettings.forRoot(root).effectiveMaskingMode()) {
            case "none" -> false;
            case "all" -> true;
            default -> type(root, l.entityType()).map(EntityTypes.EntityType::masked).orElse(true);
        };
    }
}
