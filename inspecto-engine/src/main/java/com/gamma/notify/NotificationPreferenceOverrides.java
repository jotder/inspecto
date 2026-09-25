package com.gamma.notify;

import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import com.gamma.config.io.ConfigCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.gamma.notify.NotificationPreferences.EMAIL;
import static com.gamma.notify.NotificationPreferences.IN_APP;
import static com.gamma.notify.NotificationPreferences.WEBHOOK;

/**
 * The per-Subject override layer over the deployment-default {@link NotificationPreferences} grid
 * (ses-sns-adapter-design §7 — fixes SEC review F2, where one global grid was edited as if it were each
 * caller's own). Each stable subject id may store a <em>sparse</em> set of cells; the effective answer is
 * {@link #enabled that override, else the default}.
 *
 * <ul>
 *   <li><b>Critical categories are locked on at both layers</b> — nothing is ever stored for them.</li>
 *   <li><b>Only {@code inApp} and {@code email} are personal.</b> {@code webhook} is an operator-configured
 *       destination with no per-user address, so its cells always inherit the default.</li>
 *   <li><b>Email needs a verified address.</b> The address is the Subject's verified email claim, handed in
 *       by the HTTP edge on every save — never anything from a request body. With no claim, email cannot be
 *       turned on and reads as off, whatever the default says.</li>
 * </ul>
 *
 * <p><b>Storage.</b> One per-DEPLOYMENT file (users span Spaces), written crash-safe through
 * {@link AtomicFiles}; {@code null} path = in memory only. A file that exists but cannot be read leaves the
 * store <em>unwritable</em> ({@link #apply} throws {@link IllegalStateException}) rather than letting the next
 * save overwrite every other user's preferences with one user's.
 *
 * <p>A Subject is <b>enrolled</b> for personal email delivery once it has saved its preferences — that save is
 * when the edge sees its claim. Enrolment records the address the claim carried at that moment.
 *
 * @since 4.0.0
 */
public final class NotificationPreferenceOverrides {

    private static final Logger log = LoggerFactory.getLogger(NotificationPreferenceOverrides.class);

    /** The deployment file's name (under the spaces container root, or the single-tenant write root). */
    public static final String FILE = "notification-preferences.toon";
    /** Cell source: the value comes from the deployment default. */
    public static final String INHERITED = "inherited";
    /** Cell source: the Subject stored its own value. */
    public static final String OVERRIDDEN = "overridden";

    /** Channels a Subject may override — see the class doc for why {@code webhook} is not one. */
    private static final List<String> PERSONAL = List.of(IN_APP, EMAIL);
    private static final List<String> GRID_CHANNELS = List.of(IN_APP, EMAIL, WEBHOOK);

    /** A Subject that can receive personal email: its id and the verified address it saved with. */
    public record Enrolled(String subject, String email) {}

    private final Path file;
    private final boolean writable;
    /** subject id → verified email ("" = none); insertion-ordered so the file is stable. */
    private final Map<String, String> emails = new LinkedHashMap<>();
    /** subject id → category → channel → on. */
    private final Map<String, Map<String, Map<String, Boolean>>> overrides = new LinkedHashMap<>();

    private NotificationPreferenceOverrides(Path file, boolean writable) {
        this.file = file;
        this.writable = writable;
    }

    /** No durable home — overrides last as long as the process (every test, and a deployment with no root). */
    public static NotificationPreferenceOverrides inMemory() {
        return new NotificationPreferenceOverrides(null, true);
    }

    /** Open (without creating) the deployment file; {@code null} ⇒ {@link #inMemory()}. */
    public static NotificationPreferenceOverrides open(Path file) {
        if (file == null) return inMemory();
        if (!Files.exists(file)) return new NotificationPreferenceOverrides(file, true);
        try {
            NotificationPreferenceOverrides o = new NotificationPreferenceOverrides(file, true);
            o.load(ToonHelper.load(file.toString()));
            return o;
        } catch (Exception e) {
            log.error("notification preference overrides {} are unreadable ({}) — serving the deployment "
                    + "default to everyone and REFUSING personal saves until the file is repaired", file, e.getMessage());
            return new NotificationPreferenceOverrides(file, false);
        }
    }

    @SuppressWarnings("unchecked")
    private void load(Map<String, Object> doc) {
        if (doc.get("subjects") instanceof List<?> subjects)
            for (Object o : subjects)
                if (o instanceof Map<?, ?> m && m.get("id") != null)
                    emails.put(String.valueOf(m.get("id")), m.get("email") == null ? "" : String.valueOf(m.get("email")));
        if (doc.get("overrides") instanceof List<?> rows)
            for (Object o : rows) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object subject = m.get("subject"), category = m.get("category"), channel = m.get("channel");
                if (subject == null || category == null || channel == null) continue;
                boolean on = Boolean.TRUE.equals(m.get("enabled")) || "true".equals(String.valueOf(m.get("enabled")));
                if (!storable(String.valueOf(category), String.valueOf(channel))) continue;
                emails.putIfAbsent(String.valueOf(subject), "");
                overrides.computeIfAbsent(String.valueOf(subject), k -> new LinkedHashMap<>())
                        .computeIfAbsent(String.valueOf(category), k -> new LinkedHashMap<>())
                        .put(String.valueOf(channel), on);
            }
    }

    /** Whether a cell may hold an override at all: a known, non-critical category and a personal channel. */
    private static boolean storable(String category, String channel) {
        return PERSONAL.contains(channel)
                && NotificationCategory.byId(category).map(c -> !c.critical()).orElse(false);
    }

    /** The Subject's own stored value for one cell, if any. */
    public synchronized Optional<Boolean> override(String subject, String category, String channel) {
        Map<String, Map<String, Boolean>> byCat = overrides.get(subject);
        if (byCat == null || byCat.get(category) == null) return Optional.empty();
        return Optional.ofNullable(byCat.get(category).get(channel));
    }

    /**
     * The effective preference: critical ⇒ on; email with no verified {@code email} ⇒ off; else the
     * Subject's override when there is one, else {@code defaults}.
     */
    public synchronized boolean enabled(NotificationPreferences defaults, String category, String channel,
                                        String subject, String email) {
        if (NotificationCategory.byId(category).map(NotificationCategory::critical).orElse(false)) return true;
        if (EMAIL.equals(channel) && blank(email)) return false;
        return override(subject, category, channel).orElseGet(() -> defaults.enabled(category, channel));
    }

    /** Whether ANY Subject has turned {@code channel} on for {@code category} — the shared feed must then
     *  hold the notification so that Subject's view can show it. */
    public synchronized boolean anyEnables(String category, String channel) {
        for (Map<String, Map<String, Boolean>> byCat : overrides.values()) {
            Map<String, Boolean> ch = byCat.get(category);
            if (ch != null && Boolean.TRUE.equals(ch.get(channel))) return true;
        }
        return false;
    }

    /** Subjects that saved with a verified address, in first-save order. */
    public synchronized List<Enrolled> enrolled() {
        List<Enrolled> out = new ArrayList<>();
        emails.forEach((s, e) -> { if (!blank(e)) out.add(new Enrolled(s, e)); });
        return out;
    }

    /**
     * Apply one Subject's edits and persist. {@code changes} is category → channel → value, where a
     * {@code null} value RESETS that cell to the default. Unknown/critical categories, non-personal channels
     * and email-on without an address are ignored. {@code email} (the verified claim, or {@code null})
     * replaces the stored address every time, so an address never outlives its claim.
     *
     * @throws IllegalStateException when the deployment file exists but could not be read
     */
    public synchronized void apply(String subject, String email, Map<String, ? extends Map<String, Boolean>> changes)
            throws IOException {
        if (!writable)
            throw new IllegalStateException("notification preference overrides " + file + " are unreadable; "
                    + "personal saves are refused until the file is repaired");
        emails.put(subject, blank(email) ? "" : email.trim());
        Map<String, Map<String, Boolean>> mine = overrides.computeIfAbsent(subject, k -> new LinkedHashMap<>());
        changes.forEach((category, channels) -> {
            if (channels == null) return;
            channels.forEach((channel, on) -> {
                if (!storable(category, channel)) return;
                if (on == null) {
                    Map<String, Boolean> ch = mine.get(category);
                    if (ch != null) ch.remove(channel);
                    return;
                }
                if (on && EMAIL.equals(channel) && blank(email)) return;   // no verified address, no email
                mine.computeIfAbsent(category, k -> new LinkedHashMap<>()).put(channel, on);
            });
        });
        mine.values().removeIf(Map::isEmpty);
        persist();
    }

    /**
     * The Subject's effective grid — one row per category, the {@link NotificationPreferences#grid()} shape
     * plus {@code source} ({@link #INHERITED} / {@link #OVERRIDDEN}) and {@code editable} per channel.
     */
    public synchronized List<Map<String, Object>> grid(NotificationPreferences defaults, String subject, String email) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NotificationCategory c : NotificationCategory.values()) {
            Map<String, Boolean> channels = new LinkedHashMap<>();
            Map<String, String> source = new LinkedHashMap<>();
            Map<String, Boolean> editable = new LinkedHashMap<>();
            for (String ch : GRID_CHANNELS) {
                channels.put(ch, enabled(defaults, c.id(), ch, subject, email));
                source.put(ch, override(subject, c.id(), ch).isPresent() ? OVERRIDDEN : INHERITED);
                editable.put(ch, storable(c.id(), ch) && !(EMAIL.equals(ch) && blank(email)));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", c.id());
            row.put("label", c.label());
            row.put("critical", c.critical());
            row.put("available", c.available());
            row.put("channels", channels);
            row.put("source", source);
            row.put("editable", editable);
            rows.add(row);
        }
        return rows;
    }

    private void persist() throws IOException {
        if (file == null) return;
        List<Map<String, Object>> subjects = new ArrayList<>();
        emails.forEach((s, e) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s);
            m.put("email", e);
            subjects.add(m);
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        overrides.forEach((s, byCat) -> byCat.forEach((cat, chs) -> chs.forEach((ch, on) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject", s);
            m.put("category", cat);
            m.put("channel", ch);
            m.put("enabled", on);
            rows.add(m);
        })));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("subjects", subjects);
        doc.put("overrides", rows);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        AtomicFiles.write(file, ConfigCodec.toToon(doc).getBytes(StandardCharsets.UTF_8), ".notify-prefs-");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
