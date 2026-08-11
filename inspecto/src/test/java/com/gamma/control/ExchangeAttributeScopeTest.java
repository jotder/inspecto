package com.gamma.control;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-EXCHANGE-ATTRS (BACKLOG §1 0-b): nothing stamped on the exchange by one request may be readable
 * by the next.
 *
 * <h3>The fault being guarded against</h3>
 * {@code HttpExchange} attributes are private to the exchange only by default —
 * {@code sun.net.httpserver.ExchangeImpl} falls back to the shared {@code HttpContext} map on any
 * pre-JDK-26 runtime, or a current one started with {@code -Djdk.httpserver.attributes=context}. The
 * deployment bundle's {@code -NoRuntime} flavor explicitly supports "Java 24+", so the shared-map
 * configuration is a supported deployment, not a hypothetical. There, ControlApi's single
 * {@code createContext("/")} means every request shares one map: request A authenticates as alice,
 * request B hits a public path (auth optional, nothing stamped, no 401), and B's
 * {@code getAttribute(ATTR_SUBJECT)} returns <b>alice</b> — flowing into {@code requireCapability},
 * {@code actor()}, and {@code authorize()}. {@code ATTR_RAW_BODY} (the request body) rides the same map.
 *
 * <h3>The fix and what this test pins</h3>
 * {@link ControlApi#clearRequestScope} clears the {@link ControlApi#REQUEST_SCOPED_ATTRS} roster as
 * dispatch's first act (outermost stage, before anything reads an attribute), making a shared-map
 * runtime behave like a private-map one. As with {@code ApiContextV1DerivationTest}, the JDK's map
 * choice is a {@code static final} read at class-init, so the shared-map runtime cannot be reproduced
 * in-process — the test instead drives the clear directly against an exchange pre-populated the way a
 * shared map would be, which holds on <em>every</em> runtime. The reflection test makes the roster
 * self-maintaining: a new {@code ATTR_*} constant on ApiContext that is not also added to the roster
 * fails the build rather than silently leaking.
 */
class ExchangeAttributeScopeTest {

    /** The exact leak from the BACKLOG write-up: a stale Subject surviving into the next request. */
    @Test
    void aStaleSubjectFromAPreviousRequestIsNotReadableAfterTheClear() {
        FakeExchange ex = new FakeExchange("/health");   // request B: public path, nothing re-stamped
        ex.setAttribute(ApiContext.ATTR_SUBJECT, new Subject("alice", Set.of("canOperateRuns")));

        ControlApi.clearRequestScope(ex);

        assertTrue(ApiContext.subject(ex).isEmpty(),
                "request B must not inherit request A's authenticated Subject");
        assertEquals("appUser", ApiContext.actor(ex),
                "with no Subject and no headers, the actor falls back to the historic default — "
                        + "never to the previous request's identity");
    }

    /** Every attribute on the roster is actually cleared — including the other requests' raw body. */
    @Test
    void everyRosterAttributeIsClearedNotJustTheSubject() {
        FakeExchange ex = new FakeExchange("/health");
        for (String attr : ControlApi.REQUEST_SCOPED_ATTRS) ex.setAttribute(attr, "stale-" + attr);

        ControlApi.clearRequestScope(ex);

        for (String attr : ControlApi.REQUEST_SCOPED_ATTRS)
            assertNull(ex.getAttribute(attr), "'" + attr + "' must not survive into the next request");
    }

    /**
     * Roster completeness: every {@code ATTR_*} String constant declared on {@link ApiContext} — the
     * home of the per-exchange attribute contract — is on the clear roster. Someone adding
     * {@code ATTR_FOO} without adding it to {@link ControlApi#REQUEST_SCOPED_ATTRS} gets a red build
     * here, not a cross-request leak on a shared-map runtime. (Attributes owned by other classes —
     * {@code Roles.ATTR_CONFIG_ROOT}, {@code AccessDecider.ATTR_MATCHED_POLICY}, ControlApi's
     * effective-path — are asserted by name below, since their owners are not a single roster source.)
     */
    @Test
    void everyApiContextAttrConstantIsOnTheClearRoster() throws Exception {
        List<String> roster = List.of(ControlApi.REQUEST_SCOPED_ATTRS);
        for (Field f : ApiContext.class.getDeclaredFields()) {
            if (!f.getName().startsWith("ATTR_")) continue;
            assertTrue(Modifier.isStatic(f.getModifiers()) && f.getType() == String.class,
                    "ATTR_* constants are static String attribute names: " + f.getName());
            String name = (String) f.get(null);
            assertTrue(roster.contains(name),
                    "ApiContext." + f.getName() + " (\"" + name + "\") is request-scoped but missing "
                            + "from ControlApi.REQUEST_SCOPED_ATTRS — it would leak across requests on "
                            + "a shared-attribute runtime");
        }
    }

    /** The three roster entries owned outside ApiContext, pinned by name. */
    @Test
    void theExternallyOwnedAttributesAreOnTheRoster() {
        List<String> roster = List.of(ControlApi.REQUEST_SCOPED_ATTRS);
        assertTrue(roster.contains(Roles.ATTR_CONFIG_ROOT));
        assertTrue(roster.contains(AccessDecider.ATTR_MATCHED_POLICY));
        assertTrue(roster.contains("inspecto.effectivePath"),
                "the route-matching path is rewritten per request and must not survive either");
    }

    /**
     * The minimum {@link HttpExchange} carrying a URI and its own attribute map (no mocking framework in
     * this build) — same harness as {@code ApiContextV1DerivationTest}. Pre-populating the map models
     * what a shared {@code HttpContext} map holds when the next request arrives.
     */
    private static final class FakeExchange extends HttpExchange {
        private final URI uri;
        private final Map<String, Object> attributes = new HashMap<>();
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();

        private FakeExchange(String path) { this.uri = URI.create(path); }

        @Override public URI getRequestURI() { return uri; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return -1; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public void setStreams(InputStream i, OutputStream o) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
