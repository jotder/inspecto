package com.gamma.config.spec;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The accepted config-key census — the one answer to <b>"does any component read this key?"</b>
 * (`DUCKLE-C3-DEAD-PROPERTY-1`). A key no component reads is a <em>silent loss</em>: the author writes
 * it, the save answers {@code written: true}, and the engine never looks at it. This class is what
 * turns that silence into a {@link Finding}.
 *
 * <p><b>The map is not invented here — it is the census that already existed.</b> Two authorities read
 * a pipeline file (pipeline spec §3): {@link ConfigSpecs#pipeline()} is what the product
 * <em>declares</em>, and {@code PipelineConfigParser} is what the engine <em>navigates</em>. Their
 * union is the set of keys something reads; their difference is {@link #PARSER_ONLY}, the ratchet
 * {@code PipelineKeyCoverageContractTest} has derived from source since 2026-08-31. That test now
 * ratchets THIS field rather than a copy of it, so the checker, the ratchet and the generated doc are
 * one list and drift is not representable. Current knowledge:
 * {@code docs/okf/backend/pipeline-graph/pipeline-config-keys.md}.
 *
 * <p>🔴 <b>The granularity is the BLOCK, and that is a correctness constraint, not a shortcut.</b>
 * Leaf drift inside a declared block is real and known — {@code dirs.errors}, {@code dirs.quarantine},
 * {@code dirs.markers}, {@code dirs.log_dir}, {@code collector.consignment.max_bytes}/{@code .order},
 * {@code parsing.source_timezone}, {@code parsing.delimited.*} are engine-read and spec-undeclared. A
 * leaf-granular checker would refuse configs that run correctly today. ⇒ <b>an accepted block is
 * accepted whole and never descended into.</b> Anything finer needs the leaf census to exist first.
 *
 * <p>⚠ <b>Four of the nine config types have a table: {@code pipeline}, {@code alert}, {@code meta}
 * and {@code enrichment}.</b> The other five ({@code job}, {@code schema}, {@code expectation},
 * {@code widget}, {@code dashboard}) have no parser census, so their accepted set is unknown and
 * {@link #unknownKeyFindings} returns nothing for them. That is a stated fail-open, not an omission:
 * deriving a set from {@code ConfigSpecs} alone would refuse the keys those parsers read, which is
 * exactly the mistake the block granularity above rules out.
 *
 * <p>⛔ <b>{@code job} is RULED OUT, and the reason is NOT "no job-type registry" — that sentence stood
 * here until 2026-09-17 and had the causality backwards.</b> The registry exists ({@code JobTypeRegistry}
 * + {@code JobTypeProvider}/{@code JobTypeDescriptor} in {@code inspecto-engine}, served by
 * {@code GET /jobs/types}), and its existence is what rules a census OUT. {@code JobConfig.fromMap}
 * funnels every non-frame key into an open {@code params} bag, so the accepted set is <b>per job type</b>,
 * and per-type is not statically enumerable four ways: the registry is <b>runtime-mutable</b> (Job Packs
 * register and deregister), <b>extensible by {@code ServiceLoader}</b> ({@code inspecto-ops} ships
 * {@code caserule.evaluate} and {@code objects.analytics} — and {@code spaces/demo} commits a config using
 * the latter), <b>config-derived</b> for {@code sql.template} (the {@code $name} tokens in the authored
 * SQL <i>are</i> its parameter contract), and <b>edition-varying</b> ({@code maintenance}'s task list is
 * derived from whatever the classpath contributed, so a static table would 422 valid Enterprise configs on
 * a Personal build). ⚠ Nor could a ratchet read it: this module depends only on {@code inspecto-api}, and
 * the registry is a per-{@code JobService} runtime object, not a compile-time table.
 * ⚠ Measured 2026-09-17: <b>34 of 34</b> committed job configs carry at least one non-frame key, across 34
 * distinct param keys — a frame-only census would refuse every one of them, and a top-level-only census
 * (the root map holds the single key {@code job:}) would catch nothing. Both available granularities are
 * wrong and there is no third. {@code JOB_PATH_KEYS} is hand-maintained for exactly this reason.
 *
 * <p>{@code schema} has no single parser at all (its blocks are navigated by a dozen classes across
 * {@code inspecto-etl} — {@code DataTransformer}, {@code Identifiers}, {@code PartitionDef},
 * {@code ParserSpec}, {@code SourceZones}, {@code TypeFlow}, … — so "the reads" are not enumerable
 * from one source); {@code expectation} is authored through {@code /expectations*} rather than
 * {@code /config/write} and its persisted content carries {@code lastResult}/{@code createdAt}/
 * {@code updatedAt} bookkeeping no spec declares, the same shape that struck {@code widget} and
 * {@code dashboard}. {@code alert}, {@code meta} and {@code enrichment} are censusable precisely
 * because that objection can be ANSWERED for them — see {@link #ALERT_PARSER_ONLY},
 * {@link #ENRICHMENT_PARSER_ONLY} and {@link #acceptedBlocks}.
 *
 * <p>⚠ <b>{@code widget} and {@code dashboard} are a different shape of fail-open and censusing them
 * here would be a no-op.</b> Neither is ever written through {@code /config/write}: the UI saves both
 * through the component-store routes ({@code POST|PUT /components/{kind}}, see
 * {@code ComponentsService}), which never call this class. A gate for them belongs in
 * {@code ComponentRoutes}, and it is NOT a table away: the persisted body also carries {@code name},
 * {@code owner} and {@code shares}, which no {@code ConfigSpec} declares, so a spec-derived refusal
 * would reject essentially every real save.
 *
 * <p>⚠ A block named {@code x-…} is the author's declared "this is mine, not the engine's" marker: it
 * is accepted unconditionally, never suggested against, and round-trips through {@code ConfigCodec}
 * untouched.
 */
@PublicApi(since = "5.2.0")
public final class AcceptedConfigKeys {

    private AcceptedConfigKeys() {}

    /** The prefix that marks a block as deliberately extra-engine — accepted, never suggested against. */
    public static final String EXTENSION_PREFIX = "x-";

    /**
     * The blocks whose SECOND level is censused too, so the checker may descend one level into them.
     *
     * <p>🔴 Exactly the scopes {@code PipelineKeyCoverageContractTest} scans — it reads the parser's
     * root local ({@code raw}) and its {@code processing} local ({@code proc}), and nothing else. That
     * is why the list is {@code processing} alone and why it is a list rather than "every block with a
     * declared sub-block":
     *
     * <ul>
     *   <li>{@code dirs} HAS declared leaves ({@code poll}, {@code database}, {@code backup},
     *       {@code temp}, {@code status_dir}) and ALSO engine-read undeclared ones ({@code errors},
     *       {@code quarantine}, {@code markers}, {@code log_dir}). Descending on "has a declared
     *       sub-block" would refuse four keys the engine reads today.</li>
     *   <li>{@code collector} is the same shape and worse: only a handful of leaves are declared
     *       ({@code consignment.max_files}, and since 2026-09-23 {@code fetch.rate_limit} and the
     *       {@code retry.*} / {@code circuit_breaker.*} leaves), while the block legitimately carries
     *       every connector's own keys — the ones
     *       {@code RecipeConverter} round-trips through {@code collect:}.</li>
     * </ul>
     *
     * ⚠ Adding an entry here is only sound once that block's leaves are censused the way
     * {@code processing.*} is. Until then, accepted-whole is the honest answer.
     */
    private static Set<String> censusedParents(String type) {
        return switch (type == null ? "" : type) {
            case "pipeline" -> Set.of("processing");
            // The whole alert file is ONE block — `alert:` — so a census that stopped at the top level
            // would accept every alert config whole and catch nothing. `alert` is censused because its
            // leaves ARE enumerable: `AlertRule.fromMap` reads a fixed, literal list of them.
            case "alert" -> Set.of("alert");
            // `EnrichmentConfig.fromMap` reads these three blocks through plain locals (`in`, `out`,
            // `tr`) with a literal key each and no dynamic access, and every leaf it reads is
            // spec-declared — so descending one level is sound and catches the keys that matter
            // (`input.*`, `output.*`, `triggers.*` are where an enrichment's real settings live).
            // ⛔ `references` is deliberately NOT here: `fromMap` iterates its `entrySet()` over
            // AUTHOR-CHOSEN view names, so descending would refuse every reference anyone names —
            // the same shape that keeps `meta` off this list.
            case "enrichment" -> Set.of("input", "output", "triggers");
            // ⛔ `meta` is deliberately NOT here. `SemanticModel.load` reads its five top-level keys
            // literally, but ONE LEVEL DOWN inside `tables`, `kpis` and `reports` it iterates
            // `entrySet()` over AUTHOR-CHOSEN names (a table ref, a KPI name, a report name). Those are
            // an unbounded namespace, so descending would refuse every KPI an author ever names.
            default -> Set.of();
        };
    }

    /**
     * The blocks {@code PipelineConfigParser} reads that {@link ConfigSpecs#pipeline()} does not
     * declare. ⚠ <b>This list may only ever SHRINK</b> — it is the remaining pipeline-spec gap-10 debt,
     * and {@code PipelineKeyCoverageContractTest} fails when a block joins it silently or stays on it
     * after being declared.
     *
     * <p>18 when the ratchet landed (2026-08-31); 17 after gap 8 declared {@code output_store} the same
     * day; 16 after CONSIGNMENT-HOME-1 declared {@code collector.consignment.max_files} (2026-09-02).
     *
     * <p>🔴 {@code collector} is NOT on this list (it is declared, via a few leaves) and that is what makes
     * the {@code collect:} round trip safe: {@code RecipeConverter} round-trips arbitrary collector-block
     * keys through {@code collect:}, and because the block is accepted WHOLE, none of them is flagged.
     * {@code RecipeCompiler}'s refusal to blanket-reject unknown {@code collect:} keys therefore stands
     * unchanged — see {@code RecipeCompiler:254-257}.
     */
    public static final Set<String> PARSER_ONLY = Set.of(
            // ── top-level ────────────────────────────────────────────────────────
            "active",              // the arming switch itself — authored on every runnable pipeline
            "route",               // gap 9's block — the branch-aware ingest lane
            "sinks",               // the plural destination block
            "steps",               // the ordered Stage-2 chain (gap 11)
            "template",            // template: true ⇒ never registered, so never runnable
            "trigger",             // schedule / on:dataset
            // ── processing.* ─────────────────────────────────────────────────────
            "processing.dedup",
            "processing.disabled_steps",
            "processing.duplicate_check",
            "processing.ingester_config",
            "processing.join",
            "processing.map",
            "processing.mapping_file",
            "processing.schemas",
            "processing.segments",
            "processing.summarize");

    /**
     * The {@code alert.*} leaves {@code AlertRule.fromMap} reads that {@link ConfigSpecs#alert()} does
     * not declare — the alert type's counterpart to {@link #PARSER_ONLY}.
     *
     * <p>🔴 <b>Why alert can be censused when the other seven types cannot.</b> The fail-open on this
     * class is not squeamishness: deriving an accepted set from {@code ConfigSpecs} ALONE would refuse
     * the keys a hand-written parser reads but the spec never declared. That objection is answerable
     * exactly where the parser's reads are ENUMERABLE, and {@code AlertRule.fromMap}
     * ({@code AlertRule.java:115-128}) is a flat, literal list of ten {@code alert.get("…")} calls with
     * no dynamic key access at all. Seven of the ten are declared by the spec; these three are not, and
     * they are all live — {@code dataset}/{@code measure} are the BI-5 measure-rule shape and
     * {@code when} scopes ledger rows (both enforced in the compact constructor at
     * {@code AlertRule.java:91-99}). ⚠ This list may only ever SHRINK, for the same reason
     * {@link #PARSER_ONLY} may: a block leaving it means the spec now declares it.
     *
     * <p>⚠ The FLAT {@code alert-rule} component shape is a different config type written through
     * {@code AlertRoutes}, not {@code /config/write}, so it never reaches this census.
     */
    public static final Set<String> ALERT_PARSER_ONLY = Set.of(
            "alert.dataset",   // BI-5 measure rule: the Dataset the Measure is read from
            "alert.measure",   // BI-5 measure rule: count or agg(field)
            "alert.when");     // the ledger-row scope filter

    /**
     * The top-level blocks {@code EnrichmentConfig} reads that {@link ConfigSpecs#enrichment()} does
     * not declare — the enrichment type's counterpart to {@link #PARSER_ONLY}.
     *
     * <p>🔴 <b>Why enrichment can be censused.</b> The soundness condition is that the parser's reads
     * be ENUMERABLE at the granularity the checker uses. {@code EnrichmentConfig.load}/{@code fromMap}
     * ({@code EnrichmentConfig.java:143-236}) reach the root map through exactly seven literal reads —
     * {@code name}, {@code transform}, {@code transform_file}, {@code references}, {@code triggers}
     * and {@code ToonHelper.requireSection(raw, "input"|"output")} — with no {@code keySet}/
     * {@code entrySet}/{@code forEach} over the root at all. Six are spec-declared; {@code references}
     * is not, and it is live (it registers each join/lookup view). Seven reads, seven accounted for.
     * ⚠ Every OTHER component that reads a {@code *_enrich.toon} map reads a subset of the same
     * declared blocks: {@code PipelineGraphRoutes:349-352} ({@code triggers.on_pipeline},
     * {@code name}), {@code PipelineBundleRoutes:539-560} + {@code :608-612} ({@code name},
     * {@code triggers}, {@code input.database}, {@code output.database}),
     * {@code PipelineRenameRoutes:505-515} ({@code triggers.on_pipeline}).
     *
     * <p>⚠ This list may only ever SHRINK, for the same reason {@link #PARSER_ONLY} may: a block
     * leaving it means the spec now declares it.
     */
    public static final Set<String> ENRICHMENT_PARSER_ONLY = Set.of(
            "references");   // name → {path|ref, format, as_of}: the join/lookup views registered by name

    /**
     * The blocks {@code spec} declares: a leaf {@code a.b.c} declares {@code a} and {@code a.b}.
     * (Lifted from {@code PipelineKeyCoverageContractTest}, which now calls this rather than holding a
     * second copy of the derivation.)
     */
    public static Set<String> declaredBlocks(ConfigSpec spec) {
        Set<String> blocks = new TreeSet<>();
        if (spec == null) return blocks;
        for (FieldSpec f : spec.fields()) {
            String[] parts = f.path().split("\\.");
            blocks.add(parts[0]);
            if (parts.length > 1) blocks.add(parts[0] + "." + parts[1]);
        }
        return blocks;
    }

    /**
     * Every block some component reads for {@code type}, or an EMPTY set when this config type has no
     * census (which {@link #unknownKeyFindings} reads as "check nothing", never as "accept nothing").
     */
    public static Set<String> acceptedBlocks(String type) {
        Set<String> all = new TreeSet<>();
        switch (type == null ? "" : type) {
            case "pipeline" -> {
                all.addAll(declaredBlocks(ConfigSpecs.pipeline()));
                all.addAll(PARSER_ONLY);
            }
            case "alert" -> {
                all.addAll(declaredBlocks(ConfigSpecs.alert()));
                all.addAll(ALERT_PARSER_ONLY);
            }
            // 🔴 `meta` has NO parser-only list, and that is a result, not an omission. The one reader
            // of a `*_meta.toon` is `SemanticModel.load` (the only caller is `ServiceBootstrap:154`),
            // and every top-level key it reads — name, tables, kpis, reports, domain — is already
            // declared by the spec. Five reads, five accounted for, so the declared set alone cannot
            // refuse a key the engine honours. `MetaKeyCoverageContractTest` ratchets that from source.
            case "meta" -> all.addAll(declaredBlocks(ConfigSpecs.meta()));
            case "enrichment" -> {
                all.addAll(declaredBlocks(ConfigSpecs.enrichment()));
                all.addAll(ENRICHMENT_PARSER_ONLY);
            }
            default -> { /* no parser census ⇒ nothing is KNOWN to be dead; see the class doc. */ }
        }
        return all;
    }

    /** Whether {@code type} has an accepted-names census at all. */
    public static boolean hasCensus(String type) {
        return !acceptedBlocks(type).isEmpty();
    }

    /**
     * One finding per block of {@code raw} that no component reads, at {@code severity} — ERROR at an
     * authoring gate (the save is the last moment the author is present), WARNING wherever a config
     * that is already on disk is merely being read.
     *
     * <p>Returns empty for a config type with no census. Blocks are visited top-level first, then one
     * level down inside each accepted block that itself has declared sub-blocks — never deeper, per the
     * block-granularity rule on this class.
     */
    public static List<Finding> unknownKeyFindings(String type, Map<String, Object> raw, Severity severity) {
        Set<String> accepted = acceptedBlocks(type);
        if (accepted.isEmpty() || raw == null || raw.isEmpty()) return List.of();

        List<Finding> findings = new ArrayList<>();
        for (String key : orderedKeys(raw)) {
            if (isExtension(key)) continue;
            if (!accepted.contains(key)) {
                findings.add(finding(key, key, accepted, severity));
                continue;
            }
            // One level down, and ONLY inside a block whose second level is itself censused.
            if (!censusedParents(type).contains(key) || !(raw.get(key) instanceof Map<?, ?> nested)) continue;
            Set<String> subs = subBlocksOf(accepted, key);
            if (subs.isEmpty()) continue;
            for (String sub : orderedKeys(nested)) {
                if (isExtension(sub)) continue;
                String path = key + "." + sub;
                if (!accepted.contains(path)) findings.add(finding(path, sub, subs, severity));
            }
        }
        return findings;
    }

    /** Whether {@code key} is an author-owned extension block, accepted unconditionally. */
    public static boolean isExtension(String key) {
        return key != null && key.startsWith(EXTENSION_PREFIX);
    }

    /**
     * The nearest accepted name to {@code name} among {@code candidates}, or empty when nothing is
     * close enough to be worth guessing.
     *
     * <p>⚠ The "nothing is close" answer is a first-class result, not a degenerate one: a message that
     * volunteers an unrelated name for a typo of something else sends the author to the wrong key. The
     * threshold scales with the name's length so that a short key cannot match half the table, and ties
     * break on distance then lexicographically so the message is deterministic.
     */
    public static java.util.Optional<String> nearestName(String name, Set<String> candidates) {
        if (name == null || name.isBlank() || candidates == null) return java.util.Optional.empty();
        int budget = Math.max(1, Math.min(3, name.length() / 3));
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : new TreeSet<>(candidates)) {
            if (isExtension(candidate) || candidate.equals(name)) continue;
            int d = distance(name, candidate);
            if (d <= budget && d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    // ── internals ───────────────────────────────────────────────────────────────

    /** The message says what is WRONG; the guidance says what to DO — split, per the R1 contract. */
    private static Finding finding(String path, String leaf, Set<String> siblings, Severity severity) {
        // Suggest only names the author could actually write HERE: a top-level typo must not be told to
        // try a `processing.*` block, which would not be a legal key at that position.
        Set<String> sameDepth = new LinkedHashSet<>();
        int depth = path.split("\\.").length;
        for (String candidate : siblings)
            if (candidate.split("\\.").length == depth) sameDepth.add(lastSegment(candidate));

        java.util.Optional<String> near = nearestName(leaf, sameDepth);
        String message = "'" + path + "' is read by no component — the engine ignores it, so anything "
                + "configured here is silently lost";
        String guidance = near
                .map(n -> "did you mean '" + (depth == 1 ? n : path.substring(0, path.lastIndexOf('.') + 1) + n)
                        + "'? Remove the key, or prefix it '" + EXTENSION_PREFIX
                        + "' to keep it as an author-owned annotation.")
                .orElse("no accepted key is close to this name. Remove it, or prefix it '"
                        + EXTENSION_PREFIX + "' to keep it as an author-owned annotation.");
        String code = severity == Severity.ERROR
                ? FindingCodes.ERR_UNKNOWN_CONFIG_KEY : FindingCodes.WARN_UNKNOWN_CONFIG_KEY;
        return new Finding(severity, path, message, code, guidance);
    }

    private static Set<String> subBlocksOf(Set<String> accepted, String parent) {
        Set<String> subs = new TreeSet<>();
        String prefix = parent + ".";
        for (String a : accepted) if (a.startsWith(prefix) && a.indexOf('.', prefix.length()) < 0) subs.add(a);
        return subs;
    }

    private static String lastSegment(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private static List<String> orderedKeys(Map<?, ?> map) {
        List<String> keys = new ArrayList<>();
        for (Object k : map.keySet()) if (k != null) keys.add(String.valueOf(k));
        return keys;
    }

    /** Plain Levenshtein, two rows — the smallest thing that answers "is this a typo of that?". */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }
}
