package com.gamma.control;

import com.gamma.acquire.SecretResolver;
import com.gamma.service.OperationalDbReport;
import com.gamma.util.Values;

import java.util.Map;

/**
 * System-level diagnostics for the operator (PG-1 Open 2, Stage 1):
 * <pre>
 *   GET  /system/operational-db        what this deployment is ACTUALLY using, per store family
 *   POST /system/operational-db/test   open a proposed JDBC URL for real and run SELECT 1
 * </pre>
 *
 * <p><b>Read + validate only, by decision (2026-08-15).</b> The UI is served by the process that needs
 * the operational database, so it cannot configure its own dependency and no change could take effect
 * without a restart; persisting a selection here would create a <b>second declaration of the same
 * fact</b> beside {@code -D}. So these two routes answer "what am I using?" and "would this work?", and
 * the operator applies the flags through their own deployment tooling. ⛔ There is deliberately no PUT.
 *
 * <p><b>Gates.</b> Both are {@code canConfigureAccess} — the existing administrative capability; this is
 * infrastructure detail, not workbench authoring. Neither is write-root gated: the read writes nothing,
 * and the test writes nothing either (the {@code endpoint} skill's rule that a read route must not invent
 * a 503). The test route's 422 stands in for the spec gate: a caller-supplied URL is refused unless its
 * scheme is one this service is willing to dial, so an admin-gated endpoint cannot be turned into a
 * general-purpose port scanner.
 *
 * <p>⛔ <b>No password is ever returned</b>, and a supplied one is a {@link SecretResolver} reference
 * ({@code ${ENV:…}} / {@code ${KEYSTORE:…}} / {@code ${FILE:…}}), never a literal — a literal in a form
 * post is a credential in transit and in every access log. The reference is resolved server-side at the
 * moment of the test and never stored.
 */
final class SystemRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/system/operational-db", ApiContext.withCapability("canConfigureAccess",
                (e, m) -> ETags.respond(e, OperationalDbReport.of(api.spaces().current()))));
        api.post("/system/operational-db/test", ApiContext.withCapability("canConfigureAccess",
                (e, m) -> testConnection(api.body(e))));
    }

    private Object testConnection(Map<String, Object> body) {
        String url = Values.trimOrEmpty(body == null ? null : body.get("url"));
        if (url.isBlank())
            throw new ApiException(422, "url is required — the JDBC URL to test, e.g. "
                    + "jdbc:postgresql://host:5432/inspecto");
        if (!OperationalDbReport.allowedScheme(url))
            throw new ApiException(422, "url must start with one of "
                    + String.join(" or ", OperationalDbReport.allowedSchemes())
                    + " — this endpoint opens the connection for real, so it dials nothing else");

        String password = Values.trimOrEmpty(body == null ? null : body.get("password"));
        if (!password.isBlank() && !password.startsWith("${"))
            throw new ApiException(422, "password must be a secret reference (${ENV:…}, ${KEYSTORE:…} "
                    + "or ${FILE:…}), never a literal — provision the secret, then test it");

        // Resolved here and held only for the length of the call; the outcome never echoes it.
        return OperationalDbReport.test(url, emptyToNull(Values.trimOrEmpty(body == null ? null : body.get("user"))),
                emptyToNull(SecretResolver.resolve(password)));
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
