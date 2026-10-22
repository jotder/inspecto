package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.util.OnboardingCounters;
import com.gamma.util.OnboardingCounters.Event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The <b>onboarding counters</b> ({@code LA-ONBOARD-1} OB-13, A-10): always on, local, never sent anywhere. Core, every
 * edition. At boot the counters file is installed in the server-wide document home (the same home as
 * {@code scheduler.toon} and {@code la-limits.toon}: {@code -Dsystem.config.dir}, else the spaces container root, else the
 * sole Space's write root) and a {@code launch} is recorded; Space create, the Add data commit and the first graph record
 * where they happen ({@link OnboardingCounters}).
 *
 * <ul>
 *   <li>{@code GET /onboarding/metrics} - what the counters say (when each milestone first happened, seconds from the first
 *       launch, unfinished steps). {@code canAdminister}; with no sign-in anyone.</li>
 *   <li>{@code POST /onboarding/events {event, step?}} - the steps only the browser sees: {@code guide_completed} and
 *       {@code step_abandoned} with a step of the closed vocabulary. Anything else is 422; nothing the body carries beyond
 *       those two words is kept. Rate-limited on the standard bucket; open to any caller (a beginner is who reports it).</li>
 * </ul>
 */
final class OnboardingRoutes implements RouteModule {

    /** The counters file, in the server-wide document home. */
    static final String FILE = "onboarding-counters.tsv";

    @Override
    public void register(ApiContext api) {
        installAtBoot(api);
        // ⚠ String LITERALS on purpose (CapabilityManifestTest's scanner).
        api.get("/onboarding/metrics", ApiContext.withCapability("canAdminister", (e, m) -> OnboardingCounters.metrics()));
        api.post("/onboarding/events", (e, m) -> report(api.body(e)));
    }

    /** The standard rate-limit bucket covers the browser's reports. */
    static boolean isRateLimited(String method, String path) {
        return "POST".equals(method) && path.equals("/onboarding/events");
    }

    private static void installAtBoot(ApiContext api) {
        try {
            OnboardingCounters.install(SchedulerRoutes.systemDocPath(api, FILE));
        } catch (RuntimeException e) {
            OnboardingCounters.install(null);   // no home: counting is off, the metrics say so
        }
        OnboardingCounters.record(Event.LAUNCH);
    }

    private static Map<String, Object> report(Map<String, Object> body) {
        String word = ApiContext.str(body, "event");
        Event event = Event.of(word);
        if (event == null || !OnboardingCounters.REPORTABLE.contains(event))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "event must be one of " + words() + (word == null ? "" : ", got '" + clip(word) + "'"));
        String step = ApiContext.str(body, "step");
        if (event == Event.STEP_ABANDONED && (step == null || !OnboardingCounters.STEPS.contains(step)))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "step must be one of " + new TreeSet<>(OnboardingCounters.STEPS));
        OnboardingCounters.record(event, step);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recorded", event.word());
        return out;
    }

    private static Set<String> words() {
        Set<String> s = new TreeSet<>();
        for (Event e : OnboardingCounters.REPORTABLE) s.add(e.word());
        return s;
    }

    /** A refused word is echoed short, so a 422 never becomes a store for what someone typed. */
    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
