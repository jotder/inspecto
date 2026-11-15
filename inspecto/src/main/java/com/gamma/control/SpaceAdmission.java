package com.gamma.control;

import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.service.SpaceContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

/**
 * Who may use a Space at all ({@code LA-FULL-CYCLE-TEST-1} finding 32, operator decision D5: fail closed).
 *
 * <p>A Space is <b>closed</b> when its {@code space.toon} says {@code admission: closed}; {@code POST /spaces} and
 * {@code POST /spaces/import} write that, with {@code created_by}, whenever a signed-in person creates the Space. A
 * closed Space admits:
 * <ol>
 *   <li>the person who created it ({@code created_by} is their Subject id);</li>
 *   <li>a holder of {@code canAdminister}, judged by that Space's own role table;</li>
 *   <li>a holder of a role that Space's own {@code roles.toon} defines - the way an administrator admits others
 *       (Settings, Access, Roles in that Space, or {@code PUT /access/roles}); a role only the built-in table defines
 *       admits nobody;</li>
 *   <li>a machine whose client that Space's {@code roles.toon} names ({@link Roles#admitMachine}, unchanged).</li>
 * </ol>
 * Everyone else is answered as if the Space did not exist (404), and it is left out of the Space lists.
 *
 * <p>Nothing is checked when no Subject is attached: with no sign-in every request passes, as before. A Space with no
 * {@code admission} key - every Space made before this rule, made without sign-in or placed on disk - is open: any
 * signed-in person reads it, as before.
 */
final class SpaceAdmission {

    private SpaceAdmission() {}

    /**
     * An un-prefixed route that addresses the installation, not the Space an un-prefixed request falls back to: the
     * Space list and its create, rename and delete, and the Exchange (owner and consumer travel in the request). These
     * answer whatever the fallback Space admits; each decides per Space itself. The same paths the SPA never prefixes
     * ({@code SERVER_GLOBAL} in {@code inspecto-ui/src/app/inspecto/api/space-scope.ts}); its other entries are public.
     */
    static boolean installationScope(String path) {
        return path.equals("/spaces") || path.startsWith("/spaces/") || path.equals("/exchange") || path.startsWith("/exchange/");
    }

    /** Whether the request's Subject, already resolved against {@code bound} (the Space the request runs in), may use it. */
    static boolean admitsBound(HttpExchange ex, SpaceContext bound) {
        if (!bound.manifest().closed()) return true;
        if (!(ApiContext.attr(ex, ApiContext.ATTR_SUBJECT) instanceof Subject subject)) return true;   // no sign-in
        return subject.id().equals(bound.manifest().createdBy())
                || admitted(bound.root().config(), subject, ComponentAccess.heldRoles(ex));
    }

    /**
     * Whether the request's Subject may use {@code ctx}, a Space the request is not bound to (a row of a Space list):
     * the caller's grants are resolved again under THAT Space's role table, as {@link ApiContext#requireCapabilityIn}
     * does, and the request's own bound-Space attributes are put back.
     */
    static boolean admits(HttpExchange ex, SpaceContext ctx) {
        if (!ctx.manifest().closed()) return true;
        if (!(ApiContext.attr(ex, ApiContext.ATTR_SUBJECT) instanceof Subject bound)) return true;   // no sign-in
        if (bound.id().equals(ctx.manifest().createdBy())) return true;
        Path root = ctx.root().config();
        Authenticator a = Authenticators.active().orElse(null);
        if (root == null || a == null) return false;
        Object priorRoot = ApiContext.attr(ex, Roles.ATTR_CONFIG_ROOT);
        Object priorHeld = ApiContext.attr(ex, ComponentAccess.ATTR_HELD_ROLES);
        try {
            Roles.configRoot(ex, root);
            ApiContext.attr(ex, ComponentAccess.ATTR_HELD_ROLES, null);   // only what the Authenticator publishes for THIS Space counts
            Subject there = a.authenticate(ex).orElse(null);
            if (there == null || !there.id().equals(bound.id())) return false;
            if (there.machine()) Roles.admitMachine(root, there);   // throws where the Space does not name the client
            return admitted(root, there, ComponentAccess.heldRoles(ex));
        } catch (ApiException refused) {
            return false;
        } finally {
            ApiContext.attr(ex, Roles.ATTR_CONFIG_ROOT, priorRoot);
            ApiContext.attr(ex, ComponentAccess.ATTR_HELD_ROLES, priorHeld);
        }
    }

    /** Rules 2 to 4 for a Subject and the role names it holds, both resolved against the Space at {@code configRoot}. */
    private static boolean admitted(Path configRoot, Subject subject, Set<String> heldRoles) {
        if (subject.machine()) return true;   // only reached once Roles.admitMachine found its client named by this Space
        if (subject.capabilities().contains(Roles.CAN_ADMINISTER)) return true;
        // both lower-case (Roles.validate, ComponentAccess.heldRoles); empty when roles.toon is absent or unreadable
        return !Collections.disjoint(Roles.load(configRoot).authored().keySet(), heldRoles);
    }
}
