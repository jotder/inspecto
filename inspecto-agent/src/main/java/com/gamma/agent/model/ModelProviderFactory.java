package com.gamma.agent.model;

import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.model.ModelProfile;
import com.gamma.agent.model.OllamaModelProvider;
import com.gamma.pipeline.exec.EgressPolicy;
import com.gamma.pipeline.exec.ModelEgress;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Maps {@link ProviderSettings} to a concrete {@link ModelRouter} (v4.1) — the one place a provider
 * id becomes a client. Local Ollama is built directly (the dependency this module always carries);
 * hosted ids are resolved through the {@link HostedProviderPlugin} ServiceLoader seam so hosted SDKs
 * stay out of the default classpath ({@code inspecto-agent-hosted} contributes them).
 *
 * <p>Construction never touches the network — every provider builds its client lazily and reports
 * {@code available()} from configuration alone (the kernel's abstain-safe contract).
 */
public final class ModelProviderFactory {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelProviderFactory.class);

    private ModelProviderFactory() {}

    /**
     * The router for the persisted settings, or the legacy environment-resolved Ollama router when
     * no settings file exists — existing deployments keep working unchanged.
     */
    public static ModelRouter fromPersisted() {
        return AssistModelSettings.load()
                .map(ModelProviderFactory::create)
                .orElseGet(OllamaModelProvider::fromEnvironment);
    }

    /** Build a router for explicit settings. Unknown/unbacked hosted ids yield an unavailable router. */
    public static ModelRouter create(ProviderSettings settings) {
        return create(settings, ModelEgress.forCurrentSpace(), EgressPolicy.SYSTEM);
    }

    /**
     * ASSIST-MODEL-EGRESS-1 (2026-10-01): the settings' endpoint is checked against the Space's model endpoint
     * allowlist ({@link ModelEgress}, the {@code models} key of {@code egress.toon}) and the client is built on the
     * CHECKED address ({@link ModelEgress#pin}), as the intelligence module's {@code GatewayFactory} does. A refused
     * endpoint yields an unavailable router, so nothing dials it. A hosted provider with no {@code baseUrl} keeps
     * its SDK's fixed vendor endpoint.
     */
    public static ModelRouter create(ProviderSettings settings, ModelEgress.Policy egress, EgressPolicy.Resolver resolver) {
        if (settings == null) return OllamaModelProvider.fromEnvironment();
        String id = settings.provider();
        String effective = settings.baseUrl() != null ? settings.baseUrl()
                : "ollama".equals(id) ? ProviderSettings.defaultBaseUrl("ollama") : null;
        if (effective != null) {
            try {
                String pinned = ModelEgress.pin(effective, egress, resolver);
                settings = new ProviderSettings(settings.provider(), pinned, settings.apiKeyRef(),
                        settings.models(), settings.timeoutSeconds());
            } catch (EgressPolicy.Refused refused) {
                String why = "model endpoint refused: " + refused.getMessage();
                log.warn("[EGRESS] {}", why);
                ModelProvider refusedProvider = new ModelProvider() {
                    @Override public String name() { return why; }
                    @Override public boolean available() { return false; }
                    @Override public com.gamma.agent.kernel.model.ModelResponse generate(
                            com.gamma.agent.kernel.model.ModelRequest request) {
                        throw new com.gamma.agent.kernel.error.ModelError(why);
                    }
                };
                return tier -> refusedProvider;
            }
        }
        if ("ollama".equals(id)) {
            String baseUrl = settings.baseUrl();
            var models = settings.models().isEmpty()
                    ? ProviderSettings.defaultModels("ollama") : settings.models();
            // enabled=true: choosing ollama in the settings IS the explicit opt-in.
            return withDeadline(
                    OllamaModelProvider.routerFor(new ModelProfile("settings", baseUrl, true, models)),
                    settings);
        }
        for (HostedProviderPlugin plugin : ServiceLoader.load(HostedProviderPlugin.class)) {
            if (plugin.providers().contains(id)) {
                return withDeadline(
                        plugin.createRouter(settings, AssistModelSettings.resolveApiKey(settings)),
                        settings);
            }
        }
        String why = ProviderSettings.knownProviders().contains(id)
                ? "provider '" + id + "' requires the inspecto-agent-hosted jar on the classpath"
                : "unknown model provider '" + id + "'";
        return tier -> ModelProvider.unavailable(why);
    }

    /**
     * Enforce the settings' per-request timeout as a hard deadline around every provider (B1: the
     * declared timeout previously had no enforcement — a hung provider stalled the assist call).
     */
    private static ModelRouter withDeadline(ModelRouter router, ProviderSettings settings) {
        Duration deadline = Duration.ofSeconds(settings.timeoutSeconds());
        return tier -> TimeoutModelProvider.wrap(router.providerFor(tier), deadline);
    }

    /** Provider ids selectable in this deployment: always ollama, plus whatever plugins contribute. */
    public static Set<String> availableProviders() {
        Set<String> out = new LinkedHashSet<>();
        out.add("ollama");
        for (HostedProviderPlugin plugin : ServiceLoader.load(HostedProviderPlugin.class)) {
            out.addAll(plugin.providers());
        }
        return out;
    }
}
