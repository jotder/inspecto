package com.gamma.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiContext#query} over a real JDK HttpServer: a query parameter is percent-decoded exactly ONCE. It used to
 * decode the already-decoded {@code getQuery()}, so {@code %2B} arrived as a space.
 */
class ApiContextQueryDecodingTest {

    private HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", ex -> {
            String key = ex.getRequestURI().getRawQuery().split("=", 2)[0];
            String v = ApiContext.query(ex, key);
            byte[] out = (v == null ? "<null>" : "[" + v + "]").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String echo(String rawQuery) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/echo?" + rawQuery);
        return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void encodedPlusArrivesAsPlus() throws Exception {
        assertEquals("[a+b]", echo("key=a%2Bb"));
    }

    @Test
    void literalPlusArrivesAsSpace() throws Exception {
        assertEquals("[a b]", echo("q=a+b"));
    }

    @Test
    void encodedAmpersandAndPercentAreDecodedOnce() throws Exception {
        assertEquals("[a&b]", echo("key=a%26b"));
        assertEquals("[100%25]", echo("key=100%2525"));
    }

}
