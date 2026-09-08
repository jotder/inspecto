package com.gamma.opsapi;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.RouteModule;

import com.gamma.ops.queue.Queue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Work-queue routes ({@code /queues*}, INC-4): the team buckets incidents are routed into. Read is open
 * (operational visibility); create/update authors config, so it requires {@code canAuthorWorkbench}
 * (a no-op on Personal). Members + routing strategy come from the body, mirroring {@code *_queue.toon}.
 */
public final class QueueRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/queues", (e, m) -> OpsEngine.of(api).queues().stream().map(Queue::toMap).toList());
        api.get("/queues/([^/]+)", (e, m) -> queueById(api, ApiContext.name(m)));
        api.post("/queues", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> createQueue(api, api.body(e))));
    }

    /** {@code GET /queues/{id}} — the queue, or 404. */
    private Object queueById(ApiContext api, String id) {
        return OpsEngine.of(api).queue(id).map(Queue::toMap)
                .orElseThrow(() -> new ApiException(404, "no queue with id '" + id + "'"));
    }

    /**
     * {@code POST /queues} (INC-4) — create or replace a queue. Body {@code {id, name?, description?,
     * members?[], routing?}} where {@code routing} is {@code round_robin|least_loaded|manual}. Missing/blank
     * {@code id} → 400; a bad routing value → 400.
     */
    private Object createQueue(ApiContext api, Map<String, Object> body) {
        if (ApiContext.str(body, "id") == null) throw new ApiException(400, "body must include 'id'");
        Map<String, Object> block = new LinkedHashMap<>(body);
        // Normalize members to a list of strings for Queue.fromMap (tolerate a single string).
        if (body.get("members") instanceof String one) block.put("members", List.of(one));
        try {
            return OpsEngine.of(api).registerQueue(Queue.fromMap(block)).toMap();
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }
    }
}
