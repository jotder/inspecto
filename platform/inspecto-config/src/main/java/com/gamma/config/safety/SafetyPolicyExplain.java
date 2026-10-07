package com.gamma.config.safety;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The explain view of a Space's effective Safety Policy ({@code policy-narrowing-design.md} S7): every field of
 * the folded tier with its effective value and <b>which tier constrained it</b> - {@code server}, {@code space},
 * both, or {@code default} when neither file states it (the operator's roots plus the Space's own base, the
 * validator's caps). A {@code null} effective value means <em>unrestricted</em> (an allow-set no tier states, a
 * cap no tier states).
 *
 * <p>"Constrained" is per field kind: a {@code permit.*} is constrained only by a tier that states
 * {@code false}; an {@code allow.*}/{@code deny.*} set by any tier that states it; a {@code caps.*} by the tier(s)
 * whose stated value is the effective MIN. Pure; the route adds nothing but the Space id.
 */
public final class SafetyPolicyExplain {

    private SafetyPolicyExplain() {}

    public static Map<String, Object> explain(String spaceId, SafetyPolicyFiles.Tiers t) {
        SafetyPolicyTier s = t.server(), w = t.space(), e = t.effective();
        List<Map<String, Object>> fields = new ArrayList<>();
        field(fields, "mode", e.effectiveMode().name().toLowerCase(), s.mode() != null, false);
        permit(fields, "permit.network", e.network(), s.network(), w.network());
        permit(fields, "permit.install_extensions", e.installExtensions(), s.installExtensions(), w.installExtensions());
        permit(fields, "permit.advance_state", e.advanceState(), s.advanceState(), w.advanceState());
        permit(fields, "permit.rewind_state", e.rewindState(), s.rewindState(), w.rewindState());
        stated(fields, "allow.roots", strs(e.allowRoots()), s.allowRoots() != null, w.allowRoots() != null);
        stated(fields, "allow.hosts", strs(e.allowHosts()), s.allowHosts() != null, w.allowHosts() != null);
        stated(fields, "allow.connectors", sorted(e.allowConnectors()), s.allowConnectors() != null, w.allowConnectors() != null);
        stated(fields, "allow.extensions", sorted(e.allowExtensions()), s.allowExtensions() != null, w.allowExtensions() != null);
        stated(fields, "allow.formats", sorted(e.allowFormats()), s.allowFormats() != null, w.allowFormats() != null);
        stated(fields, "deny.roots", strs(e.denyRoots()), s.denyRoots() != null, w.denyRoots() != null);
        stated(fields, "deny.hosts", strs(e.denyHosts()), s.denyHosts() != null, w.denyHosts() != null);
        cap(fields, "caps.max_threads", e.maxThreads(), s.maxThreads(), w.maxThreads());
        cap(fields, "caps.max_batch_files", e.maxBatchFiles(), s.maxBatchFiles(), w.maxBatchFiles());
        cap(fields, "caps.max_batch_bytes", e.maxBatchBytes(), s.maxBatchBytes(), w.maxBatchBytes());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("space", spaceId);
        out.put("readable", true);
        out.put("files", Map.of("server", file(t.serverFile(), t.serverPresent()),
                "space", file(t.spaceFile(), t.spacePresent())));
        out.put("fields", fields);
        out.put("exempt", EXEMPT);
        return out;
    }

    /** D15: egress no Space run drives - configured by the server alone, so outside the Space tier. */
    private static final List<Map<String, String>> EXEMPT = List.of(
            Map.of("surface", "notification channels (webhook, SMTP)", "reason", "server -D properties only (D15, N10)"),
            Map.of("surface", "OIDC (JWKS, token relay)", "reason", "server -D properties (D15, N11)"),
            Map.of("surface", "intelligence agent to control plane", "reason", "loopback client, no Space run drives it (D15, N12)"));

    /** The answer for a Space whose policy file in scope cannot be read: no effective policy, the reason instead. */
    public static Map<String, Object> unreadable(String spaceId, SafetyPolicyUnreadableException ex) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("space", spaceId);
        out.put("readable", false);
        out.put("problem", ex.getMessage());
        return out;
    }

    private static Map<String, Object> file(java.nio.file.Path p, boolean present) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", p == null ? null : p.toString());
        m.put("present", present);
        return m;
    }

    private static void permit(List<Map<String, Object>> out, String name, Boolean eff, Boolean server, Boolean space) {
        field(out, name, eff == null || eff, Boolean.FALSE.equals(server), Boolean.FALSE.equals(space));
    }

    private static void stated(List<Map<String, Object>> out, String name, Object eff, boolean server, boolean space) {
        field(out, name, eff, server, space);
    }

    private static <T extends Comparable<T>> void cap(List<Map<String, Object>> out, String name, T eff, T server, T space) {
        field(out, name, eff, eff != null && Objects.equals(server, eff), eff != null && Objects.equals(space, eff));
    }

    private static void field(List<Map<String, Object>> out, String name, Object eff, boolean server, boolean space) {
        List<String> by = new ArrayList<>();
        if (server) by.add("server");
        if (space) by.add("space");
        if (by.isEmpty()) by.add("default");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", name);
        m.put("effective", eff);
        m.put("constrainedBy", by);
        out.add(m);
    }

    private static List<String> strs(List<?> l) {
        return l == null ? null : l.stream().map(Object::toString).toList();
    }

    private static List<String> sorted(java.util.Set<String> s) {
        return s == null ? null : s.stream().sorted().toList();
    }
}
