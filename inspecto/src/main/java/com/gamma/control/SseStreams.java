package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live Server-Sent-Event streams one {@link ControlApi} is serving, so {@link ControlApi#close()} can end
 * them promptly. A stream handler blocks in a heartbeat poll and only notices a gone client on its next write,
 * and {@code HttpServer.stop} does not interrupt handler threads — without this registry a stream's
 * subscriber outlives {@code close()} by up to one heartbeat.
 *
 * <p>{@link #closeAll()} marks the registry closed, runs every stream's cleanup (e.g. remove its
 * {@code EventLog} subscriber), interrupts its handler thread and closes its exchange. It is idempotent
 * and never throws. A stream opened after {@code closeAll()} is ended at once.
 */
final class SseStreams {

    /** One registered stream; {@link #close()} deregisters it (the handler's normal-disconnect path). */
    final class Stream implements AutoCloseable {
        private final HttpExchange exchange;
        private final Thread thread = Thread.currentThread();
        private final Runnable cleanup;

        private Stream(HttpExchange exchange, Runnable cleanup) {
            this.exchange = exchange;
            this.cleanup = cleanup;
        }

        private void end() {
            try { cleanup.run(); } catch (RuntimeException ignored) { /* never throw from close */ }
            thread.interrupt();
            try { exchange.close(); } catch (RuntimeException ignored) { /* already closed */ }
        }

        @Override public void close() { open.remove(this); }
    }

    private final Set<Stream> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** Registers the calling handler thread's stream; {@code cleanup} must be idempotent. */
    Stream register(HttpExchange exchange, Runnable cleanup) {
        Stream s = new Stream(exchange, cleanup);
        open.add(s);
        if (closed && open.remove(s)) s.end();    // raced closeAll(): end it now rather than leak it
        return s;
    }

    /** Ends every live stream. Idempotent, never throws. */
    void closeAll() {
        closed = true;
        for (Stream s : open) {
            if (open.remove(s)) s.end();
        }
    }

    /** Live stream count (tests). */
    int size() { return open.size(); }
}
