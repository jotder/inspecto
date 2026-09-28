package com.gamma.agent;

import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the v3.0 (M0) cross-module assist SPI wiring end-to-end: the optional agent module
 * implements {@code AssistAgent} (from core) and {@link CollectorService} accepts and binds it.
 * No engine work, no I/O — just the injection point.
 */
class NoopAssistAgentTest {

    /** Every CollectorService this class builds, closed after each test: the default Space's services share the
     *  process-wide {@code EventLog.global()}, so an unclosed one leaves its subscribers live for the whole fork. */
    private final java.util.List<CollectorService> services = new java.util.ArrayList<>();

    private CollectorService track(CollectorService svc) {
        services.add(svc);
        return svc;
    }

    @org.junit.jupiter.api.AfterEach
    void closeServices() {
        services.forEach(CollectorService::close);
        services.clear();
    }

    @Test
    void wiresIntoCollectorServiceViaTheSpi() {
        CollectorService svc = track(new CollectorService(List.of(), 60, 1));
        assertFalse(svc.assistAgent().isPresent(), "no agent before registration");

        NoopAssistAgent agent = new NoopAssistAgent();
        svc.registerAgent(agent);

        assertTrue(svc.assistAgent().isPresent(), "agent present after registration");
        assertEquals("noop", svc.assistAgent().get().name());
        assertSame(svc, agent.boundService(), "init() received the host service");
    }

    @Test
    void secondRegistrationIsIgnored() {
        CollectorService svc = track(new CollectorService(List.of(), 60, 1));
        NoopAssistAgent first = new NoopAssistAgent();
        NoopAssistAgent second = new NoopAssistAgent();

        svc.registerAgent(first);
        svc.registerAgent(second);   // ignored — one agent per service

        assertSame(first, svc.assistAgent().orElseThrow(), "first registration wins");
    }
}
