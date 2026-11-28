package com.gamma.job;

import com.gamma.signal.SignalType;
import com.gamma.signal.SignalTypeCatalog;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.Signals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks a Job gets when it is saved (SIGNAL-BOARD-1 SB-2): its {@code on_signal} against the Signal Type catalog,
 * every {@code $signal.<field>} of its {@code when} and {@code bind} against the fields that type declares, and the
 * chain of Jobs it joins. Pure: it reads only what it is given, writes nothing and never refuses — the save door
 * decides what a finding means for its caller ({@link Finding#agentRefusable()}).
 *
 * <p><b>What a Job posts</b> (for the chain): the {@link #LIFECYCLE} types every Run posts, plus the {@code emits} its
 * Job Type declares. A Job is fired by a type it posts only through another Job: the dispatcher never fires a Job on
 * its own Signal (the self-loop guard in {@code JobService.onSignalEvent}). A Job with a {@code when} guard is not a
 * link of an unconditional chain: the guard may stop it, and a save cannot evaluate it.
 */
public final class JobSignalCheck {

    /** The types the Job service posts for every Run of every Job, with the Job as source. */
    public static final Set<String> LIFECYCLE = Set.of(SignalType.JOB_RUN_STARTED, SignalType.JOB_RUN_COMPLETED,
            SignalType.JOB_RUN_FAILED, SignalType.JOB_RUN_REJECTED);

    /** A {@code $signal.<field>} reference: the first path segment is the payload field. */
    private static final Pattern SIGNAL_REF = Pattern.compile("\\$signal\\.([A-Za-z_][A-Za-z0-9_]*)");

    /** What a finding is about. */
    public enum Kind {
        /** {@code on_signal} names no catalogued type: nothing posts it today (D-SB3: a warning for everyone). */
        UNPOSTED_TYPE("SIGNAL_TYPE_UNPOSTED"),
        /** A {@code $signal.<field>} the type does not declare. */
        UNDECLARED_FIELD("SIGNAL_FIELD_UNDECLARED"),
        /** The type's fields are not declared (a Job Pack's emit, a family with run-time fields): not checkable. */
        FIELDS_NOT_DECLARED("SIGNAL_FIELDS_NOT_DECLARED"),
        /** Saving the Job closes a chain of Jobs on itself. */
        CHAIN_LOOP("JOB_CHAIN_LOOP");

        public final String code;

        Kind(String code) { this.code = code; }
    }

    /**
     * One finding.
     *
     * @param key     the Job key it is about ({@code on_signal}, {@code when}, {@code bind.<name>})
     * @param subject what makes two findings the same one across a save: the type, the field, or the loop's Jobs
     * @param fix     what to change
     */
    public record Finding(Kind kind, String key, String subject, String message, String fix) {
        /** D-CO1: refused for an agent, a warning for a person. D-SB3 exempts an unposted type; a field that cannot
         *  be checked is never a refusal. */
        public boolean agentRefusable() {
            return kind == Kind.UNDECLARED_FIELD || kind == Kind.CHAIN_LOOP;
        }

        /** Same finding (kind, key and subject), whatever the wording. A loop is one finding per Job whichever Jobs
         *  it runs through: the check names only the first loop it finds, so an edit that removes a path can name
         *  another loop the Job already closed. */
        public String identity() {
            return kind == Kind.CHAIN_LOOP ? kind + "\u0000" + key : kind + "\u0000" + key + "\u0000" + subject;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", kind == Kind.FIELDS_NOT_DECLARED ? "INFO" : "WARNING");
            m.put("code", kind.code);
            m.put("key", key);
            m.put("message", message);
            m.put("fix", fix);
            return m;
        }
    }

    private JobSignalCheck() {}

    /** {@link #check(JobConfig, SignalTypeCatalog, List, Function)} against what {@code jobs} runs now. */
    public static List<Finding> check(JobService jobs, JobConfig draft) {
        return check(draft, jobs.signalTypeCatalog(), jobs.configSnapshot(),
                type -> jobs.jobType(type).map(JobTypeDescriptor::emits).orElse(List.of()));
    }

    /**
     * The findings on saving {@code draft}.
     *
     * @param others      the Jobs of the Space (a Job of the draft's name is replaced by the draft)
     * @param emitsOfType a Job Type id → the types it declares in {@code emits}
     */
    public static List<Finding> check(JobConfig draft, SignalTypeCatalog catalog, List<JobConfig> others,
                                      Function<String, List<String>> emitsOfType) {
        List<Finding> out = new ArrayList<>();
        if (!draft.hasSignal()) return out;
        String filter = draft.onSignal().trim();
        List<SignalTypeCatalog.Entry> matched = matching(catalog, filter);
        if (matched.isEmpty()) {
            out.add(new Finding(Kind.UNPOSTED_TYPE, "on_signal", filter,
                    "on_signal '" + filter + "' names no catalogued Signal Type: nothing posts it today, so this Job "
                            + "will not fire until a Job Pack or another Job posts it",
                    "pick a type from GET /signals/types, or keep it if a Job Pack you are about to load posts it"));
        } else {
            fieldFindings(draft, filter, matched, out);
        }
        chainFinding(draft, others, emitsOfType, out);
        return out;
    }

    /** The catalogued types {@code filter} ({@code on_signal}: a type or {@code prefix.*}) can fire on. */
    static List<SignalTypeCatalog.Entry> matching(SignalTypeCatalog catalog, String filter) {
        List<SignalTypeCatalog.Entry> out = new ArrayList<>();
        boolean prefix = filter.endsWith(".*");
        for (SignalTypeCatalog.Entry e : catalog.entries()) {
            SignalTypeDecl d = e.decl();
            boolean hit;
            if (!d.family()) hit = Signals.matchesType(d.type(), filter);
            else if (!prefix) hit = d.covers(filter.toLowerCase(Locale.ROOT));
            else {   // two families overlap when one prefix starts with the other; a prefix matches case-sensitively
                String a = filter.substring(0, filter.length() - 1), b = d.type().substring(0, d.type().length() - 1);
                hit = a.startsWith(b) || b.startsWith(a);
            }
            if (hit) out.add(e);
        }
        // an exact type the catalog lists itself carries that entry's fields only, as the catalog resolves it
        if (!prefix && out.stream().anyMatch(e -> !e.decl().family())) out.removeIf(e -> e.decl().family());
        return out;
    }

    private static void fieldFindings(JobConfig draft, String filter, List<SignalTypeCatalog.Entry> matched,
                                      List<Finding> out) {
        Map<String, Set<String>> refs = new LinkedHashMap<>();   // key → fields referenced there
        if (draft.hasWhen()) refs.put("when", fields(draft.when()));
        if (draft.bind() != null)
            draft.bind().forEach((k, v) -> { if (v != null) refs.put("bind." + k, fields(v)); });
        refs.values().removeIf(Set::isEmpty);
        if (refs.isEmpty()) return;
        for (SignalTypeCatalog.Entry e : matched) {
            if (e.fieldsDeclared()) continue;
            out.add(new Finding(Kind.FIELDS_NOT_DECLARED, "on_signal", filter,
                    "the payload fields of '" + e.decl().type() + "' (" + e.source() + ") are not declared, so the "
                            + "$signal fields of this Job cannot be checked",
                    "none needed; check the fields against what the poster writes"));
            return;
        }
        Set<String> declared = new TreeSet<>(SignalTypeCatalog.STAMPED);
        for (SignalTypeCatalog.Entry e : matched)
            for (var field : e.decl().fields()) declared.add(field.name());
        refs.forEach((key, fields) -> {
            for (String field : fields) {
                if (declared.contains(field)) continue;
                out.add(new Finding(Kind.UNDECLARED_FIELD, key, field,
                        key + " reads $signal." + field + ", which '" + filter + "' does not declare, so it is always "
                                + "empty when the Job fires",
                        "use one of the declared fields: " + String.join(", ", declared)));
            }
        });
    }

    /** The payload fields {@code text} reads through {@code $signal.<field>}. */
    static Set<String> fields(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = SIGNAL_REF.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** One loop through {@code draft}, if saving it closes one: draft → … → draft over unguarded on_signal links. */
    private static void chainFinding(JobConfig draft, List<JobConfig> others,
                                     Function<String, List<String>> emitsOfType, List<Finding> out) {
        if (!draft.enabled()) return;
        List<JobConfig> jobs = new ArrayList<>();
        for (JobConfig c : others) if (!c.name().equals(draft.name()) && c.enabled()) jobs.add(c);
        jobs.add(draft);
        List<String> path = new ArrayList<>(List.of(draft.name()));
        if (!loopBack(draft, draft, jobs, emitsOfType, path, new LinkedHashSet<>())) return;
        path.add(draft.name());
        out.add(new Finding(Kind.CHAIN_LOOP, "on_signal", String.join(",", new TreeSet<>(path)),
                "saving this Job closes a chain on itself: " + String.join(" → ", path) + " (each Run fires the next "
                        + "until the chain is cut at its maximum depth)",
                "give one Job of the chain a 'when' guard that stops it, or listen to a narrower type"));
    }

    private static boolean loopBack(JobConfig from, JobConfig draft, List<JobConfig> jobs,
                                    Function<String, List<String>> emitsOfType, List<String> path, Set<String> seen) {
        if (!seen.add(from.name())) return false;
        Set<String> posts = new LinkedHashSet<>(LIFECYCLE);
        posts.addAll(emitsOfType.apply(from.type()));
        for (JobConfig to : jobs) {
            if (to.name().equals(from.name()) || !to.hasSignal() || to.hasWhen()) continue;
            if (posts.stream().noneMatch(t -> Signals.matchesType(t, to.onSignal()))) continue;
            if (to.name().equals(draft.name())) return true;
            path.add(to.name());
            if (loopBack(to, draft, jobs, emitsOfType, path, seen)) return true;
            path.removeLast();
        }
        return false;
    }
}
