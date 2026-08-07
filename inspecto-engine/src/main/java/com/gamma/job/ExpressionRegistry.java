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
    private final Map<ExpressionProvider, String> owners = new LinkedHashMap<>();   // provider -> owner (null = permanent)

    /** The engine's own vocabulary (§2). Classpath providers and Job Packs register on top. */
    static ExpressionRegistry withBuiltins() {
        ExpressionRegistry r = new ExpressionRegistry();
        r.register(new BuiltinExpressions(), null);
        return r;
    }

    /** Register a permanent (built-in / classpath) provider — never deregistered. */
    void register(ExpressionProvider provider) {
        register(provider, null);
    }

    /** Register a provider owned by {@code owner} (a Job Pack key, or {@code null} for permanent).
     *  Atomic: a collision on any one token leaves the registry untouched. */
    void register(ExpressionProvider provider, String owner) {
        List<ExpressionDecl> decls = provider.declarations();
        for (ExpressionDecl d : decls) {
            if (routes.containsKey(d.matchKey()))
                throw new IllegalStateException("duplicate expression token '" + d.token() + "'");
        }
        decls.forEach(d -> routes.put(d.matchKey(), new Route(d, provider)));
        providers.add(provider);
        owners.put(provider, owner);
    }

    /** Remove every provider owned by {@code owner} (Job Pack unload/reload); returns the tokens removed.
     *  A pack can never displace a built-in — its token collides and is rejected at registration — so this
     *  only ever takes back what that pack itself added. */
    List<String> deregister(String owner) {
        List<String> removed = new ArrayList<>();
        for (var it = owners.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!java.util.Objects.equals(owner, e.getValue())) continue;
            for (ExpressionDecl d : e.getKey().declarations()) {
                routes.remove(d.matchKey());
                removed.add(d.token());
            }
            providers.remove(e.getKey());
            it.remove();
        }
        return removed;
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

    /** The catalog {@code GET /jobs/expressions} serves (§4.3), <b>generated from the registry</b> — never a
     *  parallel hand-maintained list, so it stays correct as packs load. A {@code contextFree} entry's
     *  {@code preview} is evaluated here, by the same evaluator a Run uses, which is what makes the UI's
     *  preview correct by construction instead of a second implementation. Context-bound entries fall back
     *  to their declared worked sample: there is no firing Run at request time, and inventing one would
     *  show the author a value that is not what their Job will see. */
    List<Map<String, Object>> catalog(ExpressionContext previewCtx) {
        return declarations().stream()
                .map(d -> d.toMap(d.contextFree()
                        ? evaluate(d.sampleExpression(), previewCtx).orElse(d.example())
                        : d.example()))
                .toList();
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
