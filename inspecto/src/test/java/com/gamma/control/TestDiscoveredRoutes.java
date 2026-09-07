package com.gamma.control;

import java.util.Map;

/**
 * A {@link RouteModule} that reaches {@link ControlApi} ONLY through
 * {@code src/test/resources/META-INF/services/com.gamma.control.RouteModule} — never from the hard-coded
 * list. Its existence on every test classpath in this module is what lets {@link RouteModuleDiscoveryTest}
 * prove, over real HTTP, that the ServiceLoader append in {@code ControlApi} works (EDG-01 cell 3a).
 *
 * <p>The path is deliberately one nothing else could register, so it can never collide with a built-in
 * and never shadow one — and being appended after the built-in list, it could not shadow one anyway.
 */
public final class TestDiscoveredRoutes implements RouteModule {

    public static final String PATH = "/test-discovered/ping";

    @Override
    public void register(ApiContext api) {
        api.get(PATH, (e, m) -> Map.of("discovered", true, "via", "META-INF/services"));
    }
}
