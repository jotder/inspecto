package com.gamma.oidc;

import com.gamma.control.TokenRelay;
import com.gamma.control.testkit.TokenRelayContract;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;

/** {@link OidcTokenRelay} against the platform's TokenRelay TCK (MODULE-REORG-1 P5b): a stand-in IAM that refuses every grant. */
class OidcTokenRelayTckTest extends TokenRelayContract {
    private static HttpServer iam;

    @BeforeAll
    static void startIam() throws Exception {
        iam = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        iam.createContext("/token", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(400, -1);
            ex.close();
        });
        iam.start();
    }

    @AfterAll
    static void stopIam() {
        iam.stop(0);
    }

    @Override
    protected TokenRelay relay() {
        return new OidcTokenRelay(HttpClient.newHttpClient(),
                URI.create("http://localhost:" + iam.getAddress().getPort() + "/token"), "inspecto-spa", "tck-client-secret");
    }
}
