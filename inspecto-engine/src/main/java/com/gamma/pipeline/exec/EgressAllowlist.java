package com.gamma.pipeline.exec;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * <b>A Space's egress allowlist, read by every outbound caller</b> — the Action Request dispatcher, the
 * {@code sink.webhook} Step and the webhook notification channel ({@code WEBHOOK-EGRESS-POLICY-1}). Persisted as
 * {@code egress.toon} in the Space's config root ({@code allow: [tickets.internal, 10.20.0.0/16]}) and written by
 * {@code PUT /settings/egress}. A file that is present but unreadable reads as EMPTY — fail closed.
 *
 * <h3>One-time seeding at upgrade (operator decision 2026-09-27)</h3>
 * Before this, the two webhooks dialled any address, so a Space may already send to private hosts with no entry.
 * At service start ({@link #migrate}) a Space that has no {@code egress.toon} is seeded from the hosts it already targets —
 * every {@code sink.webhook} Step's Connection host, every {@code WEBHOOK} channel's URL host, and the host of
 * {@code -Dnotify.webhook.url} (the URL the webhook channel actually posts to) — persists it with a
 * {@code seededAt} stamp, logs it and emits an {@code egress-allowlist.seeded} audit event. A Space created through the product gets an EMPTY file ({@link #recordEmpty}) and the read path never seeds. Once the file exists it
 * is never seeded again, so a later PUT that removes a seeded entry sticks. A seeded host that can never be
 * allowlisted (a loopback or link-local literal) is skipped and logged. Hosts only: a host entry lifts only the
 * private and CGNAT classes ({@link EgressPolicy}), so seeding a public host changes nothing.
 */
public final class EgressAllowlist {

    private static final Logger log = LoggerFactory.getLogger(EgressAllowlist.class);

    /** The allowlist file in a Space's config root. */
    public static final String FILE = "egress.toon";
    /** The Step type and the channel kind whose targets seed an allowlist. */
    static final String WEBHOOK_STEP = "sink.webhook";
    private static final int MAX_SCAN_DEPTH = 4;
    private static final long MAX_SCAN_BYTES = 1L << 20;

    private static final Object LOCK = new Object();

    private EgressAllowlist() {}

    /** The current Space's parsed allowlist ({@link SpaceConfigRoot#current()}); EMPTY when it has no root. */
    public static EgressPolicy.Allowlist forCurrentSpace() {
        return of(SpaceConfigRoot.current());
    }

    /** The parsed allowlist of the Space whose config root is {@code root}. */
    public static EgressPolicy.Allowlist of(Path root) {
        return EgressPolicy.Allowlist.of(entries(root));
    }

    /**
     * The Space's allowlist entries; empty when none (or unreadable). ⛔ NEVER seeds: a Space with no
     * {@code egress.toon} reads as EMPTY (deny) — seeding here would let whoever creates a Space, a Connection and a
     * webhook Step before the first send allowlist a private host without {@code canAdminister}.
     */
    public static List<String> entries(Path root) {
        if (root == null) return List.of();
        Path f = root.resolve(FILE);
        if (!Files.exists(f)) {
            if (WARNED.add(root.toAbsolutePath().normalize().toString()))
                log.warn("[EGRESS] {} has no {} — the egress allowlist is EMPTY (deny by default)", root, FILE);
            return List.of();
        }
        try {
            Object allow = ToonHelper.load(f.toString()).get("allow");
            List<String> out = new ArrayList<>();
            if (allow instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
            EgressPolicy.Allowlist.of(out);   // validate
            return out;
        } catch (Exception bad) {
            log.warn("[EGRESS] {} is unreadable or invalid ({}) — treating the egress allowlist as EMPTY", f, bad.getMessage());
            return List.of();
        }
    }

    private static final Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The ONE-TIME upgrade migration, run at service start for each Space config root ({@code SpaceManager}): a Space
     * with no {@code egress.toon} is seeded from the hosts it targets today and the file written. A Space that already
     * has the file — including one created through the product, which {@link #recordEmpty} marks — is untouched. A
     * Space folder an operator drops onto disk is seeded at the next boot (an operator action, trusted). If the file
     * cannot be written nothing is allowed: the error is logged and the list stays EMPTY (fail closed).
     */
    public static void migrate(Path root) {
        if (root == null) return;
        Path f = root.resolve(FILE);
        synchronized (LOCK) {
            if (Files.exists(f)) return;
            List<String> hosts = new ArrayList<>();
            for (String h : currentTargetHosts(root)) {
                try {
                    EgressPolicy.Allowlist.of(List.of(h));
                    hosts.add(h);
                } catch (IllegalArgumentException never) {
                    log.warn("[EGRESS] not seeding '{}' into the egress allowlist: {}", h, never.getMessage());
                }
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("allow", hosts);
            doc.put("seededAt", Instant.now().toString());
            try {
                write(root, doc);
            } catch (IOException e) {
                log.error("[EGRESS] could not persist the seeded egress allowlist {} ({}) — the allowlist stays EMPTY "
                        + "(deny) until an administrator writes it with PUT /settings/egress", f, e.getMessage());
                return;
            }
            log.info("[EGRESS] seeded the egress allowlist {} from this Space's current webhook targets: {}", f, hosts);
            try {
                EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                        .message("the egress allowlist was seeded from the Space's webhook targets: " + hosts)
                        .actor("system").actorType("system")
                        .action("egress-allowlist.seeded").actionCategory("configuration")
                        .attr("after", String.join(",", hosts)));
            } catch (RuntimeException ignored) {
                // best effort, like every audit emit
            }
        }
    }

    /** Record an EMPTY allowlist for a Space created through the product, so {@link #migrate} never seeds it. */
    public static void recordEmpty(Path root) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("allow", List.of());
        doc.put("createdAt", Instant.now().toString());
        synchronized (LOCK) {
            write(root, doc);
        }
    }

    private static void write(Path root, Map<String, Object> doc) throws IOException {
        Files.createDirectories(root);
        AtomicFiles.write(root.resolve(FILE), JToon.encode(doc).getBytes(StandardCharsets.UTF_8), ".egress-");
    }

    /**
     * The hosts this Space's webhooks target today: {@code sink.webhook} Step Connections (a flat
     * {@code webhook: {connection}} block or a graph {@code sink.webhook} node), {@code WEBHOOK} channels in
     * {@code registry/channels/}, and {@code -Dnotify.webhook.url}. Lower-cased, de-duplicated, in discovery order.
     */
    static Set<String> currentTargetHosts(Path root) {
        Set<String> connections = new LinkedHashSet<>();
        Set<String> hosts = new LinkedHashSet<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.walk(root, MAX_SCAN_DEPTH)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString();
                    if (!n.endsWith(".toon") || n.endsWith("_connection.toon") || n.equals(FILE)) continue;
                    if (!Files.isRegularFile(p) || Files.size(p) > MAX_SCAN_BYTES) continue;
                    if (root.relativize(p).toString().contains(".history")) continue;
                    Map<String, Object> doc;
                    try {
                        doc = ToonHelper.load(p.toString());
                    } catch (Exception unreadable) {
                        continue;
                    }
                    webhookConnections(doc, connections);
                    if (p.getParent() != null && p.getParent().getFileName() != null
                            && "channels".equals(p.getParent().getFileName().toString()))
                        channelHost(doc, hosts);
                }
            } catch (IOException e) {
                log.warn("[EGRESS] could not scan {} for webhook targets ({})", root, e.getMessage());
            }
        }
        for (String id : connections) {
            String h = connectionHost(root, id);
            if (h != null) hosts.add(h);
        }
        addUrlHost(System.getProperty("notify.webhook.url"), hosts);
        return hosts;
    }

    private static void webhookConnections(Map<String, Object> doc, Set<String> out) {
        if (doc.get("webhook") instanceof Map<?, ?> w && w.get("connection") != null)
            out.add(String.valueOf(w.get("connection")).trim());
        if (doc.get("nodes") instanceof List<?> nodes)
            for (Object o : nodes)
                if (o instanceof Map<?, ?> node && WEBHOOK_STEP.equals(node.get("type"))
                        && node.get("config") instanceof Map<?, ?> c && c.get("connection") != null)
                    out.add(String.valueOf(c.get("connection")).trim());
    }

    private static void channelHost(Map<String, Object> doc, Set<String> out) {
        if (doc.get("kind") != null && "webhook".equalsIgnoreCase(String.valueOf(doc.get("kind"))))
            addUrlHost(doc.get("target") == null ? null : String.valueOf(doc.get("target")), out);
    }

    private static String connectionHost(Path root, String id) {
        Path f = root.resolve(id + "_connection.toon");
        ConnectionProfile p = null;
        try {
            if (Files.isRegularFile(f)) p = ConnectionProfile.load(f);
        } catch (Exception unreadable) {
            // fall back to the registry
        }
        if (p == null) p = ConnectionRegistry.find(id).orElse(null);
        return p == null || p.host() == null || p.host().isBlank() ? null : p.host().trim().toLowerCase(Locale.ROOT);
    }

    private static void addUrlHost(String url, Set<String> out) {
        if (url == null || url.isBlank()) return;
        try {
            String h = URI.create(url.trim()).getHost();
            if (h != null && !h.isBlank()) out.add(h.replaceAll("^\\[|\\]$", "").toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            // not a URL — nothing to seed
        }
    }
}
