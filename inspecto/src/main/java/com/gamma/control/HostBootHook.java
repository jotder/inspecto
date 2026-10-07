package com.gamma.control;

import com.gamma.service.SpaceManager;

/**
 * Contribution point for an <b>optional</b> module that must install host-wide seams (a resolver, a forwarder, a
 * delete fence) once the host has its Spaces. Before this existed such a module did it inside
 * {@link RouteModule#register}, which forced {@code register} to demand a {@link HostContext} and so made the
 * module's routes undrivable on a test double. {@code register} now only registers routes; the installs live here.
 *
 * <p>Lives beside {@link HostContext} (not in {@code inspecto-http-spi}) because its one argument, the
 * {@link SpaceManager}, is a host type. Registered in {@code META-INF/services/com.gamma.control.HostBootHook};
 * discovered fail-soft ({@code OptionalSpi}) and called once by {@link ControlApi}'s constructor, after every route
 * module has registered. A host without such a module boots unchanged.
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface HostBootHook {
    /** Install this module's seams over {@code spaces}. Must be idempotent (a host may boot twice in one JVM). */
    void afterRoutes(SpaceManager spaces);
}
