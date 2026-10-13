package com.gamma.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the {@code -Dauth.oidc.*} endpoint settings with a provider preset and optional OIDC discovery.
 *
 * <p>Precedence, per key: <b>explicit {@code -Dauth.oidc.<key>}</b> (always wins) &gt; the discovery document
 * named by {@code -Dauth.oidc.discoveryUrl} (fetched once per URL, at first use, i.e. boot) &gt; the preset's
 * default ({@code keycloak} only: its fixed {@code <issuer>/protocol/openid-connect/*} layout). With
 * {@code auth.oidc.provider=generic} (the default) and no {@code discoveryUrl} this returns exactly the
 * system property, so the behaviour is unchanged.
 *
 * <p>Fail-closed, value-free messages: an unknown provider, an unreachable/malformed discovery document, a
 * non-https non-loopback discovery URL, or a discovery {@code issuer} that differs from an explicitly
 * configured one all refuse to boot.
 */
final class OidcConfig {
    private OidcConfig() {}

    enum Provider { GENERIC, WSO2, KEYCLOAK }

    private static final Logger log = LoggerFactory.getLogger(OidcConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, JsonNode> DISCOVERED = new ConcurrentHashMap<>();
    private static final Map<String, String> DISCOVERY_FIELD = Map.of(
            "issuer", "issuer", "jwksUri", "jwks_uri", "tokenEndpoint", "token_endpoint",
            "authorizeUrl", "authorization_endpoint", "endSessionUrl", "end_session_endpoint");
    private static final Map<String, String> KEYCLOAK_PATH = Map.of(
            "jwksUri", "/protocol/openid-connect/certs", "tokenEndpoint", "/protocol/openid-connect/token",
            "authorizeUrl", "/protocol/openid-connect/auth", "endSessionUrl", "/protocol/openid-connect/logout");

    static Provider provider() {
        String p = System.getProperty("auth.oidc.provider");
        if (p == null || p.isBlank()) return Provider.GENERIC;
        try {
            return Provider.valueOf(p.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("inspecto-oidc: -Dauth.oidc.provider must be one of generic, wso2, keycloak");
        }
    }

    /** The effective value of {@code auth.oidc.<key>} ({@code issuer|jwksUri|tokenEndpoint|authorizeUrl|
     *  endSessionUrl}), or {@code null} when neither configured nor derivable. */
    static String get(String key) {
        Provider provider = provider();           // validates the value on every path
        JsonNode doc = discovery();               // validates the discovery document once configured
        String explicit = System.getProperty("auth.oidc." + key);
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (doc != null) {
            String v = doc.path(DISCOVERY_FIELD.get(key)).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        if (provider == Provider.KEYCLOAK && KEYCLOAK_PATH.containsKey(key)) {
            String issuer = get("issuer");
            if (issuer != null) return issuer.trim().replaceAll("/+$", "") + KEYCLOAK_PATH.get(key);
        }
        return null;
    }

    private static JsonNode discovery() {
        String url = System.getProperty("auth.oidc.discoveryUrl");
        if (url == null || url.isBlank()) return null;
        JsonNode doc = DISCOVERED.get(url);
        if (doc == null) {
            doc = fetch(url);
            DISCOVERED.put(url, doc);
        }
        String configured = System.getProperty("auth.oidc.issuer");
        if (configured != null && !configured.isBlank()
                && !trimSlash(configured).equals(trimSlash(doc.path("issuer").asText(""))))
            throw new IllegalStateException("inspecto-oidc: the issuer in the discovery document does not equal "
                    + "-Dauth.oidc.issuer");
        return doc;
    }

    private static JsonNode fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("inspecto-oidc: -Dauth.oidc.discoveryUrl is not a valid URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(loopback && "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalStateException("inspecto-oidc: -Dauth.oidc.discoveryUrl must be https "
                    + "(http is allowed only for localhost)");
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode doc = r.statusCode() == 200 ? JSON.readTree(r.body()) : null;
            if (doc != null && doc.path("issuer").asText("").isBlank()) doc = null;
            if (doc != null) return doc;
        } catch (Exception e) {
            log.warn("OIDC discovery fetch failed: {}", e.getClass().getSimpleName());
        }
        throw new IllegalStateException("inspecto-oidc could not load the OIDC discovery document named by "
                + "-Dauth.oidc.discoveryUrl (unreachable, not HTTP 200, or not an OIDC discovery document)");
    }

    private static String trimSlash(String s) { return s.trim().replaceAll("/+$", ""); }
}
