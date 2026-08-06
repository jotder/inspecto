package com.gamma.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The open registry of {@link ExpressionProvider}s that replaced the hardcoded {@code deduce()} switch in
 * {@link ParameterResolver} (job-parameter-contract §4.2). Mirrors {@link JobTypeRegistry}: built-ins
 * first, then (from step 5) classpath providers and Job Packs — so a token is added by registration.
 *
 * <p>Registration is fail-closed on <em>every</em> path, unlike {@code JobTypeRegistry}'s ServiceLoader
 * loop which only warns (§4.2): a provider declaring an already-registered token fails its own
 * registration loudly, because a captured token would change what an authored {@code $}-value means
 * without any signal.
 *
 * <p>Owns the {@code $}-grammar too ({@link #isExpression}, {@link #unescape}, {@link #declares}), so the
 * escape rule and the fail-closed unknown-token gate (§6.2/§6.3) are stated once rather than at each
 * position an author can type a value.
 */
final class ExpressionRegistry {

    /** One routing entry: which provider answers for a declared token, and how the token is matched. */
    private record Route(ExpressionDecl decl, ExpressionProvider provider) {}

    private final List<ExpressionProvider> providers = new ArrayList<>();
    private final Map<String, Route> routes = new LinkedHashMap<>();   // matchKey -> route

    /** The engine's own vocabulary (§2) — the only registration until step 5 wires the open paths. */
    static ExpressionRegistry withBuiltins() {
        ExpressionRegistry r = new ExpressionRegistry();
        r.register(new BuiltinExpressions());
        return r;
    }

    /** Register a provider. Atomic: a collision on any one token leaves the registry untouched. */
    void register(ExpressionProvider provider) {
        List<ExpressionDecl> decls = provider.declarations();
        for (ExpressionDecl d : decls) {
            if (routes.containsKey(d.matchKey()))
                throw new IllegalStateException("duplicate expression token '" + d.token() + "'");
        }
        decls.forEach(d -> routes.put(d.matchKey(), new Route(d, provider)));
        providers.add(provider);
    }

    /** Whether {@code raw} is meant as an Expression at all (§6.2/§6.3): {@code $}-led, but not the
     *  {@code $$} literal escape. A value with no leading {@code $} can never name a token. */
    static boolean isExpression(String raw) {
        return raw.startsWith("$") && !raw.startsWith("$$");
    }

    /** Apply the {@code $$} literal escape: {@code $$today} is the eight-character string {@code $today},
     *  so a value that genuinely starts with {@code $} (a shell string, a currency amount) stays
     *  expressible once authored values are evaluated (§6.2). Anything else passes through unchanged. */
    static String unescape(String raw) {
        return raw.startsWith("$$") ? raw.substring(1) : raw;
    }

    /** Whether some provider declares a token matching {@code expr}. The fail-closed gate of §6.3: an
     *  Expression nobody declares is a typo, not a value that should quietly fall through. */
    boolean declares(String expr) {
        return route(expr).isPresent();
    }

    /** Every registered declaration, in registration order — what the §4.3 catalog serves. */
    List<ExpressionDecl> declarations() {
        return providers.stream().flatMap(p -> p.declarations().stream()).toList();
    }

    /** Evaluate one Expression: route it to its declaring provider, or empty when no token matches
     *  (unknown) or the declared token has no value in this context. */
    Optional<String> evaluate(String expr, ExpressionContext ctx) {
        return route(expr).flatMap(r -> r.provider().evaluate(expr, ctx));
    }

    /** Longest match wins, so declaring both {@code $now} and {@code $now.epoch_seconds} is unambiguous. */
    private Optional<Route> route(String expr) {
        if (expr == null) return Optional.empty();
        Route best = null;
        for (var e : routes.entrySet()) {
            boolean hit = switch (e.getValue().decl().form()) {
                case LITERAL -> expr.equals(e.getKey());
                case PREFIX, FUNCTION -> expr.startsWith(e.getKey());
            };
            if (hit && (best == null || e.getKey().length() > best.decl().matchKey().length())) best = e.getValue();
        }
        return Optional.ofNullable(best);
    }
}
