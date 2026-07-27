package com.gamma.intelligence;

import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.OllamaChatAdapter;
import com.eoiagent.model.OpenAiCompatibleChatAdapter;
import com.eoiagent.model.StubLlmGateway;
import com.gamma.model.ModelSettings;
import com.gamma.model.ModelSettingsStore;

/**
 * Builds the {@link LlmGateway} passed explicitly to {@code PlatformBuilder.llmGateway(...)} —
 * bypassing the platform's own provider routing entirely, so an unsupported/hosted provider
 * string in {@link com.gamma.intelligence.pack.InspectoModelProfile} never breaks assembly. Reads
 * the core {@link ModelSettings} the reflex-layer settings screen writes (S9: via the core-owned
 * {@link ModelSettingsStore}, not a compile dep on {@code inspecto-agent}), so one screen configures
 * both agent modules.
 *
 * <p>P0 only wires local providers (ollama, llama.cpp) for real — a hosted provider falls back to
 * a deterministic offline stub with an explanatory reply, since this module deliberately carries
 * no hosted-model SDK (see the pack-level Javadoc on air-gap discipline).
 */
final class GatewayFactory {

    private GatewayFactory() {
    }

    /**
     * The gateway, plus whether it is backed by a <b>real reachable model</b> rather than the offline stub.
     *
     * <p>The flag exists because the two cases are not interchangeable for AGT-6a A5: the deliberative
     * {@code /ask} loop degrades gracefully into the stub's explanatory prose, but a natural-language
     * <b>derive</b> hop cannot — with no model there are no tool arguments to derive, and answering "I could
     * not understand you" would blame the operator's sentence for a deployment fact. The derive route turns
     * {@code configured=false} into a <b>503</b> instead. Callers must not infer this with
     * {@code instanceof StubLlmGateway}: a test injects a stub that <i>does</i> answer with tool calls, and
     * that is a configured model as far as this distinction is concerned.
     */
    record Gateway(LlmGateway llm, boolean configured) {}

    static Gateway build() {
        ModelSettings settings = ModelSettingsStore.load().orElseGet(() -> ModelSettings.defaults("ollama"));
        String modelId = settings.model("medium");
        if (settings.local() && settings.baseUrl() != null && modelId != null) {
            return switch (settings.provider()) {
                case "ollama" -> new Gateway(new OllamaChatAdapter(settings.baseUrl(), modelId), true);
                case "llamacpp" ->
                        new Gateway(new OpenAiCompatibleChatAdapter(settings.baseUrl(), modelId, null), true);
                default -> new Gateway(offlineStub(settings), false);
            };
        }
        return new Gateway(offlineStub(settings), false);
    }

    private static LlmGateway offlineStub(ModelSettings settings) {
        return StubLlmGateway.builder()
                .defaultReplyText("No reachable local model is configured for the embedded intelligence "
                        + "agent (provider '" + settings.provider() + "'); hosted providers are not wired "
                        + "into file-processor-intelligence yet. Configure a local Ollama endpoint under "
                        + "Assist Settings.")
                .build();
    }
}
