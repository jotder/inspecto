package com.gamma.acquire.connectors;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A minimal HTTP CONNECT relay for connector proxy tests: accepts connections in a loop, parses a
 * {@code CONNECT host:port HTTP/1.1} request (plus headers) on each, records the requested target and any
 * {@code Proxy-Authorization} header, replies {@code 200 Connection established}, then pipes bytes to/from a
 * real TCP connection to that target until either side closes. Just enough of the CONNECT protocol to prove
 * {@link HttpProxySocketFactory} actually tunnels through an HTTP proxy rather than connecting directly.
 * The SOCKS5 counterpart is {@link MiniSocks5Relay}; multi-connection for the same reason (FTP data channel).
 */
final class MiniHttpConnectRelay implements AutoCloseable {
    private final ServerSocket listener;
    private final Thread acceptor;
    private final List<String> targets = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final CountDownLatch firstKnown = new CountDownLatch(1);

    private MiniHttpConnectRelay(ServerSocket listener) {
        this.listener = listener;
        this.acceptor = new Thread(this::acceptLoop, "mini-http-connect-relay");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    static MiniHttpConnectRelay start() throws IOException {
        ServerSocket ss = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        return new MiniHttpConnectRelay(ss);
    }

    int port() { return listener.getLocalPort(); }

    /** The first {@code host:port} a client asked this relay to CONNECT to (the control connection),
     *  waiting up to {@code timeoutMs}. */
    String firstConnectTarget(long timeoutMs) throws InterruptedException {
        firstKnown.await(timeoutMs, TimeUnit.MILLISECONDS);
        return targets.isEmpty() ? null : targets.get(0);
    }

    /** The {@code Proxy-Authorization} header value of the first CONNECT, or {@code null} if none was sent. */
    String firstAuthorization() {
        return authorizations.isEmpty() ? null : authorizations.get(0);
    }

    private void acceptLoop() {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                Thread t = new Thread(() -> serve(client), "mini-http-connect-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;   // listener closed
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            // Deliberately NOT a BufferedReader over the whole stream — read only the request head
            // byte-by-byte so no tunnelled bytes get swallowed into a reader's buffer.
            var in = client.getInputStream();
            var out = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || !requestLine.startsWith("CONNECT ")) return;
            String target = requestLine.substring("CONNECT ".length()).split(" ")[0];

            String auth = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Proxy-Authorization:", 0, "Proxy-Authorization:".length()))
                    auth = line.substring("Proxy-Authorization:".length()).trim();
            }
            if (auth != null) authorizations.add(auth);
            targets.add(target);
            firstKnown.countDown();

            int colon = target.lastIndexOf(':');
            String host = target.substring(0, colon);
            int p = Integer.parseInt(target.substring(colon + 1));

            try (Socket upstream = new Socket(host, p)) {
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread relayUp = new Thread(() -> relay(client, upstream));
                relayUp.setDaemon(true);
                relayUp.start();
                relay(upstream, client);
                relayUp.join(2000);
            }
        } catch (Exception ignore) {
            // best-effort test relay — a closed/failed leg just ends this connection
        }
    }

    private static String readLine(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) c);
        }
        return any ? sb.toString() : null;
    }

    private static void relay(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException ignore) {
            // normal once either side closes
        }
    }

    @Override
    public void close() throws IOException {
        listener.close();
        acceptor.interrupt();
    }
}
