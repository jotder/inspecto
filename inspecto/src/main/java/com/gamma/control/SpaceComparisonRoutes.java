package com.gamma.control;

import com.gamma.job.JobService;
import com.gamma.job.SpaceStorageAccess;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /space-comparisons} — compare the storage growth of two or more hosted Spaces
 * ({@code space.comparison} Job Type; space-comparison design, decided 2026-09-24).
 *
 * <p><b>This route IS the authorized path across the Space boundary.</b> In-process code can reach any hosted
 * Space, so the engine never resolves one: this handler checks {@code canAdminister} (design §5 Q2 — a
 * cross-Space aggregate crosses the isolation that "reads are open" rests on), and only THEN resolves the
 * named Spaces and hands their data roots to the run as an explicit {@link SpaceStorageAccess} grant. An
 * authored or scheduled {@code space.comparison} job gets no such grant and can read only its own Space.
 * ⛔ Do not resolve a Space before the capability gate, and do not widen the grant beyond the ids the caller
 * named (design §5 Q1: an explicit list, no "every registered Space").
 *
 * <p>Asynchronous, as {@code POST /datasets/{id}/materialize}: {@code 202} + {@code runId} + a
 * {@code Location} to poll. Nothing is written (design §5 Q3) — the result is the Run message plus a
 * {@code space.comparison.completed} signal in the requesting Space.
 *
 * <p>Gates, fail-closed and in order: no {@code canAdminister} → 403 (the capability wrapper) · a malformed
 * body (fewer than two ids, a duplicate, an invalid id, a non-positive bound) → 422 · an id that is not a
 * hosted Space → 404 · a hosted Space with no data root → 422 · a comparison already running here → 409.
 * No write-root gate: the run reads data roots only.
 */
final class SpaceComparisonRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.post("/space-comparisons",
                ApiContext.withCapability("canAdminister", (e, m) -> compare(api, e)));
    }

    private Object compare(ApiContext api, HttpExchange ex) throws IOException {
        Map<String, Object> body = api.body(ex);
        List<String> ids = ids(body.get("spaces"));
        if (ids.size() < 2)
            throw new ApiException(422, "a comparison needs 'spaces': an array of at least two Space ids");
        if (new LinkedHashSet<>(ids).size() != ids.size())
            throw new ApiException(422, "'spaces' names a Space twice: " + ids);
        for (String id : ids)
            if (!SpaceId.isValid(id)) throw new ApiException(422, "'" + id + "' is not a valid Space id");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("spaces", String.join(",", ids));
        for (String key : new String[] {"window_days", "top"}) {
            String v = ApiContext.str(body, key);
            if (v == null) continue;
            int n;
            try {
                n = Integer.parseInt(v.trim());
            } catch (NumberFormatException nfe) {
                throw new ApiException(422, "'" + key + "' must be a positive integer, got '" + v + "'");
            }
            if (n < 1) throw new ApiException(422, "'" + key + "' must be a positive integer, got " + n);
            params.put(key, Integer.toString(n));
        }
        List<String> axes = ids(body.get("axes"));
        if (!axes.isEmpty()) params.put("axes", String.join(",", axes));

        // Only past the capability gate: resolve exactly the named Spaces, nothing else.
        Map<String, Path> roots = new LinkedHashMap<>();
        for (String id : ids) {
            SpaceContext space = api.spaces().space(SpaceId.of(id))
                    .orElseThrow(() -> new ApiException(404, "no hosted Space '" + id + "'"));
            Path root = space.service().dataRoot();
            if (root == null) throw new ApiException(422, "Space '" + id + "' has no data root to compare");
            roots.put(id, root);
        }

        JobService jobs = api.service().jobServiceOrCreate();
        WriteGates.conflictIf(jobs.isRunning(JobService.SPACE_COMPARISON_RUN),
                "a space comparison is already running in this Space");
        String runId = jobs.triggerSpaceComparisonRun(params, SpaceStorageAccess.granting(roots),
                ApiContext.actor(ex));

        ex.getResponseHeaders().set("Location", "/api/v1/jobs/runs/" + runId);
        return ApiContext.respondJson(ex, 202, Map.of("runId", runId, "spaces", ids, "status", "running"));
    }

    /** A JSON array (or a CSV string) of non-blank strings; anything else is empty. */
    private static List<String> ids(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
        } else if (raw instanceof String s) {
            for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
        }
        return out;
    }
}
