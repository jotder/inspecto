package com.gamma.control;

import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-space Menu tree (Menu Builder) — user-curated sidebar navigation, persisted as
 * {@code nav-menus.toon} in the space's config tree (crash-safe TOON, mirroring {@link BrandingSettings}).
 * The wire contract is the shape the UI froze mock-first (menu-builder plan §4.7):
 * {@code {space, version: 1, landing?, nodes: [{id, title, icon?, children?|binding?{kind, componentId}}]}}.
 *
 * <p><b>Route binding (UIE-7).</b> A leaf whose binding is {@code {kind: route, route: "/cases"}} opens an in-app
 * screen instead of a library Component, so a business user reaches platform panes (Cases, Alerts, …) from the
 * Space menu. The route is validated conservatively by {@link #checkRoute}: an absolute in-app path — no scheme,
 * no protocol-relative {@code //} or {@code /\}, no {@code ..} segment, no whitespace or control characters.
 *
 * <p><b>Space landing (UIE-7).</b> {@code landing} names the leaf the SPA opens instead of the platform Home when
 * the app is opened at {@code /} or the Space is switched to. It must name a leaf of this tree on a PUT (422
 * otherwise); on a read a dangling landing (hand-edited file) is dropped rather than failing the whole tree.
 *
 * <p>On disk only {@code {version, landing?, nodes}} is stored — the file already lives inside one space's config
 * tree, so persisting the space id would be redundant and could go stale; the route stamps {@code space}
 * from the request seam on every response.
 *
 * <p>{@link #sanitize} is both the 422 validation walk and the canonicalizer: it whitelist-copies the
 * known node fields (junk keys never reach disk), rejects blank {@code id}/{@code title}, duplicate ids,
 * a node carrying both {@code children} and {@code binding}, and a runaway node count.
 */
record NavMenus(List<Map<String, Object>> nodes, String landing) {

    static final NavMenus EMPTY = new NavMenus(List.of(), null);
    static final int VERSION = 1;
    /** Defence-in-depth cap on total nodes across the whole tree (the UI authors dozens, not thousands). */
    static final int MAX_NODES = 2000;
    /** The binding kind that opens an in-app route rather than a library Component. */
    static final String ROUTE_KIND = "route";
    /** Longest accepted route — an in-app path, not a data channel. */
    static final int MAX_ROUTE_LENGTH = 512;

    /** Write to {@code nav-menus.toon} at {@code path} (canonical TOON, crash-safe). */
    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        if (landing != null) m.put("landing", landing);
        m.put("nodes", nodes);
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".nav-menus-");
    }

    /** Read {@code nav-menus.toon} at {@code path}; missing/unreadable/invalid → {@link #EMPTY}. */
    static NavMenus read(Path path) {
        if (!Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            List<Map<String, Object>> nodes = sanitize(m.get("nodes"));
            String landing = m.get("landing") instanceof String l && isLeaf(nodes, l) ? l : null;
            return new NavMenus(nodes, landing);
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /** Validate a PUT body's {@code nodes} + {@code landing} into a canonical tree. Throws
     *  {@link IllegalArgumentException} (→ 422) on a structural violation or a landing that names no leaf. */
    static NavMenus of(Object rawNodes, Object rawLanding) {
        List<Map<String, Object>> nodes = sanitize(rawNodes);
        if (rawLanding == null) return new NavMenus(nodes, null);
        if (!(rawLanding instanceof String l) || !isLeaf(nodes, l))
            throw new IllegalArgumentException("landing must name a menu item (a leaf) of this tree");
        return new NavMenus(nodes, l);
    }

    /** Whether {@code id} names a leaf (a node with a binding) anywhere in {@code nodes}. */
    @SuppressWarnings("unchecked")
    static boolean isLeaf(List<Map<String, Object>> nodes, String id) {
        for (Map<String, Object> n : nodes) {
            if (id.equals(n.get("id"))) return n.get("binding") != null;
            if (n.get("children") instanceof List<?> kids && isLeaf((List<Map<String, Object>>) kids, id)) return true;
        }
        return false;
    }

    /** Validate + canonicalize a raw {@code nodes} value. Throws {@link IllegalArgumentException} on a
     *  structural violation (the route maps it to 422). {@code null} → an empty tree. */
    static List<Map<String, Object>> sanitize(Object raw) {
        return sanitizeNodes(raw, new HashSet<>(), new int[1]);
    }

    private static List<Map<String, Object>> sanitizeNodes(Object raw, Set<String> ids, int[] count) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("nodes must be a list");
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> node)) throw new IllegalArgumentException("each node must be an object");
            if (++count[0] > MAX_NODES) throw new IllegalArgumentException("too many nodes (max " + MAX_NODES + ")");
            String id = requireText(node, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate node id '" + id + "'");
            String title = requireText(node, "title");
            Object children = node.get("children");
            Object binding = node.get("binding");
            if (children != null && binding != null)
                throw new IllegalArgumentException("node '" + id + "': children and binding are mutually exclusive");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", id);
            c.put("title", title);
            if (node.get("icon") instanceof String icon && !icon.isBlank()) c.put("icon", icon);
            if (binding != null) {
                if (!(binding instanceof Map<?, ?> b))
                    throw new IllegalArgumentException("node '" + id + "': binding must be an object");
                Map<String, Object> cb = new LinkedHashMap<>();
                String kind = requireText(b, "kind");
                cb.put("kind", kind);
                if (ROUTE_KIND.equals(kind)) cb.put("route", checkRoute(id, requireText(b, "route")));
                else cb.put("componentId", requireText(b, "componentId"));
                c.put("binding", cb);
            } else if (children != null) {
                c.put("children", sanitizeNodes(children, ids, count));
            }
            out.add(c);
        }
        return out;
    }

    /**
     * A route binding's target must stay inside the SPA: an absolute path ({@code /cases}, optionally with a
     * query or fragment), never a scheme ({@code https:}, {@code javascript:}), a protocol-relative host
     * ({@code //evil}, or {@code /\evil}, which browsers read the same way), a {@code ..} segment, or
     * whitespace / control characters.
     */
    static String checkRoute(String nodeId, String route) {
        String bad = null;
        if (route.length() > MAX_ROUTE_LENGTH) bad = "is longer than " + MAX_ROUTE_LENGTH + " characters";
        else if (route.charAt(0) != '/') bad = "must start with '/'";
        else if (route.startsWith("//") || route.indexOf('\\') >= 0) bad = "must not name another host";
        else if (route.contains(":")) bad = "must not carry a scheme";
        else if (route.chars().anyMatch(ch -> ch <= ' ' || ch == 0x7f)) bad = "must not contain whitespace";
        else {
            int end = route.length();
            for (char stop : new char[]{'?', '#'}) { int i = route.indexOf(stop); if (i >= 0 && i < end) end = i; }
            for (String seg : route.substring(0, end).split("/"))
                if (seg.equals("..") || seg.equals(".")) { bad = "must not contain '.' or '..' segments"; break; }
        }
        if (bad != null) throw new IllegalArgumentException("node '" + nodeId + "': route " + bad);
        return route;
    }

    private static String requireText(Map<?, ?> m, String key) {
        if (m.get(key) instanceof String s && !s.isBlank()) return s;
        throw new IllegalArgumentException("missing or blank '" + key + "'");
    }
}
