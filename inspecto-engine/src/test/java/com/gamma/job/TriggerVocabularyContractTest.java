package com.gamma.job;

import com.gamma.pipeline.PipelineTrigger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last of Sprint 3's five failure classes: <b>a served descriptor drifts from its own
 * dispatch</b>. The maintenance descriptor was the first instance (closed in Sprint 1); this is the
 * second — the <b>trigger vocabulary in the binding glossary</b>.
 *
 * <p>🔴 <b>What the grounding found, and it is worse than "the glossary might be stale".</b>
 * {@link ExpressionDecl.TriggerKind} is the vocabulary the server PUBLISHES: every parameter token
 * carries an {@code availableIn} set, serialised lowercase, and the Angular client's own comment
 * documents it as <i>"Trigger kinds it is meaningful on, lowercase: cron | on_pipeline | on_signal |
 * manual"</i>. But that enum is referenced <b>nowhere else in the engine</b> except one
 * {@code ON_SIGNAL} import in {@link BuiltinExpressions}. The real dispatch never mentions it: a job's
 * trigger is decided by config predicates — {@code hasCron()}, {@code hasSignal()},
 * {@code onPipeline()} — and by the literal string {@code "manual"} at the run seam. So the enum is a
 * <b>parallel, hand-maintained list that only decorates the descriptor</b>. Add a fifth job trigger to
 * {@code JobConfig} and the served vocabulary can never mention it, no client can offer a token for
 * it, and <b>nothing fails</b>. That is the class, exactly.
 *
 * <p><b>What these tests derive rather than restate.</b> The dispatch side comes from
 * {@link JobConfig}'s record components by reflection; the published side from the enum; the
 * documented side by <b>parsing {@code docs/GLOSSARY.md}</b> — the same technique that closed the
 * hand-mirrored-map class, because a glossary that is binding and hand-typed is a mirror like any
 * other. ⛔ No list of trigger names is written down in this file. If one were, it would be a third
 * copy and this test would be part of the problem it exists to catch.
 *
 * <p><b>Mutation-proven 2026-09-09, and the first two attempts proved nothing.</b> Removing
 * {@code ON_SIGNAL} from the enum and adding a component to {@link JobConfig} both fail to
 * <b>compile</b> — {@code BuiltinExpressions} imports that constant, and a record's canonical
 * constructor breaks every call site — so the compiler caught them and these tests were never
 * exercised. ⚠ <b>A mutation that does not compile proves nothing about a test.</b> The four that do
 * compile, each failing exactly the intended tests:
 * <ul>
 *   <li><b>descriptor ahead of dispatch</b> — ADD {@code ON_CLOCK} to the enum: fails
 *       {@code everyPublishedJobTriggerKindIsSelectableByAConfigKey} <i>and</i> the job-trigger
 *       glossary pin (2 of 6);</li>
 *   <li><b>dispatch ahead of descriptor</b> — swap {@code onSignal} and {@code when}, which compiles
 *       because both are {@code String}: fails the classification pin (1 of 6);</li>
 *   <li><b>the binding glossary invents a kind</b> — add {@code `on_whim`} to the entry: fails the
 *       job-trigger glossary pin (1 of 6);</li>
 *   <li><b>the serialisation contract breaks</b> — publish {@code t.name()} instead of lowercase:
 *       fails {@code theServedDescriptorPublishesTheEnumNamesLowercased} (1 of 6).</li>
 * </ul>
 * Restored, 6 of 6 green. Each failure message names the side that drifted.
 */
class TriggerVocabularyContractTest {

    /** The glossary entry that is binding for both vocabularies. */
    private static final String GLOSSARY = "docs/GLOSSARY.md";

    // ── the dispatch side: JobConfig's own components ────────────────────────────────────────────

    /**
     * Every published job-trigger kind except {@code MANUAL} must correspond to a {@link JobConfig}
     * record component that selects it. This is the enum → dispatch direction: a kind the server
     * advertises but no config key can request is a lie told to every client.
     */
    @Test
    void everyPublishedJobTriggerKindIsSelectableByAConfigKey() {
        Set<String> components = Arrays.stream(JobConfig.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toCollection(TreeSet::new));

        for (ExpressionDecl.TriggerKind kind : ExpressionDecl.TriggerKind.values()) {
            if (kind == ExpressionDecl.TriggerKind.MANUAL) continue;   // see the next test
            String expected = camel(kind.name());
            assertTrue(components.contains(expected),
                    "TriggerKind." + kind + " is published in every parameter descriptor's `availableIn`, "
                            + "but JobConfig has no `" + expected + "` component to request it. Either the "
                            + "enum gained a kind the dispatch cannot select, or the component was renamed. "
                            + "JobConfig components: " + components);
        }
    }

    /**
     * {@code MANUAL} is deliberately the odd one out: it has <b>no config key</b>, because a manual job
     * is one with none of the other triggers set, run through the explicit run seam. Pinning that keeps
     * the previous test's exemption honest — if someone ever adds a `manual:` key, this fails and the
     * exemption above has to be revisited rather than silently covering a real component.
     */
    @Test
    void manualIsTheAbsenceOfATriggerAndHasNoConfigKeyOfItsOwn() {
        Set<String> components = Arrays.stream(JobConfig.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        assertFalse(components.contains("manual"),
                "MANUAL is exempted from the correspondence test precisely because it is the ABSENCE of a "
                        + "trigger. A `manual` component would make that exemption wrong.");
    }

    /**
     * The dispatch → enum direction, which is the one that actually bites. It cannot be derived (there
     * is no marker on a component saying "I am a trigger"), so it is pinned: a new {@link JobConfig}
     * component fails here and forces whoever added it to answer "is this a trigger?" — and, if it is,
     * to add it to the enum so the descriptor can publish it.
     *
     * <p>⚠ This is a ratchet, not a guess. Update the expected set in the same change that adds the
     * component, deliberately.
     */
    @Test
    void aNewJobConfigComponentMustBeClassifiedAsATriggerOrNot() {
        List<String> actual = Arrays.stream(JobConfig.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertEquals(List.of("name", "type", "cron", "onPipeline", "enabled", "catchUp", "params",
                        "onSignal", "when", "args", "bind"), actual,
                "JobConfig's components changed. If the new one SELECTS a trigger, add the matching "
                        + "ExpressionDecl.TriggerKind so the served `availableIn` can publish it, and update "
                        + "docs/GLOSSARY.md's Trigger entry. If it does not, just update this list. Do not "
                        + "skip the question: an unpublished trigger is invisible to every client.");
    }

    // ── the documented side: the binding glossary, PARSED ────────────────────────────────────────

    @Test
    void theBindingGlossaryListsExactlyThePublishedJobTriggerVocabulary() throws IOException {
        Set<String> documented = glossaryList("**Job triggers:**", "**Pipeline triggers**");
        Set<String> published = Arrays.stream(ExpressionDecl.TriggerKind.values())
                .map(k -> k.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(published, new TreeSet<>(documented),
                "docs/GLOSSARY.md's Trigger entry is BINDING and disagrees with what the server publishes "
                        + "in `availableIn`. The enum is the source; fix the glossary, or fix the enum if the "
                        + "vocabulary really changed.");
    }

    @Test
    void theBindingGlossaryListsExactlyThePipelineTriggerKinds() throws IOException {
        Set<String> documented = glossaryList("**Pipeline triggers**", null);
        Set<String> actual = Arrays.stream(PipelineTrigger.Kind.values())
                .map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(actual, new TreeSet<>(documented),
                "docs/GLOSSARY.md's Trigger entry disagrees with PipelineTrigger.Kind. ⚠ These are the "
                        + "SECOND of two trigger models — the glossary once listed four values for what are "
                        + "really two separate vocabularies, which is how they drifted in the first place.");
    }

    /**
     * The serialisation the client's contract comment depends on: {@code availableIn} is the enum
     * names, <b>lowercased</b>. A change to {@code toMap} that emitted the raw names would break every
     * client's comparison silently, because both sides are strings.
     */
    @Test
    void theServedDescriptorPublishesTheEnumNamesLowercased() {
        ExpressionDecl decl = new ExpressionDecl("$probe", ExpressionDecl.Form.LITERAL,
                ParamType.STRING, "probe", "probe", ExpressionDecl.ANY_TRIGGER, true);
        // toMap takes the preview the registry supplies — it is the only thing allowed to evaluate.
        Object published = decl.toMap("probe").get("availableIn");
        assertTrue(published instanceof List<?>, "availableIn must serialise as a list, got " + published);
        Set<String> got = new TreeSet<>(((List<?>) published).stream().map(String::valueOf).toList());
        Set<String> want = Arrays.stream(ExpressionDecl.TriggerKind.values())
                .map(k -> k.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(want, got, "ANY_TRIGGER must publish every kind, lowercased — the UI compares strings");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Pull one pipe-separated, backticked vocabulary out of the glossary's Trigger entry.
     *
     * <p>⚠ Parenthesised asides are stripped FIRST and the run is cut at its first comma, which is what
     * makes this exact without an allowlist: it is how {@code (+ a `when` guard)},
     * {@code (`on: commit` or `on: dataset`)}, {@code (`PipelineTrigger.Kind`)} and the trailing
     * {@code , with `catch_up`} fall out. Those are guards and parameters, not trigger kinds, and a
     * guard that had to name them one by one would be carrying the third copy this test refuses to keep.
     */
    private static Set<String> glossaryList(String from, String to) throws IOException {
        String text = Files.readString(repoFile(GLOSSARY));
        int start = text.indexOf(from);
        assertTrue(start >= 0, "cannot find \"" + from + "\" in " + GLOSSARY
                + " — the Trigger entry was restructured; re-anchor this parse rather than deleting it");
        start += from.length();
        int end = to == null ? text.length() : text.indexOf(to, start);
        assertTrue(end > start, "cannot find \"" + to + "\" after \"" + from + "\" in " + GLOSSARY);

        String segment = text.substring(start, end)
                .replaceAll("\\([^)]*\\)", " ");     // drop asides: guards, `on:` values, the type name
        int comma = segment.indexOf(',');
        if (comma >= 0) segment = segment.substring(0, comma);   // drop ", with `catch_up`"

        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("`([A-Za-z_][A-Za-z0-9_]*)`").matcher(segment);
        while (m.find()) out.add(m.group(1));
        assertFalse(out.isEmpty(), "parsed an EMPTY vocabulary from " + GLOSSARY + " after \"" + from
                + "\" — an empty parse must fail, never pass over nothing");
        return out;
    }

    /** ON_PIPELINE -> onPipeline. */
    private static String camel(String screamingSnake) {
        String[] parts = screamingSnake.toLowerCase(Locale.ROOT).split("_");
        StringBuilder b = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            b.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return b.toString();
    }

    /** Walk up from the module's CWD to the repo root, so the path works under surefire and an IDE alike. */
    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
