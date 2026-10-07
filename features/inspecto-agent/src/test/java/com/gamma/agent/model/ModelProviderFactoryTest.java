package com.gamma.agent.model;

import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.model.ModelTier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provider-id → router mapping (v4.1). Config-only — no network, no model I/O. */
class ModelProviderFactoryTest {

    @Test
    void ollamaSettingsBuildAnAvailableRouter() {
        ProviderSettings s = new ProviderSettings("ollama", "http://localhost:11434", null,
                Map.of(ModelTier.MEDIUM, "qwen2.5:7b"), 0);
        // The Space's model endpoint allowlist must name the loopback host (ASSIST-MODEL-EGRESS-1).
        ModelRouter r = ModelProviderFactory.create(s,
                com.gamma.pipeline.exec.ModelEgress.parse(java.util.List.of("localhost")),
                com.gamma.util.egress.EgressPolicy.SYSTEM);
        // available() is a pure config check: enabled + model + baseUrl. No network involved.
        assertTrue(r.providerFor(ModelTier.MEDIUM).available());
        assertFalse(r.providerFor(ModelTier.LARGE).available(), "unmapped tier stays unavailable");
        assertFalse(ModelProviderFactory.create(s).providerFor(ModelTier.MEDIUM).available(),
                "no allowlist entry: the endpoint is refused");
    }

    @Test
    void hostedProviderWithoutPluginIsUnavailableNotFatal() {
        // The hosted jar is not on this module's test classpath — the air-gapped default.
        ModelRouter r = ModelProviderFactory.create(ProviderSettings.defaults("anthropic"));
        assertFalse(r.providerFor(ModelTier.MEDIUM).available());
        assertFalse(r.anyAvailable());
    }

    @Test
    void unknownProviderIsUnavailableNotFatal() {
        ModelRouter r = ModelProviderFactory.create(
                new ProviderSettings("frontier-9000", null, null, Map.of(), 0));
        assertFalse(r.anyAvailable());
    }

    @Test
    void availableProvidersAlwaysIncludesOllamaOnly_withoutHostedJar() {
        assertTrue(ModelProviderFactory.availableProviders().contains("ollama"));
        assertFalse(ModelProviderFactory.availableProviders().contains("anthropic"),
                "hosted ids appear only when inspecto-agent-hosted is on the classpath");
    }
}
