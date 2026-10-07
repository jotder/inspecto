package com.gamma.intelligence.agent;

import com.gamma.model.ModelSettings;
import com.gamma.util.egress.EgressPolicy;
import com.gamma.pipeline.exec.ModelEgress;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Round-2 verification item 2 (2026-09-29): the gateway dials a model endpoint only if the allowlist permits it. */
class GatewayFactoryTest {

    private static final EgressPolicy.Resolver LOCAL = host -> new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};

    @Test
    void aModelEndpointOffTheAllowlistDegradesToTheOfflineStub() {
        ModelSettings ollama = ModelSettings.defaults("ollama");
        assertNotNull(ollama.baseUrl(), "the default Ollama settings name a local endpoint");
        assertFalse(GatewayFactory.build(ollama, ModelEgress.Policy.EMPTY, LOCAL).configured(),
                "an empty allowlist (the default) allows no endpoint: no model, no outbound call");
        assertTrue(GatewayFactory.build(ollama, ModelEgress.parse(List.of("localhost", "127.0.0.1")), LOCAL).configured(),
                "a local Ollama listed explicitly is reachable");
    }
}
