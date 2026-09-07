package com.gamma.control;

/**
 * A cohesive group of related control-plane routes. Implementations register their routes onto the
 * shared {@link ApiContext}; {@link ControlApi} composes them and stays a thin host — new feature
 * groups are added without editing the dispatcher (open/closed).
 *
 * <p><b>Public since 2026-09-07 (EDG-01 cell 3a).</b> Until then this, {@link ApiContext}, {@link Handler},
 * {@link ApiException} and {@link WriteGates} were all package-private, so a route group could only ever
 * live in {@code com.gamma.control} — which is why every "not for Personal" feature shipped in every
 * bundle. Now an optional module may implement it and register the class in
 * {@code META-INF/services/com.gamma.control.RouteModule}; {@link ControlApi} discovers such modules with
 * {@link java.util.ServiceLoader} and registers them <b>after</b> the built-in list.
 *
 * <p>⚠ <b>Order is load-bearing and "after" is deliberate.</b> Route matching is first-match in
 * registration order, and {@code ServiceLoader} iteration order is unspecified — so discovered modules are
 * appended, never interleaved, and a discovered route can never outrank a built-in one. A discovered
 * module that registers a {@code (method, pattern)} a built-in already owns is refused at boot
 * ({@link ControlApi}'s duplicate guard) rather than silently losing every match.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface RouteModule {
    void register(ApiContext api);
}
