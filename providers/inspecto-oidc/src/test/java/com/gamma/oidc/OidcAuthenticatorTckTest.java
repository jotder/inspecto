package com.gamma.oidc;

import com.gamma.spi.auth.Authenticator;
import com.gamma.control.testkit.AuthenticatorContract;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

/** {@link OidcAuthenticator} against the platform's Authenticator TCK (MODULE-REORG-1 P5b): an in-memory JWKS, no network. */
class OidcAuthenticatorTckTest extends AuthenticatorContract {
    @Override
    protected Authenticator authenticator() {
        try {
            return new OidcAuthenticator(new ImmutableJWKSet<>(new JWKSet(new RSAKeyGenerator(2048).keyID("k1").generate().toPublicJWK())),
                    "https://idp.example", "inspecto", "roles");
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
