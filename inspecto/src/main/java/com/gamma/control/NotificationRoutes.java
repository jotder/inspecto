package com.gamma.control;

import com.gamma.notify.ChannelConfig;
import com.gamma.notify.Notification;
import com.gamma.notify.NotificationReadState;
import com.gamma.notify.NotificationRule;
import com.gamma.notify.NotificationService;
import com.gamma.notify.NotificationState;
import com.gamma.notify.NotificationStore;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * In-app notification feed routes ({@code /notifications*}, Phase B2): the bell-icon feed, the unread
 * badge count, and the read/read-all/delete state actions. The feed is mutable (unlike the append-only
 * {@code /events*}), so these are ordinary state transitions on the {@link NotificationStore}. The
 * real-time push counterpart ({@code GET /notifications/stream}, SSE) is registered separately (B3).
 */
final class NotificationRoutes implements RouteModule {

    /** SSE heartbeat cadence — a comment frame so an idle stream stays alive and disconnects surface. */
    private static final long HEARTBEAT_SECONDS = 15;

    @Override
    public void register(ApiContext api) {
        api.get("/notifications", (e, m) -> feed(api, e));
        api.get("/notifications/stream", (e, m) -> stream(api, e));
        api.get("/notifications/unread-count", (e, m) -> Map.of("count", unreadCount(api, ApiContext.actor(e))));
        // Read state is PER READER (operator, 2026-09-25): each caller marks its own notifications read /
        // unread, so these three are self-service — open to any authenticated caller, and they touch only
        // the caller's NotificationReadState marks. See read() for the one shared side effect.
        api.post("/notifications/read-all", (e, m) -> Map.of("updated", readAll(api, ApiContext.actor(e))));
        api.post("/notifications/([^/]+)/read", (e, m) -> read(api, ApiContext.actor(e), ApiContext.name(m)));
        api.post("/notifications/([^/]+)/unread", (e, m) -> unread(api, ApiContext.actor(e), ApiContext.name(m)));
        // ⚠ The archive ("delete") and the preference grid are ONE shared state per Space (neither store
        // has a recipient), so each write below changes every user's feed or delivery — admin-gated, not
        // "self-service" (SEC review F2). Reads stay open.
        api.get("/notifications/preferences", (e, m) -> api.service().notificationPreferences().grid());
        api.put("/notifications/preferences", ApiContext.withCapability("canAdminister",
                (e, m) -> savePreferences(api, api.body(e))));
        // Channel destinations admin CRUD (registered before the /notifications/{id} routes below, which
        // only match a single segment — "channels/{id}" is two, so there's no collision either way).
        api.get("/notifications/channels", (e, m) -> listChannels(api));
        api.post("/notifications/channels", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> createChannel(api, api.body(e))));
        api.put("/notifications/channels/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> updateChannel(api, ApiContext.name(m), api.body(e))));
        api.delete("/notifications/channels/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> deleteChannel(api, ApiContext.name(m))));
        // Authored notification rules admin CRUD (same shape as channels above; registered before the
        // /notifications/{id} routes below for the same reason — "rules/{id}" is two segments).
        api.get("/notifications/rules", (e, m) -> listRules(api));
        api.post("/notifications/rules", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> createRule(api, api.body(e))));
        api.put("/notifications/rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> updateRule(api, ApiContext.name(m), api.body(e))));
        api.delete("/notifications/rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> deleteRule(api, ApiContext.name(m))));
        // ⚠ The archive-by-id catch-all, and it MUST stay last in this module AND must not swallow a
        // sibling sub-resource. Route matching is first-match in registration order across modules
        // (ControlApi's RouteModule list), and NotificationRoutes registers before DeliveryStatusRoutes —
        // so without the negative lookahead, `DELETE /notifications/suppressions` matched HERE and came
        // back "no notification 'suppressions'". Found by ControlApiSuppressionsTest, 2026-09-07.
        // ⛔ Any future exact `/notifications/<word>` DELETE in another module must be added to this
        // lookahead; a literal path cannot otherwise outrank a parameterised one that was registered first.
        api.delete("/notifications/(?!suppressions$)([^/]+)", ApiContext.withCapability("canAdminister", (e, m) -> {
            if (!store(api).archive(ApiContext.name(m)))
                throw new ApiException(404, ErrorCodes.NOT_FOUND, "no notification '" + ApiContext.name(m) + "'");
            return Map.of("id", ApiContext.name(m), "deleted", true);
        }));
    }

    /**
     * {@code PUT /notifications/preferences} — apply the edited grid. Body: {@code {"preferences":[{category,
     * channels:{inApp,email}}, …]}}. Critical/unknown categories are ignored by the store (locked); the full
     * refreshed grid is returned.
     */
    @SuppressWarnings("unchecked")
    private static Object savePreferences(ApiContext api, Map<String, Object> body) {
        var prefs = api.service().notificationPreferences();
        Object rows = body.get("preferences");
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> r)) continue;
                Object category = r.get("category");
                if (!(r.get("channels") instanceof Map<?, ?> channels) || category == null) continue;
                Map<String, Boolean> toggles = new java.util.LinkedHashMap<>();
                channels.forEach((k, v) -> { if (v instanceof Boolean b) toggles.put(String.valueOf(k), b); });
                prefs.set(String.valueOf(category), toggles);
            }
        }
        return prefs.grid();
    }

    private static NotificationStore store(ApiContext api) {
        return api.service().notifications();
    }

    private static NotificationReadState readState(ApiContext api) {
        return api.service().notificationReadState();
    }

    /** Every active notification — the feed is bounded by the store itself, so this is never unbounded. */
    private static List<Notification> active(ApiContext api) {
        return store(api).recent(Integer.MAX_VALUE);
    }

    /**
     * One notification as {@code reader} sees it: {@link Notification#toMap()} with {@code state} /
     * {@code readAt} taken from the reader's own marks, plus a {@code read} flag. The store's own READ
     * state is never reported — it is not any one reader's (see {@link #read}).
     */
    private static Map<String, Object> view(ApiContext api, String reader, Notification n) {
        Map<String, Object> m = n.toMap();
        Long readAt = readState(api).readAt(reader, n.id());
        if (n.state() != NotificationState.ARCHIVED)
            m.put("state", (readAt == null ? NotificationState.UNREAD : NotificationState.READ).name());
        m.put("readAt", readAt);
        m.put("read", readAt != null);
        return m;
    }

    private static long unreadCount(ApiContext api, String reader) {
        return active(api).stream().filter(n -> readState(api).readAt(reader, n.id()) == null).count();
    }

    /**
     * {@code POST /notifications/{id}/read} — mark it read FOR THE CALLER. 404 unknown id.
     * ⚠ It also acknowledges the notification in the shared store, which is what re-opens the dispatcher's
     * dedupe collapse ({@link NotificationStore#hasActiveDuplicate}) so the next identical alert is delivered
     * again, as it was before read state went per-reader. That effect can only let a repeat alert THROUGH,
     * never hide one, and no reader's feed reports it.
     */
    private static Map<String, Object> read(ApiContext api, String reader, String id) {
        Notification n = existing(api, id);
        readState(api).markRead(reader, id, System.currentTimeMillis());
        store(api).markRead(id);
        return view(api, reader, n);
    }

    /** {@code POST /notifications/{id}/unread} — mark it unread FOR THE CALLER. 404 unknown id. */
    private static Map<String, Object> unread(ApiContext api, String reader, String id) {
        Notification n = existing(api, id);
        readState(api).markUnread(reader, id);
        return view(api, reader, n);
    }

    /** {@code POST /notifications/read-all} — every active notification read FOR THE CALLER; returns how many changed. */
    private static int readAll(ApiContext api, String reader) {
        List<String> ids = active(api).stream().map(Notification::id).toList();
        int changed = readState(api).markAllRead(reader, ids, System.currentTimeMillis());
        store(api).markAllRead();   // the shared dedupe acknowledgement — see read()
        return changed;
    }

    private static Notification existing(ApiContext api, String id) {
        return store(api).get(id).orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no notification '" + id + "'"));
    }

    // ── channel destinations (admin CRUD; C4 — persisted as `channel` components per space) ─────────

    private static final String CHANNEL_TYPE = "channel";

    /** {@code GET /notifications/channels} — the saved channel destinations (empty when no write root). */
    private static Object listChannels(ApiContext api) {
        Path root = api.writeRoot();
        if (root == null) return List.of();
        return new ComponentStore(root.resolve("registry")).list(CHANNEL_TYPE).stream()
                .map(ComponentRegistry.Component::content).toList();
    }

    /** {@code POST /notifications/channels} — create; 422 missing fields, 409 duplicate id. */
    private static Object createChannel(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = channelStore(api);
        ChannelConfig ch = parse(body, System.currentTimeMillis());
        if (RouteErrors.exists(store, CHANNEL_TYPE, ch.id()))
            throw new ApiException(409, ErrorCodes.CONFLICT, "channel '" + ch.id() + "' already exists (use PUT to update)");
        return write(store, ch.id(), ch.toMap());
    }

    /** {@code PUT /notifications/channels/{id}} — replace; 404 unknown. The id + createdAt are immutable. */
    private static Object updateChannel(ApiContext api, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = channelStore(api);
        Map<String, Object> existing = RouteErrors.existing(store, CHANNEL_TYPE, "channel", id);
        long createdAt = existing.get("createdAt") instanceof Number n ? n.longValue() : System.currentTimeMillis();
        Map<String, Object> patched = new LinkedHashMap<>(existing);
        patched.putAll(body);
        patched.put("id", id);                 // storage key is bound from the path, never a stale body id
        patched.put("createdAt", createdAt);   // preserve the original creation stamp
        return write(store, id, parse(patched, createdAt).toMap());
    }

    /** {@code DELETE /notifications/channels/{id}} — 404 unknown, else {@code {deleted:id}}. */
    private static Object deleteChannel(ApiContext api, String id) throws IOException {
        ComponentStore store = channelStore(api);
        RouteErrors.existing(store, CHANNEL_TYPE, "channel", id);
        store.delete(CHANNEL_TYPE, id);
        return Map.of("deleted", id);
    }

    private static ComponentStore channelStore(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "notification channel write").resolve("registry"));
    }

    private static ChannelConfig parse(Map<String, Object> body, long defaultCreatedAt) {
        try {
            return ChannelConfig.fromMap(body, defaultCreatedAt);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    private static Map<String, Object> write(ComponentStore store, String id, Map<String, Object> content)
            throws IOException {
        try {
            return store.write(CHANNEL_TYPE, id, content).content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    // ── authored notification rules (admin CRUD; persisted as `notification-rule` components per space) ──

    private static final String RULE_TYPE = "notification-rule";

    /** {@code GET /notifications/rules} — the operator-authored rules (empty when no write root). */
    private static Object listRules(ApiContext api) {
        Path root = api.writeRoot();
        if (root == null) return List.of();
        return new ComponentStore(root.resolve("registry")).list(RULE_TYPE).stream()
                .map(ComponentRegistry.Component::content).toList();
    }

    /** {@code POST /notifications/rules} — create; 422 missing fields, 409 duplicate id. */
    private static Object createRule(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = ruleStore(api);
        NotificationRule rule = parseRule(body);
        if (existsRule(store, rule.id()))
            throw new ApiException(409, ErrorCodes.CONFLICT, "rule '" + rule.id() + "' already exists (use PUT to update)");
        return writeRule(store, rule.id(), rule.toMap());
    }

    /** {@code PUT /notifications/rules/{id}} — replace; 404 unknown. The id is immutable (bound from the path). */
    private static Object updateRule(ApiContext api, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = ruleStore(api);
        existingRule(store, id);
        Map<String, Object> patched = new LinkedHashMap<>(body);
        patched.put("id", id);   // storage key is bound from the path, never a stale body id
        return writeRule(store, id, parseRule(patched).toMap());
    }

    /** {@code DELETE /notifications/rules/{id}} — 404 unknown, else {@code {deleted:id}}. */
    private static Object deleteRule(ApiContext api, String id) throws IOException {
        ComponentStore store = ruleStore(api);
        existingRule(store, id);
        store.delete(RULE_TYPE, id);
        return Map.of("deleted", id);
    }

    private static ComponentStore ruleStore(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "notification rule write").resolve("registry"));
    }

    private static NotificationRule parseRule(Map<String, Object> body) {
        try {
            return NotificationRule.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    private static boolean existsRule(ComponentStore store, String id) {
        try {
            return store.exists(RULE_TYPE, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    private static Map<String, Object> existingRule(ComponentStore store, String id) {
        try {
            return store.get(RULE_TYPE, id).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "rule '" + id + "' not found"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    private static Map<String, Object> writeRule(ComponentStore store, String id, Map<String, Object> content)
            throws IOException {
        try {
            return store.write(RULE_TYPE, id, content).content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    /** {@code GET /notifications?limit=} — the active feed, newest-first. */
    private static List<Map<String, Object>> feed(ApiContext api, HttpExchange ex) {
        int limit = ApiContext.parseIntOr(ApiContext.query(ex, "limit"), 50);
        String reader = ApiContext.actor(ex);
        return store(api).recent(limit).stream().map(n -> view(api, reader, n)).toList();
    }

    /**
     * {@code GET /notifications/stream} — Server-Sent Events for real-time delivery. Holds the exchange
     * open and pushes each new notification as a {@code data:} frame, so the bell badge updates without a
     * page reload. The framework-free JDK {@code HttpServer} has no async continuation, so the handler
     * <b>blocks</b> for the connection's lifetime — fine here because the server runs on a virtual-thread
     * executor (a parked virtual thread is cheap) and the auth-free core has a single {@code appUser}.
     * The UI falls back to polling the feed/unread-count if the stream drops.
     */
    private static Object stream(ApiContext api, HttpExchange ex) throws IOException {
        NotificationService svc = api.service().notificationService();
        String reader = ApiContext.actor(ex);
        BlockingQueue<Notification> queue = new LinkedBlockingQueue<>();
        Consumer<Notification> listener = queue::offer;
        Thread me = Thread.currentThread();
        Runnable closer = me::interrupt;        // svc.close() runs this to unblock the poll on shutdown
        // Register before the response is committed so no notification can slip through between the client
        // seeing the headers and the listener being live.
        svc.addListener(listener);
        svc.onClose(closer);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);         // 0 ⇒ chunked; stream an arbitrary number of bytes
        try (OutputStream os = ex.getResponseBody()) {
            writeFrame(os, ": connected\n\n");  // initial comment confirms the stream is live
            while (true) {
                Notification n = queue.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                writeFrame(os, n != null
                        ? "data: " + ApiContext.JSON.writeValueAsString(view(api, reader, n)) + "\n\n"
                        : ": ping\n\n");        // heartbeat keeps the connection warm + surfaces a disconnect
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); // service shutting down — end the stream
        } catch (IOException disconnect) {
            // client went away — normal SSE termination
        } finally {
            svc.removeListener(listener);
            svc.removeOnClose(closer);
        }
        return ApiContext.HANDLED;
    }

    private static void writeFrame(OutputStream os, String frame) throws IOException {
        os.write(frame.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
