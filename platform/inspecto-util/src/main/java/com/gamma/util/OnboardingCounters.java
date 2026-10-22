package com.gamma.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The **onboarding counters** of this installation ({@code LA-ONBOARD-1} OB-13, use case answer A-10): how far beginners
 * get, counted locally on every edition and never sent anywhere. One append-only file per installation, one line per event:
 * {@code <instant>\t<event>[\t<step>]}. A line holds a time, a word of the closed {@link Event} vocabulary and, for an
 * abandoned step, a word of the closed {@link #STEPS} vocabulary - never an id, a name, a value or anything a person typed.
 *
 * <p>The host {@link #install installs} the file at boot (the server-wide document home, beside {@code la-limits.toon})
 * and records {@link Event#LAUNCH}; the routes where an action happens record the rest. It lives here, below both the
 * control plane and Link Analysis (which records the first graph), like {@link IngestActivity}. Process-wide by design:
 * the counters describe the installation, not a Space. With no file installed every call is a no-op.
 *
 * <p>The {@code first_*} events and {@code guide_completed} are written once per installation (the file is read at
 * install); {@code launch}, {@code space_created} and {@code step_abandoned} every time.
 */
public final class OnboardingCounters {

    /** The closed event vocabulary; the wire word is {@link #word()}. */
    public enum Event {
        LAUNCH(false), SPACE_CREATED(false), FIRST_DATASET(true), FIRST_GRAPH(true), GUIDE_COMPLETED(true), STEP_ABANDONED(false);

        private final boolean once;

        Event(boolean once) {
            this.once = once;
        }

        /** Written at most once per installation. */
        public boolean once() {
            return once;
        }

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The event a wire word names, or null. */
        public static Event of(String word) {
            if (word == null) return null;
            for (Event e : values()) if (e.word().equals(word)) return e;
            return null;
        }
    }

    /** The steps a beginner can leave unfinished: the Space guide's lines and the Add data screen's stages. */
    public static final Set<String> STEPS = Set.of(
            "space", "data", "columns", "graph", "list", "add_data:file", "add_data:columns", "add_data:create");

    /** The events a browser may report ({@code POST /onboarding/events}); the rest are recorded where the action happens. */
    public static final Set<Event> REPORTABLE = EnumSet.of(Event.GUIDE_COMPLETED, Event.STEP_ABANDONED);

    private static Path file;
    private static final Set<Event> written = EnumSet.noneOf(Event.class);
    private static Clock clock = Clock.systemUTC();

    private OnboardingCounters() {}

    /** Use {@code path} from now on (null switches counting off); reads which once-only events it already holds. */
    public static synchronized void install(Path path) {
        file = path;
        written.clear();
        for (String[] line : lines()) {
            Event e = Event.of(line.length > 1 ? line[1] : null);
            if (e != null && e.once()) written.add(e);
        }
    }

    /** The installed file, or null. */
    public static synchronized Path file() {
        return file;
    }

    /** For tests: the clock events are stamped with. */
    static synchronized void clock(Clock c) {
        clock = c == null ? Clock.systemUTC() : c;
    }

    /** Records {@code event} (no step). */
    public static void record(Event event) {
        record(event, null);
    }

    /**
     * Records {@code event}, with {@code step} for {@link Event#STEP_ABANDONED} (a word of {@link #STEPS}; anything else is
     * dropped, so nothing typed can reach the file). A once-only event already written is skipped. Never throws: counting
     * must not fail the action it counts.
     */
    public static synchronized void record(Event event, String step) {
        if (file == null || event == null) return;
        if (event.once() && written.contains(event)) return;
        String s = event == Event.STEP_ABANDONED && step != null && STEPS.contains(step) ? "\t" + step : "";
        String line = Instant.now(clock) + "\t" + event.word() + s + "\n";
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (event.once()) written.add(event);
        } catch (IOException | UncheckedIOException e) {
            // A full disk or a read-only home: the action still succeeds, the count is lost.
        }
    }

    /**
     * What the file says, for {@code GET /onboarding/metrics}: when each milestone first happened, the seconds from the first
     * launch to it, how many launches and Spaces, and which steps were left unfinished how often. {@code recording} is false
     * when no file is installed (the server has no document home).
     */
    public static synchronized Map<String, Object> metrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recording", file != null);
        Instant firstLaunch = null;
        Map<Event, Instant> first = new LinkedHashMap<>();
        int launches = 0, spaces = 0;
        Map<String, Integer> abandoned = new TreeMap<>();
        for (String[] line : lines()) {
            Event e = Event.of(line.length > 1 ? line[1] : null);
            Instant at = instant(line[0]);
            if (e == null || at == null) continue;
            first.putIfAbsent(e, at);
            switch (e) {
                case LAUNCH -> {
                    launches++;
                    if (firstLaunch == null) firstLaunch = at;
                }
                case SPACE_CREATED -> spaces++;
                case STEP_ABANDONED -> {
                    if (line.length > 2 && STEPS.contains(line[2])) abandoned.merge(line[2], 1, Integer::sum);
                }
                default -> { }
            }
        }
        out.put("firstLaunch", firstLaunch == null ? null : firstLaunch.toString());
        out.put("launches", launches);
        out.put("spacesCreated", spaces);
        milestone(out, "firstSpace", first.get(Event.SPACE_CREATED), firstLaunch);
        milestone(out, "firstDataset", first.get(Event.FIRST_DATASET), firstLaunch);
        milestone(out, "firstGraph", first.get(Event.FIRST_GRAPH), firstLaunch);
        milestone(out, "guideCompleted", first.get(Event.GUIDE_COMPLETED), firstLaunch);
        out.put("abandoned", abandoned);
        return out;
    }

    private static void milestone(Map<String, Object> out, String key, Instant at, Instant launch) {
        out.put(key, at == null ? null : at.toString());
        out.put(key + "Seconds", at == null || launch == null || at.isBefore(launch)
                ? null : Duration.between(launch, at).toSeconds());
    }

    private static Instant instant(String s) {
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String[]> lines() {
        List<String[]> out = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) return out;
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l.split("\t", -1));
        } catch (IOException | UncheckedIOException e) {
            // unreadable: counted as empty
        }
        return out;
    }
}
