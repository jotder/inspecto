package com.gamma.entitystore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The ONE catalogue of Link Analysis limits ({@code LA-CONFIG-LIMITS-1}, plan §6.1 / §7): every hardcoded cap, budget,
 * fence and host-resource limit Link Analysis applies, as one configuration item each. The {@link #path} is both the
 * dotted TOON path the value is stored at and the wire key the SPA reads; {@link Layer#SPACE} items live in the
 * Space's {@code link-analysis.toon}, {@link Layer#INSTALL} items in the server-wide {@code la-limits.toon}
 * ({@link LaLimits#INSTALL_FILE}). {@link #browser} items may additionally carry a per-analyst value in the browser,
 * clamped to the Space's value.
 *
 * <p>⚠ <b>P1 shipped TODAY's values</b> (no behaviour change); the phase that moves a default (P2 canvas and browser
 * algorithms - done, CL-1..CL-3; P3 doubled analysis limits, P4 host resources) changes it here. {@link #min} / {@link #max} are the
 * per-item range (CL-16): a write outside it is refused 422 naming the range, and a stored value outside it reads as
 * the default. For the keys that existed before this catalogue the range is the one their write route already
 * enforced, so a value the old route accepted reads exactly as before. ⚠ Two floors are LOWER than that route's:
 * {@link #MAX_SET_BYTES} and {@link #MAX_INVESTIGATION_BYTES} take 1 byte, as the settings reader always has (a tiny
 * hand-edited budget is how the budget contract tests drive a refusal); P1 changes no behaviour.
 *
 * <p>Only two items have no default ("no threshold"): {@link #FOUR_EYES_BUDGET_ABOVE} and
 * {@link #FOUR_EYES_FAN_OUT_ABOVE}. {@link #INDEX_BUILD_MEMORY_PCT} had none until P4 gave it its own 80 % (CL-8).
 */
public enum LaLimit {

    // ── canvas (SPACE, browser) ───────────────────────────────────────────────────────────────────────────────
    /** P2 (CL-1): 1,000 (was 500). */
    PROJECTION_NODE_CAP("projection_node_cap", Layer.SPACE, true, "canvas", "nodes", 1_000, 1, 100_000,
            "Entities admitted to one canvas projection."),

    // ── browser algorithms (SPACE, browser) ──────────────────────────────────────────────────────────────────
    /**
     * P2 (CL-3): 2,500 (was 2,000). The GENERAL browser cap: the threshold above which the selection algorithms (paths,
     * cycles, cut points, spanning forest) go to the server, and the cap of a browser analysis with no item of its own
     * (branching patterns). Each catalogue algorithm has its own {@code browser_algorithms.<id>} item below.
     */
    ANALYSIS_NODE_CAP("analysis_node_cap", Layer.SPACE, true, "browser_algorithms", "nodes", 2_500, 1, 100_000,
            "Nodes above which the selection algorithms (paths, cycles, cut points, spanning forest) run on the server "
                    + "instead of the browser, and above which a branching pattern is refused in the browser."),
    /**
     * P2 (CL-2): 1,000, ONE value for the browser and the server (was 750 in the browser, 500 on the server). The browser
     * refuses suspicion score above it; a server run above it is a job, not an inline answer ({@code GraphRunRoutes}).
     * ⚠ So there is NO {@code browser_algorithms.suspicionScore} item: this is it (one concept, one key).
     */
    SUSPICION_NODE_CAP("suspicion_node_cap", Layer.SPACE, true, "browser_algorithms", "nodes", 1_000, 1, 100_000,
            "Nodes above which suspicion score refuses to run in the browser, and above which a server run of it is a "
                    + "job rather than an inline answer."),
    // One item per catalogue algorithm (com.gamma.la.core.Algorithm) the browser CONSULTS - it refuses above it, or hands
    // the run to the server - and never one the browser runs at any size or not at all (lead decision 2026-10-15: no dead
    // settings in the menu). suspicionScore's is SUSPICION_NODE_CAP. P2 (CL-3) defaults: 2,500, or the algorithm's
    // measured server inline ceiling where that is lower.
    BROWSER_SHORTEST_PATH("shortestPath", Use.HANDOVER),
    BROWSER_ARTICULATION_POINTS("articulationPoints", Use.HANDOVER),
    BROWSER_BRIDGES("bridges", Use.HANDOVER),
    BROWSER_WEIGHTED_SHORTEST_PATH("weightedShortestPath", Use.HANDOVER),
    BROWSER_MAXIMUM_SPANNING_FOREST("maximumSpanningForest", Use.HANDOVER),
    BROWSER_BETWEENNESS_CENTRALITY("betweennessCentrality", Use.REFUSE, 500),
    BROWSER_CLOSENESS_CENTRALITY("closenessCentrality", Use.REFUSE, 1_000),
    BROWSER_LOUVAIN_COMMUNITIES("louvainCommunities", Use.REFUSE),
    BROWSER_DETECT_COMMUNITIES("detectCommunities", Use.HANDOVER),
    BROWSER_CLIQUES("cliques", Use.REFUSE, 1_000),
    BROWSER_FIND_CYCLES("findCycles", Use.HANDOVER),
    BROWSER_ALL_PATHS("allPaths", Use.HANDOVER),
    BROWSER_MAX_FLOW("maxFlow", Use.REFUSE, 1_000),   // vocab-allow: the max-flow graph algorithm (Algorithm.MAX_FLOW), the sanctioned lowercase sense
    BROWSER_LINK_PREDICTION("linkPrediction", Use.REFUSE, 1_000),
    BROWSER_EIGENVECTOR_CENTRALITY("eigenvectorCentrality", Use.REFUSE, 1_000),
    BROWSER_KATZ_CENTRALITY("katzCentrality", Use.REFUSE, 1_000),
    BROWSER_HITS("hits", Use.REFUSE, 1_000),

    // ── display (SPACE, browser; SPA-only today) ─────────────────────────────────────────────────────────────
    DISPLAY_LOD_LABEL_CAP("display.lod_label_cap", Layer.SPACE, true, "display", "count", 300, 1, 30_000,
            "Labels drawn on the canvas before level-of-detail hides the rest."),
    DISPLAY_LAYERED_MAX_NODES("display.layered_max_nodes", Layer.SPACE, true, "display", "nodes", 30, 1, 3_000,
            "Nodes above which the layered layout is not offered."),
    DISPLAY_FORCE_ATLAS_MAX_NODES("display.force_atlas_max_nodes", Layer.SPACE, true, "display", "nodes", 800, 1, 80_000,
            "Nodes above which the force-atlas layout is not offered."),
    DISPLAY_EMBED_LABEL_LIMIT("display.embed_label_limit", Layer.SPACE, true, "display", "count", 80, 1, 8_000,
            "Labels drawn in the embedded Investigation view."),
    DISPLAY_EMBED_EDGE_LIMIT("display.embed_edge_limit", Layer.SPACE, true, "display", "edges", 3_000, 1, 300_000,
            "Edges drawn in the embedded Investigation view."),
    DISPLAY_GEO_POINT_CAP("display.geo_point_cap", Layer.SPACE, true, "display", "count", 5_000, 1, 100_000,
            "Points one map projection or geo analysis reads."),

    // ── investigation (SPACE) ────────────────────────────────────────────────────────────────────────────────
    HUB_THRESHOLD("hub_threshold", Layer.SPACE, false, "investigation", "count", 500, 1, 100_000,
            "An expand candidate with more distinct contacts than this is flagged high connectivity and not expanded further."),
    MERGED_DISTINCT_CAP("merged_distinct_cap", Layer.SPACE, false, "investigation", "count", 20_000, 1, 100_000,
            "Distinct values per bound column a merged expand may scan; above it the expand is refused."),
    SEED_BY_DISTINCT_CAP("seed_by_distinct_cap", Layer.SPACE, false, "investigation", "count", 20_000, 1, 100_000,
            "Distinct values per bound column a seedBy may scan; above it the seed is refused."),
    FOUR_EYES_BUDGET_ABOVE("four_eyes_budget_above", Layer.SPACE, false, "investigation", "rows", null, 1, 100_000,
            "An expand whose row budget exceeds this waits for a second person's approval; empty = no threshold."),
    FOUR_EYES_FAN_OUT_ABOVE("four_eyes_fan_out_above", Layer.SPACE, false, "investigation", "count", null, 1, 100_000,
            "An expand whose fan-out exceeds this, or is unbounded, waits for a second person's approval; empty = no threshold."),
    INVESTIGATION_EXPAND_BUDGET_DEFAULT("investigation.expand_budget_default", Layer.SPACE, false, "investigation", "rows",
            4_000, 1, 200_000, "Rows an expand reads per level when it states no budget."),
    INVESTIGATION_EXPAND_BUDGET_MAX("investigation.expand_budget_max", Layer.SPACE, false, "investigation", "rows",
            40_000, 1, 2_000_000, "The largest row budget an expand may state."),
    INVESTIGATION_MAX_FRONTIER("investigation.max_frontier", Layer.SPACE, false, "investigation", "count", 2_000, 1, 100_000,
            "Frontier ids one expand may start from."),
    INVESTIGATION_ONLY_EXEMPT_MAX("investigation.only_exempt_max", Layer.SPACE, false, "investigation", "count", 50, 1, 5_000,
            "An expand restricted to at most this many chosen neighbours is never held for four-eyes."),
    INVESTIGATION_LOG_PAGE_DEFAULT("investigation.log_page_default", Layer.SPACE, false, "investigation", "count", 500, 1, 50_000,
            "Log entries one Investigation log read returns when it states no limit."),
    INVESTIGATION_LOG_PAGE_MAX("investigation.log_page_max", Layer.SPACE, false, "investigation", "count", 5_000, 1, 500_000,
            "The most log entries one Investigation log read returns."),
    INVESTIGATION_LIST_PAGE_DEFAULT("investigation.list_page_default", Layer.SPACE, false, "investigation", "count", 100, 1, 10_000,
            "Investigations one list read returns when it states no limit."),
    INVESTIGATION_LIST_PAGE_MAX("investigation.list_page_max", Layer.SPACE, false, "investigation", "count", 1_000, 1, 100_000,
            "The most Investigations one list read returns."),

    // ── connect (SPACE) ──────────────────────────────────────────────────────────────────────────────────────
    CONNECT_MAX_SEEDS("connect.max_seeds", Layer.SPACE, false, "connect", "count", 40, 2, 2_000,
            "Seeds one connect may join (n seeds are n(n-1)/2 pairs)."),
    CONNECT_MAX_HOPS("connect.max_hops", Layer.SPACE, false, "connect", "hops", 6, 1, 6,
            "The most hops a connect may state; work grows with degree to the power of hops."),
    CONNECT_DEFAULT_HOPS("connect.default_hops", Layer.SPACE, false, "connect", "hops", 3, 1, 6,
            "Hops a connect walks when it states none."),

    // ── traversal (SPACE) ────────────────────────────────────────────────────────────────────────────────────
    TRAVERSAL_DEPTH_DEFAULT("traversal.depth_default", Layer.SPACE, false, "traversal", "hops", 12, 1, 50,
            "Recursive path depth when a path search states none."),
    TRAVERSAL_DEPTH_MAX("traversal.depth_max", Layer.SPACE, false, "traversal", "hops", 20, 1, 50,
            "The deepest recursive path search allowed."),
    TRAVERSAL_TIMEOUT_S("traversal.timeout_s", Layer.SPACE, false, "traversal", "s", 10, 1, 300,
            "Statement timeout of one recursive path search."),
    TRAVERSAL_PATHS_DEFAULT("traversal.paths_default", Layer.SPACE, false, "traversal", "count", 2_000, 1, 100_000,
            "Paths a path search returns when it states no limit."),
    TRAVERSAL_PATHS_MAX("traversal.paths_max", Layer.SPACE, false, "traversal", "count", 20_000, 1, 1_000_000,
            "The most paths one path search returns."),
    TRAVERSAL_PAIRS_DEFAULT("traversal.pairs_default", Layer.SPACE, false, "traversal", "count", 200, 1, 10_000,
            "Dataset pairs an overlap profile measures when it states no limit (one query per pair)."),
    TRAVERSAL_PAIRS_MAX("traversal.pairs_max", Layer.SPACE, false, "traversal", "count", 1_000, 1, 50_000,
            "The most Dataset pairs one overlap profile measures."),
    TRAVERSAL_EDGE_YIELD_DEFAULT("traversal.edge_yield_default", Layer.SPACE, false, "traversal", "edges", 20_000, 1, 1_000_000,
            "Edges each recursion level may yield when a path search states none."),
    TRAVERSAL_EDGE_YIELD_MAX("traversal.edge_yield_max", Layer.SPACE, false, "traversal", "edges", 200_000, 1, 10_000_000,
            "The most edges each recursion level may yield."),
    TRAVERSAL_ROWS_DEFAULT("traversal.rows_default", Layer.SPACE, false, "traversal", "rows", 4_000, 1, 200_000,
            "Rows a projection or neighbour read returns when it states no limit."),
    TRAVERSAL_ROWS_MAX("traversal.rows_max", Layer.SPACE, false, "traversal", "rows", 40_000, 1, 2_000_000,
            "The most rows one projection or neighbour read returns."),

    // ── neighbours, snapshots, files, working set (SPACE) ─────────────────────────────────────────────────────
    NEIGHBOURS_PAGE_DEFAULT("neighbours.page_default", Layer.SPACE, false, "neighbours", "count", 100, 1, 5_000,
            "Neighbours per page of the neighbour list when the read states no limit."),
    NEIGHBOURS_PAGE_MAX("neighbours.page_max", Layer.SPACE, false, "neighbours", "count", 400, 1, 20_000,
            "The most neighbours per page of the neighbour list."),
    SNAPSHOTS_PAGE_DEFAULT("snapshots.page_default", Layer.SPACE, false, "snapshots", "count", 200, 1, 10_000,
            "Snapshots one list read returns when it states no limit."),
    SNAPSHOTS_PAGE_MAX("snapshots.page_max", Layer.SPACE, false, "snapshots", "count", 2_000, 1, 100_000,
            "The most snapshots one list read returns."),
    FILES_PAGE_DEFAULT("files.page_default", Layer.SPACE, false, "files", "count", 200, 1, 10_000,
            "Dataset files one list read returns when it states no limit."),
    FILES_PAGE_MAX("files.page_max", Layer.SPACE, false, "files", "count", 2_000, 1, 100_000,
            "The most Dataset files one list read returns."),
    MAX_SET_BYTES("max_set_bytes", Layer.SPACE, false, "working_set", "bytes", 64L * 1024 * 1024, 1, 1_073_741_824L,
            "The most sealed Working Set text one step may store; above it the write is refused."),
    MAX_INVESTIGATION_BYTES("max_investigation_bytes", Layer.SPACE, false, "working_set", "bytes", 4L * 1024 * 1024 * 1024,
            1, 1_099_511_627_776L, "The most sealed Working Set text one Investigation may hold in total."),
    WORKING_SET_PAGE_DEFAULT("working_set.page_default", Layer.SPACE, false, "working_set", "rows", 2_000, 1, 100_000,
            "Working Set rows one read returns when it states no limit."),
    WORKING_SET_PAGE_MAX("working_set.page_max", Layer.SPACE, false, "working_set", "rows", 20_000, 1, 1_000_000,
            "The most Working Set rows one read returns."),

    // ── pattern (SPACE) ──────────────────────────────────────────────────────────────────────────────────────
    PATTERN_MAX_LEGS("pattern.max_legs", Layer.SPACE, false, "pattern", "rows", 200_000, 1, 10_000_000,
            "Legs a branching pattern search may read."),
    PATTERN_MATCHES_DEFAULT("pattern.matches_default", Layer.SPACE, false, "pattern", "count", 400, 1, 20_000,
            "Matches a branching pattern search returns when it states no limit."),
    PATTERN_MATCHES_MAX("pattern.matches_max", Layer.SPACE, false, "pattern", "count", 2_000, 1, 100_000,
            "The most matches one branching pattern search returns."),
    PATTERN_TIMEOUT_S("pattern.timeout_s", Layer.SPACE, false, "pattern", "s", 10, 1, 300,
            "Statement timeout of one pattern search."),
    PATTERN_TEMPORAL_MAX_ROWS("pattern.temporal_max_rows", Layer.SPACE, false, "pattern", "rows", 400_000, 1, 20_000_000,
            "Event rows a temporal pattern scan may read."),
    PATTERN_TEMPORAL_LIMIT_DEFAULT("pattern.temporal_limit_default", Layer.SPACE, false, "pattern", "count", 400, 1, 20_000,
            "Links a temporal pattern scan returns when it states no limit."),
    PATTERN_TEMPORAL_LIMIT_MAX("pattern.temporal_limit_max", Layer.SPACE, false, "pattern", "count", 2_000, 1, 100_000,
            "The most links one temporal pattern scan returns."),

    // ── measures, comparison, reference, case history, dossier, identity (SPACE) ──────────────────────────────
    MEASURES_MAX_ENTITIES("measures.max_entities", Layer.SPACE, false, "measures", "count", 20_000, 1, 1_000_000,
            "Entities one Value Measures read may answer for."),
    MEASURES_TIMEOUT_S("measures.timeout_s", Layer.SPACE, false, "measures", "s", 10, 1, 300,
            "Statement timeout of one Value Measures read."),
    MEASURES_MAX_WINDOW_DAYS("measures.max_window_days", Layer.SPACE, false, "measures", "days", 31, 1, 3_660,
            "The widest time window one Value Measures read may span."),
    COMPARISON_MAX_ENTITIES("comparison.max_entities", Layer.SPACE, false, "comparison", "count", 4_000, 1, 200_000,
            "Entities one window comparison may compare."),
    COMPARISON_MAX_ROWS("comparison.max_rows", Layer.SPACE, false, "comparison", "rows", 100_000, 1, 5_000_000,
            "Rows one window comparison may read."),
    COMPARISON_LIST_CAP("comparison.list_cap", Layer.SPACE, false, "comparison", "count", 1_000, 1, 50_000,
            "Entries each list of a window comparison returns."),
    REFERENCE_MAX_KEYS("reference.max_keys", Layer.SPACE, false, "reference", "count", 500, 1, 50_000,
            "Keys one reference-row read may look up."),
    REFERENCE_MAX_ROWS("reference.max_rows", Layer.SPACE, false, "reference", "rows", 4_000, 1, 200_000,
            "Rows one reference-row read returns."),
    CASE_HISTORY_PAGE_MAX("case_history.page_max", Layer.SPACE, false, "case_history", "count", 400, 1, 20_000,
            "The most entries one Case history read returns."),
    CASE_HISTORY_MAX_KEY_LENGTH("case_history.max_key_length", Layer.SPACE, false, "case_history", "chars", 256, 1, 25_600,
            "The longest key a Case history read may look up."),
    CASE_HISTORY_MAX_LINKED_INVESTIGATIONS("case_history.max_linked_investigations", Layer.SPACE, false, "case_history",
            "count", 200, 1, 10_000, "Case-linked Investigations one Case history read evaluates."),
    CASE_HISTORY_MAX_ATTACHMENTS("case_history.max_attachments", Layer.SPACE, false, "case_history", "count", 1_000, 1, 50_000,
            "Attachment records one Case history read evaluates."),
    DOSSIER_MAX_STEPS("dossier.max_steps", Layer.SPACE, false, "dossier", "count", 10_000, 1, 500_000,
            "Log steps one dossier may carry."),
    DOSSIER_MAX_SNAPSHOTS("dossier.max_snapshots", Layer.SPACE, false, "dossier", "count", 40, 1, 2_000,
            "Snapshots one dossier may carry."),
    IDENTITY_IMPORT_DEFAULT("identity.import_default", Layer.SPACE, false, "identity", "rows", 1_000, 1, 100_000,
            "Identity rows one import reads when it states no limit."),
    IDENTITY_IMPORT_MAX("identity.import_max", Layer.SPACE, false, "identity", "rows", 10_000, 1, 1_000_000,
            "The most identity rows one import reads."),
    IDENTITY_IMPORT_TIMEOUT_S("identity.import_timeout_s", Layer.SPACE, false, "identity", "s", 10, 1, 300,
            "Statement timeout of one identity import."),

    // ── entity profile (SPACE; la-entity-profile-plan §4.4 / §4.5) ──────────────────────────────────────────────
    ENTITY_PROFILE_TIMEOUT_S("entity_profile.timeout_s", Layer.SPACE, false, "entity_profile", "s", 30, 1, 600,
            "Statement timeout of one Entity profile read."),
    ENTITY_PROFILE_MAX_DATASETS("entity_profile.max_datasets", Layer.SPACE, false, "entity_profile", "count", 20, 1, 2_000,
            "Bound Datasets one Entity Type profile reads; the rest are left out and the profile says truncated."),
    ENTITY_PROFILE_SHAPE_SAMPLE_ROWS("entity_profile.shape_sample_rows", Layer.SPACE, false, "entity_profile", "rows", 1_000_000, 1,
            1_000_000_000, "Values the value-shape histograms read; above it they read a sample."),
    ENTITY_PROFILE_TOP_N("entity_profile.top_n", Layer.SPACE, false, "entity_profile", "count", 20, 1, 2_000,
            "Entries each histogram or list of an Entity profile returns."),
    ENTITY_PROFILE_SENTINEL_SHARE("entity_profile.sentinel_share", Layer.SPACE, false, "entity_profile", "pct", 20, 1, 100,
            "A single value holding more than this share of a column's values is reported as a dominant sentinel."),
    ENTITY_PROFILE_TIMELINE_DAYS("entity_profile.timeline_days", Layer.SPACE, false, "entity_profile", "days", 400, 1, 36_600,
            "Days an Entity profile timeline returns, newest first."),
    ENTITY_PROFILE_RELATIONSHIP_THRESHOLD("entity_profile.relationship_threshold", Layer.SPACE, false, "entity_profile", "count", 6, 2,
            1_000_000, "Entities carrying at least this many of another Entity Type in the same rows are counted as outliers."),
    ENTITY_PROFILE_MEMORY_LIMIT_MB("entity_profile.memory_limit_mb", Layer.INSTALL, false, "entity_profile", "mb", 4_096, 64,
            1_048_576, "Memory one Entity profile query may use. Profile SQL is trusted, so it is not held to the 1 GB sandbox "
                    + "default; on 132 million rows the dominant-value and relationship reads need about 4 GB."),

    // ── input shape caps (SPACE) ─────────────────────────────────────────────────────────────────────────────
    INPUT_ID_LENGTH("input.id_length", Layer.SPACE, false, "input", "chars", 512, 1, 51_200,
            "The longest entity id an Investigation op may carry."),
    INPUT_MAX_IDS("input.max_ids", Layer.SPACE, false, "input", "count", 1_000, 1, 100_000,
            "Ids or links one Investigation op may carry."),
    INPUT_NOTE_LENGTH("input.note_length", Layer.SPACE, false, "input", "chars", 2_000, 1, 200_000,
            "The longest note on an Investigation step."),
    INPUT_PURPOSE_LENGTH("input.purpose_length", Layer.SPACE, false, "input", "chars", 1_000, 1, 100_000,
            "The longest stated purpose of an Investigation."),
    INPUT_REVEAL_IDS("input.reveal_ids", Layer.SPACE, false, "input", "count", 100, 1, 10_000,
            "Masked ids one reveal may unmask."),
    INPUT_LINK_KINDS("input.link_kinds", Layer.SPACE, false, "input", "count", 100, 1, 10_000,
            "Link kinds one expand may name."),
    INPUT_LIST_MEMBERS("input.list_members", Layer.SPACE, false, "input", "count", 5_000, 1, 500_000,
            "Entity List members one list op may seal."),
    INPUT_PROJECTION_MAPPINGS("input.projection_mappings", Layer.SPACE, false, "input", "count", 16, 1, 1_600,
            "Dataset mappings one projection may name (one query each)."),
    INPUT_REFERENCES("input.references", Layer.SPACE, false, "input", "count", 200, 1, 20_000,
            "External references one Investigation may carry."),
    INPUT_REFERENCE_ID_LENGTH("input.reference_id_length", Layer.SPACE, false, "input", "chars", 256, 1, 25_600,
            "The longest id of an external reference."),
    INPUT_REFERENCE_URL_LENGTH("input.reference_url_length", Layer.SPACE, false, "input", "chars", 2_048, 1, 204_800,
            "The longest URL of an external reference."),
    INPUT_REFERENCE_LABEL_LENGTH("input.reference_label_length", Layer.SPACE, false, "input", "chars", 200, 1, 20_000,
            "The longest label of an external reference or an embed link."),
    INPUT_DRAFT_LABEL_LENGTH("input.draft_label_length", Layer.SPACE, false, "input", "chars", 80, 1, 8_000,
            "The longest label of a Draft."),
    INPUT_EMBED_ACTIVE_GRANTS("input.embed_active_grants", Layer.SPACE, false, "input", "count", 20, 1, 2_000,
            "Embed links one Investigation holds live at once."),
    INPUT_ENTITY_TYPES("input.entity_types", Layer.SPACE, false, "input", "count", 64, 1, 6_400,
            "Entity Types one Space may declare."),
    INPUT_RECURRING_EXCLUDES("input.recurring_excludes", Layer.SPACE, false, "input", "count", 32, 1, 3_200,
            "Recurring exclusions one Investigation time window may carry."),
    INPUT_EXCLUDES("input.excludes", Layer.SPACE, false, "input", "count", 366, 1, 36_600,
            "Excluded dates one Investigation time window may carry."),
    INPUT_IDENTITY_KEY_LENGTH("input.identity_key_length", Layer.SPACE, false, "input", "chars", 512, 1, 51_200,
            "The longest key of an asserted identity."),

    // ── index (SPACE) ────────────────────────────────────────────────────────────────────────────────────────
    INDEX_MAX_DISK_BYTES("index.max_disk_bytes", Layer.SPACE, false, "index", "bytes", 0L, 0L, 1_000_000_000_000_000L,
            "An index build whose estimate is above this is refused up front; 0 = no limit."),
    INDEX_KEEP_VERSIONS("index.keep_versions", Layer.SPACE, false, "index", "count", 2, 1, 100,
            "Published versions kept per index."),
    INDEX_MAX_AGE_HOURS("index.max_age_hours", Layer.SPACE, false, "index", "h", 24, 1, 8_760,
            "How old an index may be and still serve a Dataset whose input files cannot be fingerprinted."),
    INDEX_TRAVERSAL_DEPTH("index.traversal_depth", Layer.SPACE, false, "index", "hops", 2, 1, 10,
            "The deepest traversal an index answers; a deeper one reads the Dataset."),
    INDEX_FRONTIER_CAP("index.frontier_cap", Layer.SPACE, false, "index", "count", 20, 1, 2_000,
            "Frontier keys per level an indexed traversal or expand reads."),
    INDEX_MAX_DELTAS("index.max_deltas", Layer.SPACE, false, "index", "count", 8, 1, 100,
            "Delta layers an index carries before a full rebuild."),

    // ── graph run (SPACE: the default budget of a run that states none) ───────────────────────────────────────
    GRAPH_RUN_MAX_NODES("graph_run.max_nodes", Layer.SPACE, false, "graph_run", "nodes", 50_000, 1, 10_000_000,
            "Nodes a server-side graph run may load when it states no budget (clamped to the install ceiling)."),
    GRAPH_RUN_MAX_EDGES("graph_run.max_edges", Layer.SPACE, false, "graph_run", "edges", 500_000, 1, 10_000_000,
            "Edges a server-side graph run may load when it states no budget (clamped to the install ceiling)."),
    GRAPH_RUN_TIMEOUT_MS("graph_run.timeout_ms", Layer.SPACE, false, "graph_run", "ms", 30_000, 1, 10_000_000,
            "Time a server-side graph run may take when it states no budget (clamped to the install ceiling)."),
    GRAPH_RUN_MAX_RESULT_ITEMS("graph_run.max_result_items", Layer.SPACE, false, "graph_run", "count", 10_000, 1, 10_000_000,
            "Items each list of a graph run's result returns (never above 1,000,000)."),

    // ── drafts, embed (SPACE) ────────────────────────────────────────────────────────────────────────────────
    DRAFTS_MAX_OPEN("drafts.max_open", Layer.SPACE, false, "drafts", "count", 50, 1, 1_000,
            "Open Drafts one Space may hold."),
    DRAFTS_HIBERNATE_AFTER_MINUTES("drafts.hibernate_after_minutes", Layer.SPACE, false, "drafts", "min", 60, 1, 10_080,
            "Idle minutes before a Draft hibernates."),
    DRAFTS_EXPIRE_AFTER_DAYS("drafts.expire_after_days", Layer.SPACE, false, "drafts", "days", 30, 1, 3_650,
            "Idle days before a Draft expires; always longer than the hibernation."),
    EMBED_DEFAULT_TTL_HOURS("embed.default_ttl_hours", Layer.SPACE, false, "embed", "h", 72, 1, 8_760,
            "Lifetime of an embed link whose mint states none; never above the longest lifetime."),
    EMBED_MAX_TTL_HOURS("embed.max_ttl_hours", Layer.SPACE, false, "embed", "h", 720, 1, 8_760,
            "The longest an embed link may live; a longer request is refused."),

    // ── INSTALL (system admin; P4 wires them) ────────────────────────────────────────────────────────────────
    GRAPH_RUN_CEILING_NODES("graph_run.ceiling_nodes", Layer.INSTALL, false, "graph_run", "nodes", 500_000, 1, 100_000_000,
            "The hard ceiling on nodes any server-side graph run may load."),
    GRAPH_RUN_CEILING_EDGES("graph_run.ceiling_edges", Layer.INSTALL, false, "graph_run", "edges", 5_000_000, 1, 1_000_000_000,
            "The hard ceiling on edges any server-side graph run may load."),
    GRAPH_RUN_CEILING_TIMEOUT_MS("graph_run.ceiling_timeout_ms", Layer.INSTALL, false, "graph_run", "ms", 300_000, 1, 86_400_000,
            "The hard ceiling on the time any server-side graph run may take."),
    GRAPH_RUN_THREADS("graph_run.threads", Layer.INSTALL, false, "graph_run", "count", 2, 1, 64,
            "Graph run workers."),
    GRAPH_RUN_QUEUE("graph_run.queue", Layer.INSTALL, false, "graph_run", "count", 16, 1, 1_000,
            "Graph runs that may wait for a worker."),
    INDEX_THREADS("index.threads", Layer.INSTALL, false, "index", "count", 1, 1, 64,
            "Index build workers."),
    INDEX_QUEUE("index.queue", Layer.INSTALL, false, "index", "count", 4, 1, 1_000,
            "Index builds that may wait for a worker."),
    INDEX_BUILD_MEMORY_PCT("index.build_memory_pct", Layer.INSTALL, false, "index", "pct_ram", 80, 1, 100,
            "Memory an index build may use, as a share of the host's RAM; a build waits while ingest runs, so the two never overlap."),
    INDEX_EDGES_PER_BUCKET("index.edges_per_bucket", Layer.INSTALL, false, "index", "edges", 4_000_000, 1_000, 1_000_000_000,
            "Edges per index bucket; a change forces full rebuilds."),
    INDEX_MIN_BUCKETS("index.min_buckets", Layer.INSTALL, false, "index", "count", 16, 1, 1_024,
            "The fewest buckets an index is split into; a change forces full rebuilds."),
    INDEX_MAX_BUCKETS("index.max_buckets", Layer.INSTALL, false, "index", "count", 1_024, 1, 65_536,
            "The most buckets an index is split into; a change forces full rebuilds."),
    RATE_LINK_ANALYSIS_BURST("rate.link_analysis_burst", Layer.INSTALL, false, "rate", "count", 30, 1, 3_000,
            "Expensive Link Analysis requests one user may make in a burst."),
    RATE_LINK_ANALYSIS_PER_S("rate.link_analysis_per_s", Layer.INSTALL, false, "rate", "per_s", 0.5, 0.01, 1_000,
            "Expensive Link Analysis requests per second one user regains after a burst."),
    RATE_EMBED_BURST("rate.embed_burst", Layer.INSTALL, false, "rate", "count", 60, 1, 6_000,
            "Requests one embed link may serve in a burst."),
    RATE_EMBED_PER_S("rate.embed_per_s", Layer.INSTALL, false, "rate", "per_s", 2, 0.01, 1_000,
            "Requests per second one embed link regains after a burst."),
    TRAVERSAL_MEMORY_LIMIT_MB("traversal.memory_limit_mb", Layer.INSTALL, false, "traversal", "mb", 1_024, 64, 1_048_576,
            "Memory one sandboxed traversal query may use."),
    TRAVERSAL_THREADS("traversal.threads", Layer.INSTALL, false, "traversal", "count", 4, 1, 64,
            "Threads one recursive path search may use."),
    DRAFTS_HEAVY_JOBS("drafts.heavy_jobs", Layer.INSTALL, false, "drafts", "count",
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3)), 1, 64,
            "Heavy Draft jobs (replays, Working Set builds, expands) that may run at once; default min(4, cores / 3).");

    /** Where an item is stored and who may set it: the Space admin ({@code link-analysis.toon}) or the system admin ({@code la-limits.toon}). */
    public enum Layer {
        SPACE, INSTALL;

        /** The wire form: {@code space} | {@code install}. */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The shipped default of a {@code browser_algorithms.<algorithmId>} item whose algorithm measured no lower ceiling (CL-3). */
    static final int BROWSER_ALGORITHM_DEFAULT = 2_500;

    /**
     * What the browser does with a {@code browser_algorithms.<algorithmId>} item, which its description states: {@code REFUSE}
     * = the algorithm refuses above it (an open Investigation can run it on the server); {@code HANDOVER} = above it the run
     * goes to the server when an Investigation is open (a selection algorithm never above {@link #ANALYSIS_NODE_CAP}).
     */
    private enum Use { REFUSE, HANDOVER }

    private final String path;
    private final Layer layer;
    private final boolean browser;
    private final String group;
    private final String unit;
    private final Number defaultValue;
    private final double min;
    private final double max;
    private final String description;

    LaLimit(String path, Layer layer, boolean browser, String group, String unit, Number defaultValue, Number min, Number max,
            String description) {
        this.path = path;
        this.layer = layer;
        this.browser = browser;
        this.group = group;
        this.unit = unit;
        this.defaultValue = defaultValue;
        this.min = min.doubleValue();
        this.max = max.doubleValue();
        this.description = description;
    }

    /** A {@code browser_algorithms.<algorithmId>} item with the general default ({@value #BROWSER_ALGORITHM_DEFAULT}). */
    LaLimit(String algorithmId, Use use) {
        this(algorithmId, use, BROWSER_ALGORITHM_DEFAULT);
    }

    /** A {@code browser_algorithms.<algorithmId>} item: SPACE, browser, nodes; {@code measured} = its measured lower ceiling. */
    LaLimit(String algorithmId, Use use, int measured) {
        this("browser_algorithms." + algorithmId, Layer.SPACE, true, "browser_algorithms", "nodes", measured, 1, 100_000,
                switch (use) {
                    case REFUSE -> "Nodes above which the browser refuses to run " + algorithmId
                            + " (an open Investigation can run it on the server).";
                    case HANDOVER -> "Nodes above which " + algorithmId
                            + " runs on the server instead of the browser when an Investigation is open.";
                });
    }

    public String path() { return path; }
    public Layer layer() { return layer; }
    public boolean browser() { return browser; }
    public String group() { return group; }
    public String unit() { return unit; }
    public String description() { return description; }

    /** The shipped default as the wire number ({@link Long}, or {@link Double} for a {@link #decimal} item); {@code null} = none. */
    public Number defaultValue() { return wire(defaultValue); }
    public Number min() { return wire(min); }
    public Number max() { return wire(max); }

    /** Whether the item has no default ("no threshold" / "none"). */
    public boolean nullable() { return defaultValue == null; }

    /** Whether the item takes a fraction (a rate per second); every other item is a whole number. */
    public boolean decimal() { return "per_s".equals(unit); }

    /** Whether {@code v} is a value this item accepts: inside {@code min..max}, and whole unless {@link #decimal}. */
    public boolean accepts(double v) {
        return Double.isFinite(v) && v >= min && v <= max && (decimal() || v == Math.rint(v));
    }

    /**
     * A written value, checked: a JSON number this item {@link #accepts}, as its wire number; {@code null} stays
     * {@code null} (= clear, inherit the default).
     *
     * @throws IllegalArgumentException naming the key and the range when the value is not acceptable
     */
    public Number parse(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Number n) || !accepts(n.doubleValue()))
            throw new IllegalArgumentException("'" + path + "' must be " + (decimal() ? "a number" : "a whole number")
                    + " in " + fmt(min) + ".." + fmt(max) + " (" + unit + "), got " + raw);
        return wire(n.doubleValue());
    }

    /**
     * The value in force for a stated value: {@code stated} when this item {@link #accepts} it, else the default. The
     * one rule {@code LinkAnalysisSettings}' {@code *InForce} accessors and {@link LaLimits} both apply.
     */
    public long resolve(Number stated) {
        if (stated != null && accepts(stated.doubleValue())) return stated.longValue();
        if (defaultValue == null) throw new IllegalStateException("'" + path + "' has no default; read it as nullable");
        return defaultValue.longValue();
    }

    private static final Map<String, LaLimit> BY_PATH = new HashMap<>();

    static {
        for (LaLimit l : values()) BY_PATH.put(l.path, l);
    }

    /** The item stored at {@code path} (the wire key), if any. */
    public static Optional<LaLimit> byPath(String path) {
        return Optional.ofNullable(BY_PATH.get(path));
    }

    private Number wire(Number v) {
        if (v == null) return null;
        return decimal() ? (Number) v.doubleValue() : (Number) v.longValue();
    }

    private String fmt(double v) {
        return decimal() ? String.valueOf(v) : String.valueOf((long) v);
    }
}
