package com.gamma.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The ONE definition of what masking a {@code secret}-declared Job parameter means
 * (job-parameter-contract §7.2), so the rule cannot drift between the surfaces that apply it.
 *
 * <p>The rule itself is unchanged from the response boundary where it started: an inline literal
 * becomes {@value #MASK}, while a {@code ${ENV:…}} reference stays visible — the reference is not
 * itself sensitive, and hiding it would leave an operator unable to see how the secret is wired.
 * House precedent for the shape is {@code ConnectionProfile}.
 *
 * <p>⛔ <b>It lives in the engine, not beside {@code JobRoutes} where the rule was first written,
 * because two of its three callers are engine-side</b> — {@link JobService}'s run log and
 * {@link ParameterResolver}'s rejection messages — and the engine cannot depend on the control
 * plane. Copying the predicate down here instead would have produced a second definition of
 * "what is a secret worth hiding", which is the drift this class exists to prevent.
 *
 * <p>⚠ <b>Two other things in this repo spell {@code "***"} and are deliberately NOT unified here.</b>
 * {@code PipelineBundleRoutes.maskSecrets} masks by <em>key name pattern</em> while walking a nested
 * config tree — a different question (which KEYS look secret) from this one (which DECLARED
 * parameters are secret). {@code SampleHelloJob} masks its own echo by hardcoding the literal
 * parameter name {@code "api_token"}; that is a third copy and a fragile one, but it is the sample
 * Job's own body rather than framework code. ⛔ Do not "unify" either without establishing that the
 * questions really are the same — they are not.
 */
public final class SecretMasking {

    /** What a hidden value reads as. Matches {@code ConnectionProfile} and the UI's expectation. */
    public static final String MASK = "***";

    private SecretMasking() {
    }

    /**
     * Whether this value is a literal worth hiding, as opposed to a reference worth showing.
     * Only a {@code String} can be masked — a boolean or a number carries no credential — and a
     * {@code ${…}} reference names where the secret lives rather than being the secret.
     */
    public static boolean hides(Object value) {
        return value instanceof String s && !s.startsWith("${");
    }

    /** The value as it may be shown: {@link #MASK} when it is a literal, otherwise unchanged. */
    public static Object shown(Object value) {
        return hides(value) ? MASK : value;
    }

    /**
     * One value of a declaration, as it may appear in a message an operator reads. A non-secret
     * declaration shows its value verbatim — ⚠ that is load-bearing, because the whole point of a
     * validation message is to say which value failed.
     */
    public static String shown(ParameterDecl decl, String value) {
        return decl != null && decl.secret() && hides(value) ? MASK : value;
    }

    /**
     * A flat parameter view with every declared-secret literal replaced. Always returns a new map;
     * a caller that must preserve identity when nothing would change should short-circuit on an
     * empty {@code secrets} set before calling — {@code JobRoutes.maskSecrets} does exactly that,
     * because its export-path test asserts the same instance comes back.
     */
    public static Map<String, Object> mask(Map<String, ?> view, Set<String> secrets) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> en : view.entrySet()) {
            Object v = en.getValue();
            out.put(en.getKey(), secrets.contains(en.getKey()) ? shown(v) : v);
        }
        return out;
    }

    /** The names a declaration list marks {@code secret} — the input to {@link #mask}. */
    public static Set<String> names(List<ParameterDecl> decls) {
        return decls.stream().filter(ParameterDecl::secret).map(ParameterDecl::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
