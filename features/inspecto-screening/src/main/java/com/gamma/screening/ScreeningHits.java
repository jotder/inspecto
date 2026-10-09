package com.gamma.screening;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.config.safety.PathJail;
import com.gamma.control.PendingChanges;
import com.gamma.event.EventLog;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Space's <b>Screening Hits</b> (SCR-D10 … SCR-D12, {@code docs/superpower/screening-addon-plan.md}): one
 * HMAC-signed JSON document per hit at {@code <write-root>/screening-hits/<id>.json} — the Action Requests storage
 * pattern exactly (atomic temp + move, jailed under the write root, signed with the Space's Pending Change key under
 * domain {@code screening-hit}). A document no server wrote — forged or edited — reads back
 * {@code integrity: invalid} and can never be decided. Not an OperationalDb family: the documents sit in the config
 * tree a Space backup carries; every import refuses the directory and a whole-Space export skips it.
 *
 * <p><b>States</b>: {@code open → confirmed | dismissed | escalated}, {@code escalated → confirmed | dismissed};
 * {@code confirmed} and {@code dismissed} are final. A decision names the hit's {@code version} (stale → 409) and a
 * reason; every transition is appended to {@code history}.
 *
 * <p><b>Identity</b> (SCR-D11): {@code dedupeKey = sha256(listId, entry, subjectKey)}. A Run never raises a second
 * hit for a key that already has one, whatever its state.
 */
public final class ScreeningHits {

    private ScreeningHits() {}

    static final String DIR = "screening-hits";
    static final String DOMAIN = "screening-hit";
    static final Pattern SAFE_ID = Pattern.compile("sh-\\d{14}-[0-9a-f]{6}");

    static final String OPEN = "open", CONFIRMED = "confirmed", DISMISSED = "dismissed", ESCALATED = "escalated",
            INVALID = "invalid";
    static final Set<String> STATES = Set.of(OPEN, CONFIRMED, DISMISSED, ESCALATED);

    /** A decision verb → the state it moves to. */
    static final Map<String, String> DECISIONS = Map.of("confirm", CONFIRMED, "dismiss", DISMISSED, "escalate", ESCALATED);

    private static final String MAC = "mac";
    private static final Object LOCK = new Object();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    static Object lock() { return LOCK; }

    /** Whether {@code from} may move to {@code to}. */
    static boolean allowed(String from, String to) {
        return OPEN.equals(from) ? !OPEN.equals(to) : ESCALATED.equals(from) && (CONFIRMED.equals(to) || DISMISSED.equals(to));
    }

    static String dedupeKey(String listId, String entry, String subjectKey) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{listId, entry, subjectKey}) {
                d.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fresh {@code open} hit (not yet saved). */
    static Map<String, Object> raise(Screener.Subject subject, Screener.Match match, double threshold,
                                     Map<String, Object> source, String by) {
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", "sh-" + STAMP.format(at) + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24)));
        rec.put("recordType", DOMAIN);
        rec.put("state", OPEN);
        rec.put("version", 1);
        rec.put("dedupeKey", dedupeKey(match.listId(), match.entry(), subject.key()));
        rec.put("listId", match.listId());
        rec.put("purpose", match.purpose());
        rec.put("entry", match.entry());
        rec.put("method", match.method());
        rec.put("score", match.score());
        rec.put("threshold", threshold);
        rec.put("subjectKey", subject.key());
        rec.put("subjectName", subject.name());
        rec.put("subjectIdentifier", subject.identifier());
        rec.put("source", source);
        rec.put("raisedAt", at.toString());
        rec.put("raisedBy", by);
        rec.put("history", new ArrayList<>(List.of(step(OPEN, by, at.toString(), null))));
        return rec;
    }

    /** Move {@code rec} to {@code state}: bump the version, record who / when / why, append to history. */
    @SuppressWarnings("unchecked")
    static void transition(Map<String, Object> rec, String state, String by, String reason) {
        String at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        rec.put("state", state);
        rec.put("version", ((Number) rec.get("version")).intValue() + 1);
        rec.put("decidedBy", by);
        rec.put("decidedAt", at);
        rec.put("reason", reason);
        List<Object> h = rec.get("history") instanceof List<?> l ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        h.add(step(state, by, at, reason));
        rec.put("history", h);
    }

    private static Map<String, Object> step(String state, String by, String at, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("by", by);
        m.put("at", at);
        if (reason != null) m.put("reason", reason);
        return m;
    }

    static boolean invalid(Map<String, Object> rec) {
        return INVALID.equals(rec.get("integrity"));
    }

    private static Path dir(Path root) {
        return root.resolve(DIR);
    }

    private static Path file(Path root, String id) {
        if (id == null || !SAFE_ID.matcher(id).matches())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "screening hit '" + id + "' not found");
        Path f = dir(root).resolve(id + ".json");
        if (!PathJail.contains(root.toAbsolutePath().normalize(), f))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "screening hit path escapes the config root");
        return f;
    }

    /** Persist {@code rec}, signed. ⛔ Never on a record that read back invalid — that would sign a forgery. */
    static void save(Path root, Map<String, Object> rec) throws IOException {
        if (invalid(rec)) throw new IllegalStateException("refusing to re-sign a screening hit that failed its integrity check");
        Path f = file(root, String.valueOf(rec.get("id")));
        Files.createDirectories(f.getParent());
        Map<String, Object> clean = new LinkedHashMap<>(rec);
        clean.remove(MAC);
        Map<String, Object> normal = ApiContext.JSON.readValue(ApiContext.JSON.writeValueAsBytes(clean), MAP);
        normal.put(MAC, PendingChanges.domainMac(root, DOMAIN, normal));
        AtomicFiles.write(f, ApiContext.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(normal), ".sh-");
        rec.put(MAC, normal.get(MAC));
    }

    /**
     * One hit, or {@code null}. Fail closed: an unreadable document is an IOException; a document whose MAC does not
     * verify, or that names another id or record type than its file, comes back {@code integrity: invalid}.
     */
    static Map<String, Object> read(Path root, String id) throws IOException {
        Path f = file(root, id);
        if (!Files.isRegularFile(f)) return null;
        Map<String, Object> rec = ApiContext.JSON.readValue(Files.readAllBytes(f), MAP);
        Object claimed = rec.remove(MAC);
        String expected = PendingChanges.domainMac(root, DOMAIN, rec);
        boolean ok = claimed instanceof String c && MessageDigest.isEqual(
                c.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
                && id.equals(rec.get("id")) && DOMAIN.equals(rec.get("recordType"));
        if (claimed != null) rec.put(MAC, claimed);
        if (!ok) {
            rec.put("id", id);
            rec.put("integrity", INVALID);
        }
        return rec;
    }

    /** Every hit of the Space, newest first. */
    static List<Map<String, Object>> list(Path root) throws IOException {
        Path d = dir(root);
        if (!Files.isDirectory(d)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String id = p.getFileName().toString().replaceFirst("\\.json$", "");
                if (!SAFE_ID.matcher(id).matches()) continue;
                Map<String, Object> rec = read(root, id);
                if (rec != null) out.add(rec);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("id"))).reversed());
        return out;
    }

    /** The wire shape: the record without its MAC. */
    static Map<String, Object> render(Map<String, Object> rec) {
        Map<String, Object> out = new LinkedHashMap<>(rec);
        out.remove(MAC);
        return out;
    }

    /** One AUDIT row (SCR-D14): ids, list, states and counts — never the screened name. Best effort. */
    static void audit(String actor, String actorType, String action, String message, Map<String, Object> attrs) {
        try {
            EventLog log = EventLog.current();
            if (log == null) return;
            Event.Builder b = Event.builder(EventType.AUDIT).source("audit").message(message)
                    .actor(actor).actorType(actorType).action(action).actionCategory("operation");
            attrs.forEach(b::attr);
            log.emit(b);
        } catch (RuntimeException ignored) {
            // best effort, like every audit emit — the record is what matters
        }
    }
}
