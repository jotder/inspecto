package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The Space's {@link ApproverRoster} over HTTP — the egress allowlist's shape ({@link EgressRoutes}).
 * <pre>
 *   GET /settings/approvers     {users: [...], groups: [...], applies: bool}
 *   PUT /settings/approvers     replace the lists it names (an absent key keeps its list) — canAdminister,
 *                               validated fail closed (422), audited before/after
 * </pre>
 * <p>⛔ Not under the approval policy (a Space setting, like {@code egress.toon}) and reserved from every import
 * ({@code ReservedConfigPaths}): an import that could add approvers would let its author approve.
 */
final class ApproverRosterRoutes implements RouteModule {

    static final int MAX_ENTRIES = 500;

    @Override
    public void register(ApiContext api) {
        api.get("/settings/approvers", (e, m) -> view(api.writeRoot()));
        api.put("/settings/approvers", ApiContext.withCapability("canAdminister", (e, m) -> replace(api, e, api.body(e))));
    }

    private static Map<String, Object> view(Path root) {
        ApproverRoster.Roster r = ApproverRoster.load(root);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("users", r.users());
        out.put("groups", r.groups());
        out.put("applies", ApproverRoster.applies(root));
        return out;
    }

    private Object replace(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "approver roster write");
        for (String k : body.keySet())
            if (!"users".equals(k) && !"groups".equals(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown key '" + k + "' (expected users, groups)");
        ApproverRoster.Roster before = ApproverRoster.load(root);
        List<String> users = body.containsKey("users") ? list(body, "users") : before.users();
        List<String> groups = body.containsKey("groups") ? list(body, "groups") : before.groups();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("users", users);
        doc.put("groups", groups);
        AtomicFiles.write(root.resolve(ApproverRoster.FILE), JToon.encode(doc).getBytes(StandardCharsets.UTF_8), ".approvers-");
        try {
            EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                    .message(ApiContext.actor(ex) + " changed the approver roster")
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("approver-roster.changed").actionCategory("configuration")
                    .attr("usersBefore", ApiContext.JSON.writeValueAsString(before.users()))
                    .attr("usersAfter", ApiContext.JSON.writeValueAsString(users))
                    .attr("groupsBefore", ApiContext.JSON.writeValueAsString(before.groups()))
                    .attr("groupsAfter", ApiContext.JSON.writeValueAsString(groups)));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best effort, like every audit emit on a request path
        }
        return view(root);
    }

    /** A list of user ids or group names, trimmed and de-duplicated in order; 422 on any bad entry. */
    private static List<String> list(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof List<?> raw))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be a list of strings");
        if (raw.size() > MAX_ENTRIES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "': at most " + MAX_ENTRIES + " entries");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object o : raw) {
            if (!(o instanceof String s))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be a list of strings");
            ApproverRoster.invalidEntry(s).ifPresent(why -> {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "': " + why);
            });
            out.add(s.trim());
        }
        return new ArrayList<>(out);
    }
}
