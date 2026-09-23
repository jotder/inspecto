package com.gamma.control;

import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code HEAD-RESPONSE-STREAM-CLOSED-1}: a HEAD request must be answered with headers only. Writing a
 * body with a content length on a HEAD makes the JDK server warn ("being invoked with a content length
 * for a HEAD request") and the body write then throws, which the error boundary logged as a failure on
 * every preview/uptime probe. The JDK server reports that warning through {@code System.Logger}
 * {@code com.sun.net.httpserver}, which (no LoggerFinder on the classpath) lands in JUL — captured here.
 */
class ControlApiHeadRequestTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void headIsAnsweredWithHeadersOnlyAndNoServerComplaint() throws Exception {
        Logger jdk = Logger.getLogger("com.sun.net.httpserver");
        List<LogRecord> records = new ArrayList<>();
        Handler h = new Handler() {
            @Override public void publish(LogRecord r) { if (r.getLevel().intValue() >= Level.WARNING.intValue()) records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        jdk.addHandler(h);
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        try {
            api.start();
            for (String path : List.of("/", "/api/v1/health", "/api/v1/no-such-route")) {
                HttpRequest head = HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + path))
                        .method("HEAD", BodyPublishers.noBody()).build();
                HttpResponse<String> r = client.send(head, BodyHandlers.ofString());
                assertTrue(r.statusCode() < 500, "HEAD " + path + " is not a server fault: " + r.statusCode());
                assertEquals("", r.body(), "HEAD " + path + " has no body");
            }
            assertEquals(List.of(), records.stream().map(LogRecord::getMessage).toList(),
                    "the JDK server must not warn about a body written to a HEAD response");
        } finally {
            jdk.removeHandler(h);
            api.close();
            svc.close();
        }
    }
}
