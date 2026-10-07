package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `ASSURE-MAKER-CHECKER-1` S0 + S2 — <b>every config-writing control-plane route passes through the funnels,
 * or is on a justified exemption list.</b> Maker-checker is only as strong as its narrowest door: a route that writes
 * config beside the funnels is a route an approval policy cannot hold.
 *
 * <p><b>The enumeration</b> is the one {@code CapabilityManifestTest#everyMutatingRouteIsGatedExemptOrPending}
 * trusts: every {@code api.post|put|patch|delete("pattern", …)} registration site in every reactor module's
 * {@code src/main/java}. For each site the scan takes the handler expression, then the transitive closure of
 * the methods it calls that are declared in the SAME source file (a route's work lives in its own module
 * class), and reads that closure for:
 * <ul>
 *   <li>a <b>config write</b> — a TOON encode ({@code ConfigCodec.toToon(}, {@code JToon.encode(}) or a
 *       {@code ComponentStore} write or delete ({@code store.write|delete(TYPE|type|kind|"…", …)});</li>
 *   <li>the <b>content funnel</b> — {@link SaveGate} ({@code SaveGate.check}/{@code SaveGate.introduced}), the
 *       gate every pipeline-shaped save runs ({@code ComponentStore.write} is the component writers' funnel by
 *       construction);</li>
 *   <li>the <b>maker-checker hold</b> — {@code PendingChanges.hold} (or {@code holdRefusing} for a writer that
 *       cannot be one Pending Change), which an approval policy needs every authoring write to reach.</li>
 * </ul>
 * A TOON-writing route must reach {@code SaveGate} or sit on {@link #NO_SAVE_GATE}; every config-writing route
 * must reach the hold or sit on {@link #NO_HOLD} — each row with its reason.
 *
 * <p>⚠ <b>Scope, stated so it is not mistaken for more:</b> the closure stops at the file boundary, so a route
 * that delegates its write to ANOTHER class is invisible here (the settings documents, whose records write
 * themselves, are the known case — they are Space preferences, not config the engine runs). The guard's
 * promise is narrower and mechanical: a new route that writes config IN ITS OWN MODULE without the funnels
 * goes red.
 */
class ConfigWriteFunnelTest {

    /** method( "pattern", — the MUTATING shape CapabilityManifestTest counts. */
    private static final Pattern SITE = Pattern.compile("api\\.(post|put|patch|delete)\\(\\s*\"([^\"]+)\"\\s*,");

    private static final Pattern TOON_WRITE = Pattern.compile("ConfigCodec\\.toToon\\(|JToon\\.encode\\(");
    private static final Pattern COMPONENT_WRITE = Pattern.compile(
            "\\.(write|delete)\\(\\s*(TYPE|type|kind|KIND|[A-Z_]+_TYPE|\"[a-z-]+\")\\s*,");
    private static final Pattern SAVE_GATE = Pattern.compile("SaveGate\\.(check|introduced)\\(");
    private static final Pattern HOLD = Pattern.compile("PendingChanges\\.hold\\w*\\(");

    private static final String TAGS = "the Tag catalog / Tag Rules of the operational-object layer "
            + "(inspecto-ops) — ConfigSpecs has no tag type";
    private static final String CONNECTIONS = "Connection CRUD is secret-aware (masking, secret references) — "
            + "ConfigSpecs has no connection type and ComponentStore excludes it for the same reason";
    private static final String EGRESS = "the Space egress allowlist (egress.toon, ASSURE-ACTION-REQUESTS-1): a Space "
            + "setting validated by EgressPolicy (canAdminister) — SaveGate has no arm for it and ApprovalPolicy.GOVERNABLE "
            + "excludes Space settings, as it excludes approval.toon";
    private static final String PUBLICATION_DESTINATIONS = "the Space publication destination allowlist "
            + "(publication-destinations.toon, ASSURE-BI-PUBLICATION-1): a Space setting validated by "
            + "PublicationDestinations (canAdminister) — like egress.toon, SaveGate has no arm for it and "
            + "ApprovalPolicy.GOVERNABLE excludes Space settings";
    private static final String MAIL_ATTACH = "the Space mail attachment domain allowlist (mail-attachments.toon, "
            + "ASSURE-XLSX-ATTACHMENTS-1): a Space setting validated by MailAttachDomains (canAdminister) — the egress "
            + "allowlist's shape and reason";
    private static final String APPROVERS = "the Space approver roster (approvers.toon, operator 2026-10-04): a Space "
            + "setting validated by ApproverRosterRoutes (canAdminister) — the egress allowlist's shape and reason";
    private static final String JOBS = "JobRoutes runs its own job gate (the job spec + ConfigSafetyValidator.checkJob, "
            + "the two checks SaveGate's job arm runs) rather than the whole SaveGate list";

    /**
     * TOON-writing routes that deliberately do NOT run {@link SaveGate}, each with its reason — they write a
     * kind SaveGate has no arm for. Keyed {@code "METHOD pattern"}. A stale row (the route no longer writes TOON,
     * or no longer exists) fails too.
     */
    static final Map<String, String> NO_SAVE_GATE = new TreeMap<>(Map.ofEntries(
            Map.entry("POST /tags", TAGS), Map.entry("POST /tags/([^/]+)/rename", TAGS),
            Map.entry("POST /tags/rules", TAGS),
            Map.entry("POST /cases/rules", "a Case Rule of the operational-object layer (inspecto-ops) — "
                    + "ConfigSpecs has no case-rule type"),
            Map.entry("POST /connections", CONNECTIONS), Map.entry("PUT /connections/([^/]+)", CONNECTIONS),
            Map.entry("POST /jobs", JOBS), Map.entry("PUT /jobs/([^/]+)", JOBS),
            Map.entry("POST /jobs/([^/]+)/enable", JOBS), Map.entry("POST /jobs/([^/]+)/disable", JOBS),
            Map.entry("POST /jobs/([^/]+)/reschedule", JOBS),
            Map.entry("PUT /settings/egress", EGRESS),
            Map.entry("PUT /settings/approvers", APPROVERS),
            Map.entry("PUT /settings/publication-destinations", PUBLICATION_DESTINATIONS),
            Map.entry("PUT /settings/mail-attachments", MAIL_ATTACH)
    ));

    private static final String NOT_GOVERNABLE = "writes a kind ApprovalPolicy.GOVERNABLE excludes, so no policy can "
            + "name it and there is nothing to hold";
    private static final String RESULT_STAMP = "a RESULT stamp an evaluation writes onto the component "
            + "(archive=false, outside the version history) — operating, not authoring; the Pending Change version "
            + "ignores it (PendingChanges.version)";

    /**
     * Config-writing routes that deliberately do NOT reach {@code PendingChanges.hold}, each with its reason.
     * A stale row fails too.
     */
    static final Map<String, String> NO_HOLD = new TreeMap<>(Map.ofEntries(
            Map.entry("POST /tags", TAGS), Map.entry("POST /tags/([^/]+)/rename", TAGS),
            Map.entry("POST /tags/rules", TAGS),
            Map.entry("POST /cases/rules", "a Case Rule of the operational-object layer — " + NOT_GOVERNABLE),
            Map.entry("POST /connections", CONNECTIONS), Map.entry("PUT /connections/([^/]+)", CONNECTIONS),
            Map.entry("POST /requirements", "requirement: " + NOT_GOVERNABLE),
            Map.entry("POST /requirements/([^/]+)/decision", "requirement: " + NOT_GOVERNABLE),
            Map.entry("POST /requirements/([^/]+)/deliver", "requirement: " + NOT_GOVERNABLE),
            Map.entry("POST /decision-rules/([^/]+)/simulate", RESULT_STAMP),
            Map.entry("POST /expectations/evaluate", RESULT_STAMP),
            Map.entry("POST /expectations/([^/]+)/evaluate", RESULT_STAMP),
            Map.entry("PUT /settings/egress", EGRESS),
            Map.entry("PUT /settings/approvers", APPROVERS),
            Map.entry("PUT /settings/publication-destinations", PUBLICATION_DESTINATIONS),
            Map.entry("PUT /settings/mail-attachments", MAIL_ATTACH),
            Map.entry("POST /pipelines/rename/resume",
                    "finishes a rename that was already let through (held and approved, or ungoverned) — holding "
                            + "the recovery would strand a half-moved identity")
    ));

    record Verdict(String route, boolean toon, boolean component, boolean saveGate, boolean hold, Path file,
                   boolean holdsItself, String closure) {}

    /** A real hold — not {@code holdRefusing*}, which refuses instead of recording a replayable request. */
    private static final Pattern REAL_HOLD = Pattern.compile("PendingChanges\\.hold\\(");

    /**
     * Re-verification finding 2 (i): the production replay allowlist ({@link PendingChanges#REPLAYABLE}) is EXACTLY
     * the set of routes whose handler reaches a real {@code PendingChanges.hold} — a route added to one without the
     * other goes red here, so approve can never replay a route that does not hold before it writes.
     */
    @Test
    void theReplayAllowlistIsExactlyTheRoutesThatHold() throws IOException {
        Set<String> holding = new java.util.TreeSet<>();
        for (Verdict v : scan()) if (v.holdsItself()) holding.add(v.route());
        assertTrue(holding.size() > 10, "the scan found almost no holding routes — scan broken? " + holding);
        assertTrue(holding.equals(new java.util.TreeSet<>(PendingChanges.REPLAYABLE)), () ->
                "PendingChanges.REPLAYABLE has drifted from the routes that hold.\nholding but not replayable: "
                        + minus(holding, PendingChanges.REPLAYABLE) + "\nreplayable but not holding: "
                        + minus(new java.util.TreeSet<>(PendingChanges.REPLAYABLE), holding));
    }

    private static Set<String> minus(Set<String> a, java.util.Collection<String> b) {
        Set<String> out = new java.util.TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    @Test
    void everyConfigWritingRoutePassesThroughTheFunnelsOrIsExempt() throws IOException {
        List<Verdict> verdicts = scan();
        assertFalse(verdicts.isEmpty(), "no mutating registration sites found — scan broken?");
        assertTrue(verdicts.stream().anyMatch(Verdict::toon) && verdicts.stream().anyMatch(Verdict::component),
                "the scan recognised no config write at all — the write signals have drifted from the code");

        Set<String> bypassing = new LinkedHashSet<>();
        Set<String> toonWriters = new LinkedHashSet<>();
        for (Verdict v : verdicts) {
            if (!v.toon()) continue;
            toonWriters.add(v.route());
            if (!v.saveGate() && !NO_SAVE_GATE.containsKey(v.route()))
                bypassing.add(v.route() + "  [" + v.file().getFileName() + ", no SaveGate]");
        }
        assertTrue(bypassing.isEmpty(), () -> "config-writing routes that bypass the funnels — route the write "
                + "through SaveGate, or add a justified row to NO_SAVE_GATE:\n  " + String.join("\n  ", bypassing));

        Set<String> stale = new LinkedHashSet<>(NO_SAVE_GATE.keySet());
        stale.removeAll(toonWriters);
        assertTrue(stale.isEmpty(), () -> "NO_SAVE_GATE rows that no longer name a TOON-writing route: " + stale);
    }

    @Test
    void everyConfigWritingRouteReachesTheMakerCheckerHoldOrIsExempt() throws IOException {
        Set<String> unheld = new LinkedHashSet<>();
        Set<String> writers = new LinkedHashSet<>();
        for (Verdict v : scan()) {
            if (!v.toon() && !v.component()) continue;
            writers.add(v.route());
            if (!v.hold() && !NO_HOLD.containsKey(v.route()))
                unheld.add(v.route() + "  [" + v.file().getFileName() + "]");
        }
        assertTrue(unheld.isEmpty(), () -> "config-writing routes an approval policy cannot hold — call "
                + "PendingChanges.hold after validation and before the write, or add a justified row to NO_HOLD:\n  "
                + String.join("\n  ", unheld));
        Set<String> stale = new LinkedHashSet<>(NO_HOLD.keySet());
        stale.removeAll(writers);
        assertTrue(stale.isEmpty(), () -> "NO_HOLD rows that no longer name a config-writing route: " + stale);
    }

    // ── the repo-wide writer inventory (verification finding 4) ───────────────────────────────────

    /**
     * A config write in ANY class, route or not: a {@code ComponentStore}-shaped write/delete (a receiver
     * named {@code *store*} / {@code *components*}), a raw bundle unpack, or an Entity Fact log append. The
     * route-level test above follows a handler only within its own file, so a write delegated to another class
     * (the {@code /import} unpack, the widget tag re-projection, the agent's fix drafts) hid there. This test
     * does not start from routes at all: EVERY such call site in every module must sit in a method that reaches
     * the maker-checker hold itself, or be on {@link #WRITERS} with the reason it need not.
     */
    private static final Pattern WRITER = Pattern.compile(
            "(?i)\\b\\w*(store|components)\\w*(\\(\\w*\\))?\\.(write|delete)\\(|BundleImporter\\.writeConfig\\(|new EntityFactLog\\(|ActionRequests\\.save\\(");
    private static final Pattern HELD = Pattern.compile(
            "PendingChanges\\.(hold\\w*|governs)\\(|WidgetTags\\.refuseUnderPolicy\\(|refuseGovernedDependents\\(");

    private static final String HELPER = "a shared write helper — every ROUTE that reaches it holds first "
            + "(everyConfigWritingRouteReachesTheMakerCheckerHoldOrIsExempt)";
    private static final String NOT_GOVERNED = "writes a kind ApprovalPolicy.GOVERNABLE excludes";
    private static final String MACHINE = "a machine write by a Job / the engine, not a human change — "
            + "maker-checker holds human config changes only";

    private static final String ENTITY_FACTS = "the Identity Fact log behind Entity Lists (LA-17): an append-only, "
            + "hash-chained, reason-carrying log of analyst facts under audit/, not a ComponentStore kind — not "
            + "governable, and reserved from every import (ReservedConfigPaths audit/)";

    private static final String ACTION_REQUESTS = "an Action Request record (ASSURE-ACTION-REQUESTS-1) under "
            + "action-requests/ — an operational record of an outbound call, not config: it carries its own mandatory "
            + "four-eyes approval, so an approval policy must not hold it a second time; HMAC-signed, and reserved from "
            + "every import (ReservedConfigPaths)";

    private static final String PENDING_ALERT_RULES = "background template materializer (operator, 2026-10-06); "
            + "refuses while an approval policy governs alert-rule/dataset — its only caller, "
            + "PendingAlertRules#onRiskScoreProduced, checks PendingChanges.governs for both kinds first";

    /** Writer sites ({@code SimpleClass#method}) that do not reach the hold themselves, each with its reason. */
    static final Map<String, String> WRITERS = new TreeMap<>(Map.ofEntries(
            Map.entry("AccessRoutes#savePolicies", "access policies: " + NOT_GOVERNED + " — the Access Policies "
                    + "document (AccessPolicyStore) is not a ComponentStore kind, and the route is gated by canConfigureAccess"),
            Map.entry("AccessRoutes#write", HELPER), Map.entry("AlertRoutes#write", HELPER),
            Map.entry("BundleRoutes#write", HELPER + "; /bundle/import refuses (holdRefusing) before any item"),
            Map.entry("DecisionRoutes#write", HELPER), Map.entry("ExpectationRoutes#write", HELPER),
            Map.entry("DecisionRoutes#simulate", "a lastSimulation RESULT stamp (archive=false) — operating, not authoring"),
            Map.entry("ExpectationRoutes#runAndPersist", "a lastResult RESULT stamp (archive=false) — operating, not authoring"),
            Map.entry("NotificationRoutes#write", HELPER), Map.entry("NotificationRoutes#writeRule", HELPER),
            Map.entry("RequirementRoutes#write", "requirement: " + NOT_GOVERNED),
            Map.entry("PipelineListRoutes#deletePipeline", "a grandfathered PipelineStore graph (authored-pipeline) — "
                    + "not a governable kind; read-only apart from this delete"),
            Map.entry("PipelineRenameRoutes#rewriteComponentTargets", "rename's dependent rewrite — refused up front "
                    + "under a policy on the dependent's kind (refuseGovernedDependents)"),
            Map.entry("PipelineRenameRoutes#rewriteDatasetRefs", "as rewriteComponentTargets"),
            Map.entry("PipelineRenameRoutes#rewriteAlertRules", "as rewriteComponentTargets"),
            Map.entry("WidgetTags#reproject", "the widget tags PROJECTION — every tag route refuses first "
                    + "(WidgetTags.refuseUnderPolicy) before the edge moves; component writes project inside the hold"),
            Map.entry("SpaceManager#createFromBundle", "seeds a brand-new Space (canAdminister) — no policy, role "
                    + "table or component exists there yet to go around"),
            Map.entry("ObjectService#discard", "an operational object (Incident / Case), not config"),
            Map.entry("ObjectService#purge", "an operational object (Incident / Case), not config"),
            Map.entry("ConsignmentIngestor#parkSource", "the ingest manifest store — operational state, not config"),
            Map.entry("ConsignmentIngestor#finalizeSource", "the ingest manifest store — operational state, not config"),
            Map.entry("BackupTask#catalogRow", MACHINE + " (a catalog RESULT stamp, archive=false)"),
            Map.entry("StorageReportTask#storageCatalog", MACHINE + " (a catalog RESULT stamp, archive=false)"),
            Map.entry("ObjectsAnalyticsJob#run", MACHINE + " (a catalog RESULT stamp, archive=false)"),
            Map.entry("MaterializeTask#run", MACHINE + " (the Materialize Job restates its job-owned provenance keys "
                    + "on its target Dataset)"),
            Map.entry("EntityListRoutes#list", ENTITY_FACTS), Map.entry("EntityListRoutes#one", ENTITY_FACTS),
            Map.entry("EntityListRoutes#create", ENTITY_FACTS),
            Map.entry("EntityListRoutes#match", ENTITY_FACTS + " (a read: match persists nothing)"),
            Map.entry("EntityListRoutes#registerDataset", ENTITY_FACTS + " (only READS the log; the Dataset itself is "
                    + "written by DatasetRegistration, which holdRefusing's under a policy then takes the component save path)"),
            Map.entry("RiskWatchListFeed#check", ENTITY_FACTS + " (a read)"),
            Map.entry("RiskWatchListFeed#feed", MACHINE + " (the risk.score watch-list feed; expiring entries only, D-P5)"),
            Map.entry("EntityIdentityRoutes#assertIdentity", ENTITY_FACTS),
            Map.entry("EntityIdentityRoutes#retract", ENTITY_FACTS),
            Map.entry("EntityIdentityRoutes#importDataset", ENTITY_FACTS),   // operator-approved 2026-09-30
            Map.entry("EntityIdentityRoutes#groups", ENTITY_FACTS), Map.entry("EntityIdentityRoutes#group", ENTITY_FACTS),
            Map.entry("ValueMeasures#agents", "READS the Identity Fact log to resolve an agent Entity List for "
                    + "cash-out concentration (LA-18) — opening the log, not a write; operator-approved 2026-09-30; "
                    + ENTITY_FACTS),
            Map.entry("InvestigationRoutes#sealResolution", "READS the Identity Fact log to seal a resolution into an "
                    + "Investigation op (LA-17 slice 2) — opening the log, not a write; operator-approved 2026-09-30; "
                    + ENTITY_FACTS),
            Map.entry("InvestigationRoutes#sealList", "READS the Identity Fact log to seal a list into an "
                    + "Investigation op (LA-17) — the signal matches opening the log, which is not a write; "
                    + ENTITY_FACTS),
            Map.entry("ActionRequestRoutes#propose", ACTION_REQUESTS),
            Map.entry("ActionRequestRoutes#decide", ACTION_REQUESTS), Map.entry("ActionRequestRoutes#retry", ACTION_REQUESTS),
            Map.entry("ActionRequestRoutes#markFailed", ACTION_REQUESTS),
            Map.entry("ActionDispatcher#fail", ACTION_REQUESTS), Map.entry("ActionDispatcher#run", ACTION_REQUESTS),
            Map.entry("PendingAlertRules#ensureLatestDataset", PENDING_ALERT_RULES),
            Map.entry("ReconComponentDeleteHook#afterDelete", "the Reconciliation module's OPERATIONAL run state "
                    + "(<write-root>/recon-state/<id>.json, ReconStateStore), cleaned up after the component delete that "
                    + "calls this hook — a route that already passed the maker-checker hold in ComponentRoutes; not a "
                    + "ComponentStore config kind")
    ));

    @Test
    void everyConfigWriterRepoWideReachesTheHoldOrIsOnTheInventory() throws IOException {
        Map<String, Boolean> sites = new TreeMap<>();
        for (Path src : ReactorModules.mainJavaTrees()) {
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList())
                        writerSites(f, sites);
                }
        }
        assertTrue(sites.containsKey("DataSourceRoutes#importBundle") && sites.containsKey("ComponentRoutes#writeComponent"),
                "the writer scan went blind — signals drifted from the code: " + sites.keySet());
        Set<String> open = new LinkedHashSet<>();
        for (Map.Entry<String, Boolean> e : sites.entrySet())
            if (!e.getValue() && !WRITERS.containsKey(e.getKey())) open.add(e.getKey());
        assertTrue(open.isEmpty(), () -> "config writers that neither reach the maker-checker hold in the same "
                + "method nor sit on WRITERS with a reason:\n  " + String.join("\n  ", open));
        Set<String> stale = new LinkedHashSet<>(WRITERS.keySet());
        stale.removeAll(sites.keySet());
        assertTrue(stale.isEmpty(), () -> "WRITERS rows that name no writer site any more: " + stale);
        Set<String> heldButListed = new LinkedHashSet<>();
        for (String k : WRITERS.keySet()) if (Boolean.TRUE.equals(sites.get(k))) heldButListed.add(k);
        assertTrue(heldButListed.isEmpty(), () -> "WRITERS rows that now reach the hold — drop them: " + heldButListed);
    }

    static void writerSites(Path f, Map<String, Boolean> out) throws IOException {
        String text = withoutComments(Files.readString(f));
        Matcher w = WRITER.matcher(text);
        if (!w.find()) return;
        String cls = f.getFileName().toString().replaceFirst("\\.java$", "");
        List<int[]> spans = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();
        Matcher m = METHOD.matcher(text);
        while (m.find()) {
            if (KEYWORDS.contains(m.group(1))) continue;
            int open = m.end() - 1, depth = 0, end = text.length();
            for (int i = open; i < text.length(); i = skip(text, i) + 1) {
                char c = text.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) { end = i; break; }
            }
            spans.add(new int[] {m.start(), end});
            names.add(m.group(1));
        }
        w.reset();
        while (w.find()) {
            int best = -1;   // the innermost method whose span holds the site
            for (int i = 0; i < spans.size(); i++)
                if (spans.get(i)[0] <= w.start() && w.start() <= spans.get(i)[1]
                        && (best < 0 || spans.get(i)[0] > spans.get(best)[0])) best = i;
            String key = cls + "#" + (best < 0 ? "?" : names.get(best));
            boolean held = best >= 0 && HELD.matcher(text.substring(spans.get(best)[0], spans.get(best)[1])).find();
            out.merge(key, held, Boolean::logicalAnd);
        }
    }

    /** The four S0 routes by name, so "the scan went blind to them" cannot pass as "they are fine". */
    @Test
    void theFourPipelineEditsAreSeenAsConfigWritersAndPassSaveGate() throws IOException {
        Map<String, Verdict> byRoute = new LinkedHashMap<>();
        for (Verdict v : scan()) byRoute.put(v.route(), v);
        for (String r : List.of("POST /pipelines/([^/]+)/label", "POST /pipelines/([^/]+)/settings",
                "POST /pipelines/([^/]+)/save-as-template", "POST /pipelines/([^/]+)/rename")) {
            Verdict v = byRoute.get(r);
            assertTrue(v != null && v.toon(), () -> r + " is not recognised as a config write: " + v);
            assertTrue(v.saveGate(), () -> r + " does not pass through SaveGate: " + v);
        }
    }

    // ── the scan ──────────────────────────────────────────────────────────────────────────────────

    static List<Verdict> scan() throws IOException {
        List<Verdict> out = new java.util.ArrayList<>();
        for (Path src : ReactorModules.mainJavaTrees()) {
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList())
                        scanFile(f, out);
                }
        }
        return out;
    }

    private static void scanFile(Path f, List<Verdict> out) throws IOException {
        String text = withoutComments(Files.readString(f));
        Matcher site = SITE.matcher(text);
        if (!site.find()) return;
        Map<String, String> methods = methodBodies(text);
        site.reset();
        while (site.find()) {
            String handler = argumentTail(text, site.end());
            String closure = closure(handler, methods);
            out.add(new Verdict(site.group(1).toUpperCase(Locale.ROOT) + " " + site.group(2),
                    TOON_WRITE.matcher(closure).find(), COMPONENT_WRITE.matcher(closure).find(),
                    SAVE_GATE.matcher(closure).find(), HOLD.matcher(closure).find(), f,
                    REAL_HOLD.matcher(closure).find(), closure));
        }
    }

    /** The rest of the registration call's argument list, from {@code start} to its closing parenthesis. */
    private static String argumentTail(String text, int start) {
        int depth = 1;
        for (int i = start; i < text.length(); i = skip(text, i) + 1) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return text.substring(start, i);
        }
        return text.substring(start);
    }

    /** An UNQUALIFIED call — {@code Roles.write(…)} is another class's method, never this file's {@code write}. */
    private static final Pattern CALL = Pattern.compile("(?<![.\\w])([a-zA-Z_]\\w*)\\s*\\(");

    /** {@code seed} plus the bodies of every same-file method it reaches, transitively. */
    private static String closure(String seed, Map<String, String> methods) {
        StringBuilder sb = new StringBuilder(seed);
        Set<String> seen = new java.util.HashSet<>();
        Deque<String> work = new ArrayDeque<>(List.of(seed));
        while (!work.isEmpty()) {
            Matcher m = CALL.matcher(work.pop());
            while (m.find()) {
                String name = m.group(1);
                String body = methods.get(name);
                if (body != null && seen.add(name)) {
                    sb.append('\n').append(body);
                    work.push(body);
                }
            }
        }
        return sb.toString();
    }

    private static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|private|protected|static|final|synchronized|default)\\s+)*"
                    + "(?:<[^>]+>\\s+)?[\\w.<>\\[\\],? ]+?\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized",
            "return", "new", "else", "try", "do");

    /** Method name → body (overloads concatenated), brace-matched over code only. */
    static Map<String, String> methodBodies(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = METHOD.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (KEYWORDS.contains(name)) continue;
            int open = m.end() - 1, depth = 0, end = text.length();
            for (int i = open; i < text.length(); i = skip(text, i) + 1) {
                char c = text.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) { end = i; break; }
            }
            out.merge(name, text.substring(open, end), (a, b) -> a + "\n" + b);
        }
        return out;
    }

    /**
     * {@code text} with every comment blanked (newlines kept) — prose like "a refused rename (above)" must not
     * read as a call. String literals stay: a component kind is often one ({@code store.write("dataset", …)}).
     */
    static String withoutComments(String text) {
        // Dropped, not blanked: long runs of blanks send the METHOD pattern into catastrophic backtracking.
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            int end = skip(text, i);
            if (end > i && text.charAt(i) == '/') {
                sb.append(' ');
                for (int j = i; j <= end && j < text.length(); j++) if (text.charAt(j) == '\n') sb.append('\n');
            } else {
                sb.append(text, i, end + 1);
            }
            i = end;
        }
        return sb.toString();
    }

    /** Index of the last character of the token at {@code i} when it opens a string, char or comment. */
    private static int skip(String t, int i) {
        char c = t.charAt(i);
        if (c == '"') {
            if (t.startsWith("\"\"\"", i)) {
                int e = t.indexOf("\"\"\"", i + 3);
                return e < 0 ? t.length() - 1 : e + 2;
            }
            for (int j = i + 1; j < t.length(); j++) {
                if (t.charAt(j) == '\\') j++;
                else if (t.charAt(j) == '"') return j;
            }
        } else if (c == '\'') {
            for (int j = i + 1; j < t.length(); j++) {
                if (t.charAt(j) == '\\') j++;
                else if (t.charAt(j) == '\'') return j;
            }
        } else if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '/') {
            int e = t.indexOf('\n', i);
            return e < 0 ? t.length() - 1 : e;
        } else if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '*') {
            int e = t.indexOf("*/", i + 2);
            return e < 0 ? t.length() - 1 : e + 1;
        }
        return i;
    }

    /** Census helper for a human: {@code -Dfunnel.census=true} prints every writer's verdict. */
    @Test
    void census() throws IOException {
        if (!Boolean.getBoolean("funnel.census")) return;
        for (Verdict v : scan())
            if (v.toon() || v.component())
                System.out.println("[FUNNEL] " + v.route() + " toon=" + v.toon() + " comp=" + v.component()
                        + " saveGate=" + v.saveGate() + " hold=" + v.hold() + " " + v.file().getFileName());
    }
}
