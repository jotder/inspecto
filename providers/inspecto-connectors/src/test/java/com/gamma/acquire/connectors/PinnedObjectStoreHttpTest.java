package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pinned object-store wire against a hostile or odd server: a scripted raw socket answers each request with
 * exactly the bytes a test gives it. 1xx interim responses are skipped; a negative chunk size, too many headers or
 * too many header bytes fail as an {@link IOException} (an {@link AcquisitionException} at the connector), never an
 * index error; control characters never reach the wire; a silent server times out at the Connection's
 * {@code read_timeout_ms}, else at the 120 s default.
 */
class PinnedObjectStoreHttpTest {

    private ServerSocket server;
    private volatile byte[] script = new byte[0];
    private volatile boolean hang;
    private Thread acceptor;

    @BeforeEach
    void start() throws IOException {
        ObjectStoreEgressFixture.allowLoopbackStubAsLan();
        server = new ServerSocket(0, 50, InetAddress.ofLiteral("127.0.0.1"));
        acceptor = Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    InputStream in = s.getInputStream();
                    int state = 0;   // read to the end of the request head (CRLF CRLF)
                    for (int c; state < 4 && (c = in.read()) >= 0; )
                        state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3))
                                ? state + 1 : 0;
                    if (hang) {
                        Thread.sleep(5_000);
                        continue;
                    }
                    OutputStream out = s.getOutputStream();
                    out.write(script);
                    out.flush();
                } catch (IOException | InterruptedException ignored) {
                    // closed
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        ObjectStoreEgressFixture.reset();
        server.close();
    }

    private S3Connector connector(Map<String, String> extra) {
        Map<String, String> opts = new HashMap<>(Map.of("region", "us-east-1", "protocol", "http"));
        opts.putAll(extra);
        return new S3Connector(new ConnectionProfile("raw", "s3", "127.0.0.1", server.getLocalPort(), null, "bucket/in",
                "AKIDEXAMPLE", "secret", opts, null));
    }

    private void respond(String raw) {
        script = raw.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** {@code put(key, bytes)} reads the response body whole inside the connector's error mapping. */
    private void put(S3Connector c) throws AcquisitionException {
        c.put("k.txt", "x".getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    @Test
    void interimOneHundredResponsesAreSkipped() throws Exception {
        respond("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 102 Processing\r\nX-A: 1\r\n\r\n"
                + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
        RemoteFileHolder.read(connector(Map.of()));
    }

    @Test
    void aNegativeChunkSizeIsAnIoFailureNotAnIndexError() {
        respond("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-5\r\nabcde\r\n0\r\n\r\n");
        AcquisitionException e = assertThrows(AcquisitionException.class, () -> put(connector(Map.of())));
        assertInstanceOf(IOException.class, e.getCause());
        assertTrue(e.getMessage().contains("negative chunk size"), e.getMessage());
    }

    @Test
    void anUnparseableChunkSizeIsAnIoFailure() {
        respond("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n");
        AcquisitionException e = assertThrows(AcquisitionException.class, () -> put(connector(Map.of())));
        assertTrue(e.getMessage().contains("malformed chunk size"), e.getMessage());
    }

    @Test
    void moreThanTwoHundredHeadersIsRefused() {
        StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i <= PinnedObjectStoreHttp.MAX_HEADERS; i++) sb.append("X-H").append(i).append(": v\r\n");
        respond(sb.append("Content-Length: 0\r\n\r\n").toString());
        AcquisitionException e = assertThrows(AcquisitionException.class, () -> put(connector(Map.of())));
        assertTrue(e.getMessage().contains("more than 200 headers"), e.getMessage());
    }

    @Test
    void headerBytesOverSixtyFourKibAreRefused() {
        StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i < 10; i++) sb.append("X-Big").append(i).append(": ").append("v".repeat(8_000)).append("\r\n");
        respond(sb.append("Content-Length: 0\r\n\r\n").toString());
        AcquisitionException e = assertThrows(AcquisitionException.class, () -> put(connector(Map.of())));
        assertTrue(e.getMessage().contains("headers exceed 65536 bytes"), e.getMessage());
    }

    @Test
    void controlCharactersInAnOutgoingHeaderAreRefusedButATabIsNot() throws IOException {
        for (String bad : new String[] {"a\u0000b", "a\rb", "a\nb", "a\u0001b", "a\u007fb"})
            assertThrows(IOException.class, () -> PinnedObjectStoreHttp.header(new StringBuilder(), "X-V", bad), bad);
        assertThrows(IOException.class, () -> PinnedObjectStoreHttp.header(new StringBuilder(), "X\u0000V", "ok"));
        assertThrows(IOException.class, () -> PinnedObjectStoreHttp.header(new StringBuilder(), "X\tV", "ok"));
        StringBuilder ok = new StringBuilder();
        PinnedObjectStoreHttp.header(ok, "X-V", "a\tb");
        assertEquals("X-V: a\tb\r\n", ok.toString());
    }

    @Test
    void aSilentServerTimesOutAtTheConnectionsReadTimeout() {
        hang = true;
        S3Connector c = connector(Map.of(AbstractHttpObjectStoreConnector.READ_TIMEOUT_OPTION, "300"));
        long t0 = System.nanoTime();
        AcquisitionException e = assertThrows(AcquisitionException.class, () -> put(c));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertInstanceOf(SocketTimeoutException.class, e.getCause(), String.valueOf(e.getCause()));
        assertTrue(ms < 4_000, "timed out at the Connection's 300 ms, not the server's 5 s hang: " + ms + " ms");
    }

    @Test
    void theReadTimeoutDefaultsToTwoMinutesAndABadOptionIsRefused() {
        assertEquals(120_000, connector(Map.of()).readTimeoutMs());
        assertEquals(120_000, PinnedObjectStoreHttp.DEFAULT_READ_TIMEOUT_MS);
        assertThrows(IllegalArgumentException.class,
                () -> connector(Map.of(AbstractHttpObjectStoreConnector.READ_TIMEOUT_OPTION, "soon")));
        assertThrows(IllegalArgumentException.class,
                () -> connector(Map.of(AbstractHttpObjectStoreConnector.READ_TIMEOUT_OPTION, "0")));
    }

    /** Reads an object whole through {@code open} and checks the body. */
    private static final class RemoteFileHolder {
        static void read(S3Connector c) throws Exception {
            try (InputStream in = c.open(new com.gamma.acquire.RemoteFile("a", "a", com.gamma.acquire.RemoteFile.SIZE_UNKNOWN, null, null, null, null))) {
                assertEquals("ok", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
}
