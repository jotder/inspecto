package com.gamma.control;

import com.gamma.access.WriteGates;
import com.gamma.service.SpaceTestRunner;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SE-6a — the test cases that travel with a Space ({@code docs/okf/product/module/config/spec-space-tests.md}).
 * Reached as {@code /spaces/{id}/tests…}: the Space seam binds the Space and strips the prefix.
 *
 * <p>{@code GET /tests} lists the cases (a read: no capability). {@code POST /tests/run} runs them through the
 * run-to-here engine into scratch storage; it is gated like run-to-here ({@code canAuthorWorkbench}). Gate
 * order: 503 no config root · 400 bad body · 403 a case name that is not a plain name · 404 unknown case ·
 * 422 a malformed case file · 200. A failing case is a 200: the result is the answer.
 */
final class SpaceTestRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/tests", (e, m) -> list(api));
        api.post("/tests/run", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> run(api, api.body(e))));
    }

    private Object list(ApiContext api) throws IOException {
        Path tests = SpaceTestRunner.testsDir(WriteGates.requireWriteRoot(api, "space tests"));
        List<Map<String, Object>> cases = new ArrayList<>();
        for (String name : SpaceTestRunner.list(tests)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", name);
            try {
                SpaceTestRunner.Case k = SpaceTestRunner.load(tests, name);
                c.put("pipeline", k.pipeline());
                c.put("inputs", k.inputs().size());
            } catch (RuntimeException ex) {
                c.put("error", ex.getMessage());
            }
            cases.add(c);
        }
        return Map.of("cases", cases);
    }

    private Object run(ApiContext api, Map<String, Object> body) throws IOException {
        Path tests = SpaceTestRunner.testsDir(WriteGates.requireWriteRoot(api, "space tests"));
        List<String> names = names(body);
        if (names == null) names = SpaceTestRunner.list(tests);
        List<SpaceTestRunner.Case> cases = new ArrayList<>();
        for (String n : names) {
            try {
                cases.add(SpaceTestRunner.load(tests, n));
            } catch (SpaceTestRunner.BadName ex) {
                throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, ex.getMessage());
            } catch (SpaceTestRunner.NoCase ex) {
                throw new ApiException(404, ErrorCodes.NOT_FOUND, ex.getMessage());
            } catch (SpaceTestRunner.Malformed ex) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, ex.getMessage());
            }
        }
        var service = HostContext.of(api).service();
        List<Map<String, Object>> results = new ArrayList<>();
        int failed = 0;
        for (SpaceTestRunner.Case c : cases) {
            SpaceTestRunner.CaseResult r = SpaceTestRunner.run(c, id -> service.configFor(id.toLowerCase(Locale.ROOT)));
            if (!r.passed()) failed++;
            results.add(SpaceTestRoutes.json(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", failed == 0);
        out.put("total", cases.size());
        out.put("failed", failed);
        out.put("cases", results);
        return out;
    }

    static Map<String, Object> json(SpaceTestRunner.CaseResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.name());
        m.put("pipeline", r.pipeline());
        m.put("passed", r.passed());
        if (r.error() != null) m.put("error", r.error());
        List<Map<String, Object>> ls = new ArrayList<>();
        for (SpaceTestRunner.Line l : r.lines()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("expectation", l.name());
            x.put("expected", l.expected());
            x.put("actual", l.actual());
            x.put("passed", l.passed());
            ls.add(x);
        }
        m.put("expectations", ls);
        return m;
    }

    /** The optional {@code cases} body key; {@code null} means every case. */
    private static List<String> names(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("cases");
        if (raw == null) return null;
        if (!(raw instanceof List<?> l))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'cases' must be a list of case names");
        if (l.size() > SpaceTestRunner.MAX_CASES)
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "at most " + SpaceTestRunner.MAX_CASES + " cases per run");
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof String s) || s.isBlank())
                throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'cases' must be a list of case names");
            out.add(s.trim());
        }
        return out;
    }
}
