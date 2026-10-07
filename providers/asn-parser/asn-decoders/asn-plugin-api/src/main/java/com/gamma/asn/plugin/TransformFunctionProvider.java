package com.gamma.asn.plugin;

import java.util.Map;

/**
 * ServiceLoader SPI: a plugin jar contributes named transform functions. Registered via
 * {@code META-INF/services/com.gamma.asn.plugin.TransformFunctionProvider}. Names collide
 * loudly at load time — no silent override roulette.
 *
 * <p>REDESIGN.md §4.5 also plans {@code ValueDecoderProvider} and {@code FramingProvider};
 * they are deliberately not defined yet — no vendor decoder or framing needs a plugin today
 * (all corpus formats use the generic telecom decoders in asn-schema's DecoderRegistry).
 */
public interface TransformFunctionProvider {

    /** Function name → implementation. Called once per pipeline with that pipeline's context. */
    Map<String, TransformFunction> functions(PluginContext context);
}
