package com.gamma.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.SecretResolver;
import com.gamma.control.TokenRelay;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The Standard edition's {@link TokenRelay} (W6d): redeems Authorization-Code+PKCE codes and refresh
 * tokens against the IAM's token endpoint, server-to-server — so the browser only ever holds the
 * short-lived access token while the refresh token stays in the control plane's {@code httpOnly}
 * cookie. Registered via {@code META-INF/services/com.gamma.control.TokenRelay}.
 *
 * <p>Vendor-neutral by construction (BACKLOG D15: there is no IdP of record — the IdP is a per-client
 * deployment choice). Nothing here knows a vendor's URL layout: the token endpoint is <b>required
 * configuration</b>, never guessed from a product's path shape, so pointing the relay at any compliant
 * OIDC provider is a config-only change.
 *
 * <p><b>Config</b> ({@code -D} flags, alongside {@code OidcAuthenticator}'s {@code auth.oidc.*}):
 * {@code auth.oidc.tokenEndpoint} — <b>mandatory, no default</b>; take it from the provider's
 * {@code token_endpoint} in its {@code /.well-known/openid-configuration} discovery document (this
 * module deliberately does no discovery fetch at boot, matching {@code auth.oidc.jwksUri}, which is
 * likewise configured explicitly). A missing value fails fast at startup. Then
 * {@code auth.oidc.clientId} (default
 * {@code inspecto-spa}), and optional {@code auth.oidc.clientSecret} for a confidential client — its
 * value goes through {@link SecretResolver}, so pass a <b>reference</b> ({@code ${ENV:NAME}} /
 * {@code ${SYS:prop}}), never the raw secret on the command line (the shipped secrets seam; the
 * fuller {@code SecretsProvider} SPI from EDITIONS.md is still future work). A public PKCE client
 * needs no secret at all — the flag is genuinely optional.
 *
 * <p>Any IAM error (rejected code, revoked refresh token, 5xx, timeout) yields an empty result —
 * the caller 401s without learning the IAM's error detail.
 */
public final class OidcTokenRelay implements TokenRelay {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final URI tokenEndpoint;
    private final String clientId;
    private final String clientSecret;   // null ⇒ public client (PKCE only)

    /** No-arg constructor required by {@code ServiceLoader}; reads {@code -Dauth.oidc.*}. */
    public OidcTokenRelay() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
                URI.create(requireTokenEndpoint()),
                System.getProperty("auth.oidc.clientId", "inspecto-spa"),
                resolveSecret(System.getProperty("auth.oidc.clientSecret")));
    }

    /** Explicit-config seam (tests point this at a local stand-in for the IAM). */
    OidcTokenRelay(HttpClient http, URI tokenEndpoint, String clientId, String clientSecret) {
        this.http = http;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public Optional<Tokens> exchangeCode(String code, String codeVerifier, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("code_verifier", codeVerifier);
        form.put("redirect_uri", redirectUri);
        return post(form);
    }

    @Override
    public Optional<Tokens> refresh(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        return post(form);
    }

    private Optional<Tokens> post(Map<String, String> form) {
        form.put("client_id", clientId);
        if (clientSecret != null && !clientSecret.isBlank()) form.put("client_secret", clientSecret);
        try {
            HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();
            JsonNode body = JSON.readTree(response.body());
            String access = body.path("access_token").asText(null);
            String refresh = body.path("refresh_token").asText(null);
            if (access == null || refresh == null) return Optional.empty();
            return Optional.of(new Tokens(access, body.path("expires_in").asLong(300),
                    refresh, body.hasNonNull("refresh_expires_in") ? body.get("refresh_expires_in").asLong() : null));
        } catch (Exception e) {
            return Optional.empty();   // IAM unreachable / timeout / malformed — the caller just gets 401
        }
    }

    private static String encode(Map<String, String> form) {
        StringBuilder out = new StringBuilder();
        form.forEach((k, v) -> out.append(out.isEmpty() ? "" : "&")
                .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return out.toString();
    }

    /**
     * The token endpoint is required, never derived: guessing it from {@code auth.oidc.issuer} would bake
     * one vendor's path layout into the product (BACKLOG D15). Read the real value from the provider's
     * {@code /.well-known/openid-configuration} ({@code token_endpoint}) and pass it explicitly.
     */
    private static String requireTokenEndpoint() {
        String endpoint = System.getProperty("auth.oidc.tokenEndpoint");
        if (endpoint == null || endpoint.isBlank())
            throw new IllegalStateException("inspecto-security requires -Dauth.oidc.tokenEndpoint"
                    + " (the OIDC provider's token_endpoint, from its /.well-known/openid-configuration;"
                    + " it is not derived from -Dauth.oidc.issuer — no vendor path layout is assumed)");
        return endpoint;
    }

    private static String resolveSecret(String ref) {
        return ref == null ? null : SecretResolver.resolve(ref);
    }
}
