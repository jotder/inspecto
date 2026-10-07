package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;

import java.util.regex.Matcher;

/**
 * A matched-route handler: turn the request (+ path captures) into a JSON-serialisable result.
 * Public since 2026-09-07 so a {@link RouteModule} in an optional module can write one (EDG-01 cell 3a).
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
@FunctionalInterface
public interface Handler {
    Object handle(HttpExchange ex, Matcher m) throws Exception;
}
