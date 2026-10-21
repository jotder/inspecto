package com.gamma.control;

import java.util.regex.Pattern;

/**
 * The rate-limit class of every Link Analysis route ({@code /geo/*}, {@code /inv/*}, {@code /entity-lists*}) -
 * roadmap Data-preparation item 7 (M16 part). Until it existed the throttle in {@link RateLimiter} covered
 * only {@code /db/query}, {@code /bi/query}, {@code /recon/*}, {@code /agent/*}, the delivery callback and
 * push-ingest, so the heaviest LA calls (a projection or traversal over a whole Dataset, a Graph Run, an Index
 * build) were unthrottled.
 *
 * <p>Two classes, both explicit: {@link #EXPENSIVE} routes spend the {@link RateLimiter#linkAnalysis()} bucket
 * (per subject, 429 {@code RATE_LIMITED}); {@link #EXEMPT} routes are cheap reads or small bounded writes and
 * are not throttled. {@code LinkAnalysisRateClassCoverageTest} walks the {@code provides.routes} of the la-api, geo-link and
 * entity-list manifests (AbsentModuleRoutes.surface) and fails when a route is in neither table, so a new LA route must
 * choose a class. The classes are fixed; only the Link Analysis budget is tunable, via {@code control.rateLimit.linkAnalysis.*}.
 */
final class LinkAnalysisRateClasses {

    private LinkAnalysisRateClasses() {}

    /** Scan a Dataset or compute a graph from the request body: DuckDB-saturating. {method, path regex}. */
    static final String[][] EXPENSIVE = {
            {"POST", "/inv/traversal/recursive-paths"},
            {"POST", "/geo/routes"},
            {"POST", "/inv/projection/multi"},
            {"POST", "/inv/projection/neighbors"},
            {"POST", "/inv/projection/neighbors/preview"},          // LA-GROW-EXCLUDE-1: the explorer's Grow preview reads like the step
            {"POST", "/inv/investigations/([^/]+)/expand/preview"}, // LA-GROW-EXCLUDE-1: the dry run reads exactly what the expand would
            {"POST", "/geo/projection"},
            {"POST", "/inv/pattern/temporal"},
            {"POST", "/inv/pattern/branching"},
            {"POST", "/inv/schema/overlap-profile"},
            {"POST", "/inv/graph/runs"},
            {"POST", "/inv/index/builds"},
            {"POST", "/inv/uploads/([^/]+)"},   // LA-ANALYST-UPLOAD-1: buffers up to upload.max_bytes and writes disk
            {"GET", "/inv/uploads/([^/]+)"},    // LA-I2-GAPS-RESIDUALS-1: reads up to 200 records and probes the Collector's marker / quarantine dirs for each
            {"POST", "/inv/projection"},
            {"POST", "/inv/reference-rows"},
            {"POST", "/inv/case-history"},
            // la-entity-profile-plan §4.5: a profile aggregates every bound Dataset (the type's) or filters each (one Entity's)
            {"GET", "/inv/entity-types/([^/]+)/profile"},
            {"GET", "/inv/entities/([^/]+)/([^/]+)/profile"},
            {"POST", "/inv/entities/([^/]+)/resolve"},           // rate-classed like the GET it serves (operator 2026-10-15)
            // LA-EDITIONS-1 §4 inbound: a third-party product calls these machine to machine, so a flood is throttled per Subject
            // (429; the inbound create is idempotent, so a retry is safe). The deep-link resolve is classed like the resolve above.
            {"POST", "/inv/inbound/investigations"},
            {"POST", "/inv/deep-links/resolve"},
            // LA-EMBED-VIEW-1: the public embed view is anonymous and re-evaluates an Investigation's sealed log per request
            // (ControlApi throttles it per TOKEN and per IP, on its own buckets).
            {"GET", "/public/investigations/([^/]+)"},
            // LA-EDITIONS-1 phase 6: builds the Dossier bundle and holds the request through an outbound send and its retries
            {"POST", "/inv/investigations/([^/]+)/handoff"},
    };

    /** Everything else: Investigation / draft / member / case / alert-rule / identity / Entity List CRUD and
     *  reads of one Investigation's own sealed log, status and cancel of a Graph Run or Index build. Each is
     *  bounded by one Investigation or one record. Not throttled; follow up per measurement if one proves heavy
     *  (candidates: working-set, coverage, compare, dossier, replay). */
    static final String[][] EXEMPT = {
            {"GET", "/inv/schema/relationships"},
            {"GET", "/inv/value-measures"},
            {"POST", "/inv/snapshots"},
            {"GET", "/inv/uploads"},                       // LA-I2-GAPS-RESIDUALS-1: the upload-enabled Collectors, from config
            {"DELETE", "/inv/uploads/([^/]+)/([^/]+)"},    // LA-I2-GAPS-RESIDUALS-1: removes one pending file and its record
            {"GET", "/inv/snapshots"},
            {"POST", "/inv/snapshots/attach"},
            {"GET", "/inv/investigations"},
            {"POST", "/inv/investigations"},
            {"POST", "/inv/investigations/([^/]+)/ops"},
            {"POST", "/inv/investigations/([^/]+)/undo"},
            {"POST", "/inv/investigations/([^/]+)/reorder"},
            {"POST", "/inv/investigations/([^/]+)/replay"},
            {"GET", "/inv/investigations/([^/]+)/log"},
            {"POST", "/inv/investigations/([^/]+)/reveal"},
            {"POST", "/inv/investigations/([^/]+)/pending/([^/]+)/approve"},
            {"POST", "/inv/investigations/([^/]+)/pending/([^/]+)/deny"},
            {"GET", "/inv/pending"},
            {"GET", "/inv/investigations/([^/]+)/dossier"},
            {"POST", "/inv/investigations/([^/]+)/dossier/verify"},
            {"GET", "/inv/investigations/([^/]+)/dossier/bundle"},
            {"POST", "/inv/investigations/([^/]+)/dossier/bundle/verify"},
            {"GET", "/inv/investigations/([^/]+)/references"},
            {"POST", "/inv/investigations/([^/]+)/references"},
            {"GET", "/inv/investigations/([^/]+)/working-set"},
            {"GET", "/inv/investigations/([^/]+)/coverage"},
            {"GET", "/inv/investigations/([^/]+)/compare"},
            {"POST", "/inv/investigations/([^/]+)/template"},
            {"GET", "/inv/investigation-templates"},
            {"GET", "/inv/investigation-templates/([^/]+)"},
            {"POST", "/inv/investigation-templates/([^/]+)/instantiate"},
            {"GET", "/inv/investigations/([^/]+)/measures"},
            {"GET", "/inv/investigations/([^/]+)/data-changes"},
            {"GET", "/inv/investigations/([^/]+)/alert-rules"},
            {"POST", "/inv/investigations/([^/]+)/alert-rules"},
            {"POST", "/inv/investigations/([^/]+)/standing-detection"},
            {"DELETE", "/inv/investigations/([^/]+)/standing-detection/([^/]+)"},
            {"PUT", "/inv/investigations/([^/]+)/alert-rules/([^/]+)"},
            {"GET", "/inv/investigations/([^/]+)/case"},
            {"PUT", "/inv/investigations/([^/]+)/case"},
            {"DELETE", "/inv/investigations/([^/]+)/case"},
            {"GET", "/inv/investigations/([^/]+)/members"},
            {"POST", "/inv/investigations/([^/]+)/members"},
            {"POST", "/inv/investigations/([^/]+)/members/revoke"},
            {"GET", "/inv/investigations/([^/]+)/embeds"},
            {"POST", "/inv/investigations/([^/]+)/embeds"},
            {"POST", "/inv/investigations/([^/]+)/embeds/([^/]+)/revoke"},
            {"POST", "/inv/investigations/([^/]+)/drafts"},
            {"GET", "/inv/investigations/([^/]+)/drafts"},
            {"GET", "/inv/investigations/([^/]+)/drafts/([^/]+)"},
            {"GET", "/inv/investigations/([^/]+)/drafts/([^/]+)/log"},
            {"GET", "/inv/investigations/([^/]+)/drafts/([^/]+)/working-set"},
            {"GET", "/inv/investigations/([^/]+)/drafts/([^/]+)/replay"},
            {"POST", "/inv/investigations/([^/]+)/drafts/([^/]+)/ops"},
            {"POST", "/inv/investigations/([^/]+)/drafts/([^/]+)/undo"},
            {"POST", "/inv/investigations/([^/]+)/drafts/([^/]+)/discard"},
            {"GET", "/inv/investigations/([^/]+)/drafts/([^/]+)/conflicts"},
            {"POST", "/inv/investigations/([^/]+)/drafts/([^/]+)/rebase"},
            {"POST", "/inv/investigations/([^/]+)/drafts/([^/]+)/promote"},
            {"GET", "/inv/entity-identities"},
            {"GET", "/inv/entity-identities/group"},
            {"POST", "/inv/entity-identities"},
            {"POST", "/inv/entity-identities/import"},
            {"POST", "/inv/entity-identities/([^/]+)/retract"},
            {"GET", "/inv/graph/algorithms"},
            {"GET", "/inv/graph/runs"},
            {"GET", "/inv/graph/runs/([^/]+)"},
            {"POST", "/inv/graph/runs/([^/]+)/cancel"},
            {"GET", "/inv/index"},
            {"GET", "/inv/datasets/([^/]+)/files"},
            {"GET", "/inv/entity-types"},
            {"GET", "/inv/integration"},
            {"PUT", "/inv/integration"},
            {"GET", "/inv/index/builds/([^/]+)"},
            {"POST", "/inv/index/builds/([^/]+)/cancel"},
            {"GET", "/entity-lists"},
            {"GET", "/entity-lists/([^/]+)"},
            {"POST", "/entity-lists"},
            {"POST", "/entity-lists/([^/]+)/members"},
            {"POST", "/entity-lists/([^/]+)/retire"},
            {"POST", "/entity-lists/([^/]+)/match"},
            {"POST", "/entity-lists/([^/]+)/register-dataset"},
    };

    private static final Pattern[] EXPENSIVE_PATTERNS = compile(EXPENSIVE);

    private static Pattern[] compile(String[][] t) {
        Pattern[] out = new Pattern[t.length];
        for (int i = 0; i < t.length; i++) out[i] = Pattern.compile("^" + t[i][0] + " " + t[i][1] + "$");
        return out;
    }

    /** True when {@code method path} (path without the API prefix) is an EXPENSIVE Link Analysis route. */
    static boolean isExpensive(String method, String path) {
        String k = method + " " + path;
        for (Pattern p : EXPENSIVE_PATTERNS) if (p.matcher(k).matches()) return true;
        return false;
    }
}
