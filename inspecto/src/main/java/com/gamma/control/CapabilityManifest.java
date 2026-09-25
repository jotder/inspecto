package com.gamma.control;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The declared capability → route table (RBAC R4, {@code docs/superpower/rbac-abac-plan.md} §3):
 * every {@code ApiContext.withCapability} gate in the control plane, as data. This is the audit
 * surface the scattered per-route gates lacked — {@code CapabilityManifestTest} asserts this table
 * and the actual registration sites match <b>exactly</b> (both directions), so adding, removing, or
 * re-gating a route without updating the manifest fails the build. It also feeds
 * {@link Roles#KNOWN_CAPABILITIES} (the 422 vocabulary for {@code roles.toon} and Access-Catalog
 * action nodes) and, under R2, the Access Catalog's action-node binding.
 *
 * <p>Keep entries grouped by route class, in registration order — the test reports diffs by entry.
 */
final class CapabilityManifest {
    private CapabilityManifest() {}

    record Entry(String method, String pattern, String capability) {}

    static final List<Entry> ENTRIES = List.of(
            // Incident creation (operator, 2026-09-16) — one act, one capability, three routes
            new Entry("POST", "/recon/promote", Roles.CAN_MANAGE_INCIDENTS),
            new Entry("POST", "/objects", Roles.CAN_MANAGE_INCIDENTS),
            // ObjectRoutes (LA-CASE-CREATE-IN-PLACE-1) — opens Incidents minted from Entities and a Case
            new Entry("POST", "/cases/from-entities", Roles.CAN_MANAGE_INCIDENTS),
            // InvRoutes (LA-03) — sealing evidence and attaching it to a Case is Case work. The other
            // /inv/* POSTs are exempted as read-shaped; these two persist, so they are gated instead.
            new Entry("POST", "/inv/snapshots", Roles.CAN_MANAGE_INCIDENTS),
            new Entry("POST", "/inv/snapshots/attach", Roles.CAN_MANAGE_INCIDENTS),
            // InvestigationRoutes (LA-10) — the op log is evidence, so appending to it is Case work like sealing
            // a snapshot. /replay persists nothing and is exempted as read-shaped below.
            new Entry("POST", "/inv/investigations", Roles.CAN_MANAGE_INCIDENTS),
            new Entry("POST", "/inv/investigations/([^/]+)/ops", Roles.CAN_MANAGE_INCIDENTS),
            new Entry("POST", "/inv/investigations/([^/]+)/undo", Roles.CAN_MANAGE_INCIDENTS),
            new Entry("POST", "/inv/investigations/([^/]+)/reorder", Roles.CAN_MANAGE_INCIDENTS),
            // InvestigationTemplateRoutes (LA-23) — saving a method and instantiating one into a new Investigation
            // write the same store as the op log, so they are Case work like appending to it.
            new Entry("POST", "/inv/investigations/([^/]+)/template", Roles.CAN_MANAGE_INCIDENTS),
            new Entry("POST", "/inv/investigation-templates/([^/]+)/instantiate", Roles.CAN_MANAGE_INCIDENTS),
            // InvestigationMeasureRoutes (LA-23) — binding an Alert Rule arms it, so it is alert authoring, as
            // POST /alerts/rules is; the Investigation's owner-only / PDP gate applies on top.
            new Entry("POST", "/inv/investigations/([^/]+)/alert-rules", Roles.CAN_AUTHOR_ALERT_RULES),
            // InvestigationRoutes, LA-19 controls (operator 2026-09-24) — revealing a masked entity (D-U6) and
            // deciding a pending sensitive expand (D-U7, four-eyes) are oversight acts with their own capabilities.
            new Entry("POST", "/inv/investigations/([^/]+)/reveal", Roles.CAN_REVEAL_LINK_ENTITIES),
            new Entry("POST", "/inv/investigations/([^/]+)/pending/([^/]+)/approve", Roles.CAN_APPROVE_LINK_EXPANSIONS),
            new Entry("POST", "/inv/investigations/([^/]+)/pending/([^/]+)/deny", Roles.CAN_APPROVE_LINK_EXPANSIONS),
            // AccessRoutes
            new Entry("PUT", "/access/roles", Roles.CAN_CONFIGURE_ACCESS),
            new Entry("PUT", "/access/policies", Roles.CAN_CONFIGURE_ACCESS),
            new Entry("POST", "/access/policies/preview", Roles.CAN_CONFIGURE_ACCESS),
            new Entry("PUT", "/access/catalog", Roles.CAN_CONFIGURE_ACCESS),
            new Entry("PUT", "/access/profiles/([^/]+)", Roles.CAN_CONFIGURE_ACCESS),
            new Entry("DELETE", "/access/profiles/([^/]+)", Roles.CAN_CONFIGURE_ACCESS),
            // AcquisitionRoutes
            new Entry("POST", "/collectors/([^/]+)/notify", Roles.CAN_OPERATE_RUNS),
            // AlertRoutes
            new Entry("POST", "/alerts/evaluate", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/alerts/rules", Roles.CAN_AUTHOR_ALERT_RULES),
            new Entry("PUT", "/alerts/rules/([^/]+)", Roles.CAN_AUTHOR_ALERT_RULES),
            new Entry("DELETE", "/alerts/rules/([^/]+)", Roles.CAN_AUTHOR_ALERT_RULES),
            // AssistRoutes — gated 2026-09-15 (`ROUTE-UNGATED-DEFAULT-1`, grounded). ⛔ A PAIR: the settings
            // are server-wide and /test makes a real outbound call to whatever baseUrl was last saved, so
            // gating either alone leaves the other as the injection point or the trigger. Live in every
            // Standard/Enterprise bundle — inspecto-agent IS staged, unlike inspecto-intelligence.
            new Entry("POST", "/assist/settings", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/assist/settings/test", Roles.CAN_AUTHOR_WORKBENCH),
            // BiRoutes
            new Entry("POST", "/bi/templates/([^/]+)/apply", Roles.CAN_AUTHOR_WORKBENCH),
            // BundleRoutes
            new Entry("POST", "/bundle/import", Roles.CAN_AUTHOR_WORKBENCH),
            // ComponentRoutes — the findings-spec kind is Case-desk configuration (D1 = (b), operator 2026-09-25)
            new Entry("POST", "/components/findings-spec", Roles.CAN_MANAGE_INCIDENTS),
            new Entry("PUT", "/components/findings-spec/([^/]+)", Roles.CAN_MANAGE_INCIDENTS),
            new Entry("DELETE", "/components/findings-spec/([^/]+)", Roles.CAN_MANAGE_INCIDENTS),
            new Entry("POST", "/components/findings-spec/([^/]+)/versions/([^/]+)/restore", Roles.CAN_MANAGE_INCIDENTS),
            new Entry("POST", "/components/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/components/([^/]+)/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/components/([^/]+)/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/components/([^/]+)/([^/]+)/versions/([^/]+)/restore", Roles.CAN_AUTHOR_WORKBENCH),
            // ConfigRoutes
            new Entry("POST", "/config/write", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/config/patch", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/config/([^/]+)/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            // ConnectionRoutes
            new Entry("POST", "/connections", Roles.CAN_ONBOARD_CONNECTIONS),
            new Entry("POST", "/connections/([^/]+)/test", Roles.CAN_ONBOARD_CONNECTIONS),
            new Entry("POST", "/connections/([^/]+)/probe", Roles.CAN_ONBOARD_CONNECTIONS),
            new Entry("POST", "/connections/test", Roles.CAN_ONBOARD_CONNECTIONS),
            new Entry("PUT", "/connections/([^/]+)", Roles.CAN_ONBOARD_CONNECTIONS),
            new Entry("DELETE", "/connections/([^/]+)", Roles.CAN_ONBOARD_CONNECTIONS),
            // DataSourceRoutes — gated 2026-09-15 (`ROUTE-UNGATED-DEFAULT-1`, grounded). Import writes config
            // and hot-registers what it unpacked, exactly like /bundle/import and /pipelines/import above and
            // below; it was the one of the three left open, with no comment defending it and no test at all.
            new Entry("POST", "/import", Roles.CAN_AUTHOR_WORKBENCH),
            // DatasetRoutes — materializing writes DATA, never config, so it is an operation and not an
            // authoring write: the same capability as any job trigger, and the same effect was already
            // reachable by triggering a `maintenance` job with `task: materialize`.
            new Entry("POST", "/datasets/([^/]+)/materialize", Roles.CAN_OPERATE_RUNS),
            // DecisionRoutes
            new Entry("POST", "/decision-rules", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/decision-rules/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/decision-rules/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/decision-rules/([^/]+)/simulate", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/decision-rules/([^/]+)/apply", Roles.CAN_OPERATE_RUNS),
            // RuleRoutes — read-only preview of a saved rule template, so it mirrors decision-rule
            // `simulate`'s gate rather than `apply`'s. There is no `apply` sibling: running a rule
            // template IS the whole operation, it has no consequences to enact.
            new Entry("POST", "/rule-templates/([^/]+)/simulate", Roles.CAN_AUTHOR_WORKBENCH),
            // EnrichmentRoutes
            new Entry("POST", "/enrichment", Roles.CAN_AUTHOR_WORKBENCH),
            // EventRoutes (inspecto-events) — gated 2026-09-15 (`ROUTE-UNGATED-DEFAULT-1`, grounded). A saved
            // view is server-wide (SavedView carries no subject; one store per service), so writing or deleting
            // one is authoring, not a personal convenience. ⚠ The delete is a POST-shaped DELETE. This file had
            // no gate of any kind before — a whole route class the 2026-09-15 sweep never opened.
            new Entry("POST", "/events/views", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/events/views/([^/]+)/delete", Roles.CAN_AUTHOR_WORKBENCH),
            // ExchangeRoutes
            new Entry("POST", "/exchange/offers", Roles.CAN_OFFER_DATASETS),
            new Entry("POST", "/exchange/refresh", Roles.CAN_OFFER_DATASETS),
            new Entry("POST", "/exchange/requests", Roles.CAN_REQUEST_SHARES),
            new Entry("POST", "/exchange/grants/([^/]+)/(approve|deny|revoke)", Roles.CAN_APPROVE_SHARES),
            new Entry("POST", "/exchange/grants/([^/]+)/pin", Roles.CAN_REQUEST_SHARES),
            new Entry("POST", "/exchange/grants/([^/]+)/expiry", Roles.CAN_APPROVE_SHARES),
            // ExpectationRoutes
            new Entry("POST", "/expectations", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/expectations/evaluate", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/expectations/([^/]+)/evaluate", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/expectations/([^/]+)/baseline/accept", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/expectations/([^/]+)/baseline/clear", Roles.CAN_OPERATE_RUNS),

            new Entry("PUT", "/expectations/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/expectations/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            // JobRoutes
            new Entry("POST", "/jobs", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/jobs/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/jobs/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/jobs/packs/rescan", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/jobs/([^/]+)/enable", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/jobs/([^/]+)/disable", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/jobs/([^/]+)/reschedule", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/jobs/([^/]+)/trigger", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/jobs/runs/([^/]+)/replay", Roles.CAN_OPERATE_RUNS),
            // NavRoutes
            new Entry("PUT", "/nav/menus", Roles.CAN_CURATE_MENUS),
            // NotificationRoutes
            new Entry("POST", "/notifications/channels", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/notifications/channels/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/notifications/channels/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/notifications/rules", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/notifications/rules/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/notifications/rules/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            // The archive ("delete") and the deployment-default preference grid are ONE shared state —
            // NotificationStore and NotificationPreferences carry no recipient — so these change what EVERY
            // user sees and receives. Both were exempt as "self-service" (the caller's own) until SEC review F2,
            // 2026-09-24. ⛔ Re-exempt a write only once its store is keyed by Subject. (The READ state was,
            // 2026-09-25 — NotificationReadState — and so is the caller's own preference override
            // (NotificationPreferenceOverrides, ses-sns §7): those are self-service EXEMPTIONS below.)
            new Entry("PUT", "/notifications/preferences/default", Roles.CAN_ADMINISTER),
            new Entry("DELETE", "/notifications/(?!suppressions$)([^/]+)", Roles.CAN_ADMINISTER),
            // DeliveryStatusRoutes — the read surface only; the inbound callback authenticates itself
            // by provider signature and is deliberately outside the capability spine (D8 §4.4).
            new Entry("GET", "/notifications/deliveries", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("GET", "/notifications/suppressions", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/notifications/suppressions", Roles.CAN_AUTHOR_WORKBENCH),
            // ObjectRoutes
            new Entry("POST", "/cases/rules", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/cases/rules/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            // ObjectRoutes — Incident/Case TRIAGE, gated 2026-09-15 (`ROUTE-UNGATED-DEFAULT-1` step 2b).
            // Operator decision: triage is daily work, but changing an Incident's disposition is
            // administrative — so the state-changing routes take `canAdminister`, and comment / attach /
            // link / RCA-seed stay open as collaboration (see EXEMPTIONS). Case-Rule evaluate opens a Case,
            // which the audit had mis-bucketed as read-shaped. `POST /objects` (create) took `canManageIncidents`
            // on 2026-09-16 (it said PENDING here until 2026-09-17; PENDING_OPERATOR_CALLS has been empty since).
            new Entry("POST", "/objects/([^/]+)/ack", Roles.CAN_ADMINISTER),
            new Entry("POST", "/objects/([^/]+)/resolve", Roles.CAN_ADMINISTER),
            new Entry("POST", "/objects/([^/]+)/transition", Roles.CAN_ADMINISTER),
            new Entry("POST", "/objects/([^/]+)/assign", Roles.CAN_ADMINISTER),
            new Entry("POST", "/objects/([^/]+)/merge", Roles.CAN_ADMINISTER),
            new Entry("POST", "/objects/([^/]+)/split", Roles.CAN_ADMINISTER),
            new Entry("PATCH", "/objects/([^/]+)", Roles.CAN_ADMINISTER),
            new Entry("POST", "/cases/rules/([^/]+)/evaluate", Roles.CAN_ADMINISTER),
            // PipelineRoutes — W5: the graph editor writes the canonical *_pipeline.toon; the
            // *_flow.toon authoring writes (POST/PUT authored, /nodes, /edges) retired. DELETE + the
            // ad-hoc trigger stay for grandfathered flows.
            new Entry("PUT", "/pipelines/([^/]+)/graph", Roles.CAN_AUTHOR_WORKBENCH),
            // PipelineBundleRoutes — TRANSFER-ARCH-1: the selective-bundle import writes config
            // (the export half is a read and carries no gate, like /bundle/export).
            new Entry("POST", "/pipelines/import", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/pipelines/([^/]+)/save-as-template", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/pipelines/([^/]+)/label", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/pipelines/([^/]+)/settings", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/pipelines/([^/]+)/rename", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/pipelines/rename/resume", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/pipelines/authored/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            // Run-to-here is a SIMULATE, not an operate: it writes only to a scratch root and never
            // fires a production run — hence author, unlike its /trigger sibling below.
            new Entry("POST", "/pipelines/authored/([^/]+)/run", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/pipelines/authored/([^/]+)/trigger", Roles.CAN_OPERATE_RUNS),
            // PipelineHistoryRoutes — restoring a config version is a save (PIPELINE-CONFIG-HISTORY-1);
            // the list/read/diff reads carry no gate, like GET /pipelines/{name}/graph/raw.
            new Entry("POST", "/pipelines/([^/]+)/history/([^/]+)/restore", Roles.CAN_AUTHOR_WORKBENCH),
            // RequirementRoutes
            // ⛔ `POST /requirements` is deliberately NOT here. The route-gating audit called it "an
            // inconsistency, not a judgement call" because its two siblings are gated — that is WRONG, and
            // grounding it cost a red build: SEC-7(c) makes submission open on purpose (anyone may raise a
            // requirement; only a triager decides), pinned by
            // ControlApiRequirementTest.triageIsGatedButSubmissionIsOpen.
            new Entry("POST", "/requirements/([^/]+)/decision", Roles.CAN_TRIAGE_REQUIREMENTS),
            new Entry("POST", "/requirements/([^/]+)/deliver", Roles.CAN_TRIAGE_REQUIREMENTS),
            // AgentRoutes — agent GOVERNANCE, gated 2026-09-15 (`ROUTE-UNGATED-DEFAULT-1`). These decide
            // what the assistant is allowed to do and who signed off on it: the autonomy policy, the kill
            // switch, the approval decision that releases a mutating action, and the Case feedback that
            // feeds per-skill tuning. ⚠ Installation policy, not day-to-day work — which is why they take
            // the coarse capability without the product question the Incident-triage family needs.
            // ⚠ Reachability caveat: `/agent/*` answers 503 in every bundle today (no packaging stages
            // `inspecto-intelligence`), so this gate is correct-but-unreached until that changes.
            new Entry("POST", "/agent/triage-runs/(.+)/feedback", Roles.CAN_ADMINISTER),
            new Entry("POST", "/agent/approvals/(.+)/decision", Roles.CAN_ADMINISTER),
            new Entry("PUT", "/agent/policy", Roles.CAN_ADMINISTER),
            new Entry("POST", "/agent/policy/kill-switch", Roles.CAN_ADMINISTER),
            // SpaceRoutes — installation administration, gated 2026-09-15 (`ROUTE-UNGATED-DEFAULT-1`).
            // ⛔ These were reachable by ANY authenticated caller: `DELETE /spaces/{id}` checked only that
            // more than one Space existed. They were not merely un-gated but INEXPRESSIBLE — no capability
            // meant "administrator" until `canAdminister`.
            // ⚠ `POST /spaces` / `/spaces/import` carry NO withCapability wrapper: the gate is IN the handler,
            // conditional on the container hosting at least one Space (SpaceRoutes.requireAdministerUnlessRecovering).
            // They stay in EXEMPTIONS as `recovery-route` because the manifest can express only all-or-nothing.
            new Entry("PUT", "/spaces/([^/]+)", Roles.CAN_ADMINISTER),
            new Entry("DELETE", "/spaces/([^/]+)", Roles.CAN_ADMINISTER),
            // SpaceComparisonRoutes — a cross-Space AGGREGATE read (space-comparison design §5 Q2, decided
            // 2026-09-24): it crosses the Space isolation "reads are open" rests on, so it is administration,
            // not an open read. The route resolves the named Spaces only after this gate passes.
            new Entry("POST", "/space-comparisons", Roles.CAN_ADMINISTER),
            // RunRoutes
            new Entry("POST", "/runs", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/runs/([^/]+)/trigger", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/runs/([^/]+)/pause", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/runs/([^/]+)/resume", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/runs/([^/]+)/reprocess", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/runs/([^/]+)/replay-rejects", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/runs/([^/]+)/retries/retry-now", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/runs/([^/]+)/retries/cancel", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/runs/([^/]+)/drain", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/trigger", Roles.CAN_OPERATE_RUNS),
            // SettingsRoutes
            new Entry("PUT", "/settings/branding", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/settings/geo", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/settings/link-analysis", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/settings/pipeline-history", Roles.CAN_AUTHOR_WORKBENCH),
            // SchedulerRoutes — tuning the live Consignment concurrency caps is runtime operation,
            // not workbench authoring.
            new Entry("PUT", "/system/scheduler", Roles.CAN_OPERATE_RUNS),
            new Entry("PUT", "/settings/scheduler", Roles.CAN_OPERATE_RUNS),
            new Entry("PUT", "/config/icon-map", Roles.CAN_AUTHOR_WORKBENCH),
            // ShareRoutes
            new Entry("POST", "/dashboards/([^/]+)/share", Roles.CAN_AUTHOR_WORKBENCH),
            // SystemRoutes — infrastructure diagnostics, so administrative rather than authoring. Both
            // are reads (the test opens a connection and writes nothing), gated because what they report
            // is credential-adjacent and because an endpoint that dials a URL is not for everyone.
            new Entry("GET", "/system/operational-db", Roles.CAN_CONFIGURE_ACCESS),
            new Entry("POST", "/system/operational-db/test", Roles.CAN_CONFIGURE_ACCESS),
            // TagRoutes
            new Entry("POST", "/tags", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/tags/([^/]+)/rename", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/tags/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/tags/rules", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/tags/rules/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH));

    /**
     * A MUTATING route that is deliberately ungated, with the reviewed reason (route-gating compliance plan
     * step 2c, 2026-09-15). {@code category} is the audit's own bucket taxonomy — nothing invented:
     * {@code identity-flow} · {@code self-verifying-public} · {@code self-service} · {@code read-shaped} ·
     * {@code self-limiting} · {@code recovery-route} · {@code target-visibility-gated} ·
     * {@code stateless-compute} · {@code collaboration} · {@code provenance-gated} (gated in the handler by where the
     * addressed component came from — a Job Pack parser, operator D4 2026-09-25). Together with {@link #ENTRIES} and
     * {@link #PENDING_OPERATOR_CALLS} this makes "ungated" a RECORDED state rather than an absence:
     * {@code CapabilityManifestTest} scans every {@code api.post|put|patch|delete} registration in every
     * module and fails the build on a mutating route that is in none of the three tables.
     *
     * <p>⛔ Reads (GET) are open by design and are not listed — confidentiality sits at the Space/ABAC
     * layer, not at capability gating (operator, 2026-09-15). ⛔ The 503 stubs an {@code Absent*Routes}
     * class registers for a missing optional module go through {@code api.stub}, not these methods, so
     * they are exempt by construction ({@code absent-module-stub}).
     */
    record Exemption(String method, String pattern, String category, String reason) {}

    /**
     * A mutating route whose posture is an OPEN OPERATOR CALL (compliance plan step 2a). Recorded here so it is
     * a visible third state, not a silent one — ⛔ step 3's fail-closed default cannot land while this list is
     * non-empty, and the test pins the list so it can only shrink.
     */
    record Pending(String method, String pattern, String question) {}

    static final List<Exemption> EXEMPTIONS = List.of(
            // §1 identity flow — these ARE the login; a capability gate here is circular.
            new Exemption("POST", "/auth/exchange", "identity-flow", "mints the session; nothing to be authorised by yet"),
            new Exemption("POST", "/auth/refresh", "identity-flow", "rotates the session the caller already holds"),
            new Exemption("POST", "/auth/logout", "identity-flow", "ends the caller's own session"),
            // §2 self-verifying public — authenticated by something other than a Subject.
            new Exemption("POST", "/public/delivery-status/([^/]+)", "self-verifying-public", "inbound provider callback, verified by provider signature (D8 §4.4)"),
            new Exemption("POST", "/public/dashboards/([^/]+)/query", "self-verifying-public", "the share token IS the credential; scoped to one published dashboard"),
            // §3 self-service — acts only the caller's own. ⚠ The four /notifications feed/preference writes
            // were listed here as "the caller's own" but wrote ONE shared per-Space state; they were gated
            // canAdminister since SEC review F2 (2026-09-24). Verify a write is Subject-scoped IN THE STORE
            // before listing it here — the three read-state writes below are, since 2026-09-25 (operator), and so
            // is the preference write (ses-sns §7, 2026-09-25): the deployment default moved to its own
            // canAdminister route, PUT /notifications/preferences/default.
            new Exemption("POST", "/requirements", "self-service", "SEC-7(c): anyone may raise a requirement, only a triager decides — pinned by ControlApiRequirementTest.triageIsGatedButSubmissionIsOpen"),
            new Exemption("POST", "/notifications/read-all", "self-service", "marks the feed read in the CALLER's own NotificationReadState only — pinned by ControlApiNotificationsTest.readStateIsPerSubject"),
            new Exemption("POST", "/notifications/([^/]+)/read", "self-service", "marks one notification read in the CALLER's own NotificationReadState only — pinned by ControlApiNotificationsTest.readStateIsPerSubject"),
            new Exemption("POST", "/notifications/([^/]+)/unread", "self-service", "marks one notification unread in the CALLER's own NotificationReadState only — pinned by ControlApiNotificationsTest.readStateIsPerSubject"),
            new Exemption("PUT", "/notifications/preferences", "self-service", "writes the CALLER's own override in NotificationPreferenceOverrides, keyed by Subject id (the default is PUT /notifications/preferences/default, canAdminister); on Personal there is one user and it writes the single grid — pinned by ControlApiSubjectPreferencesTest.oneSubjectsPutNeverChangesAnotherSubjectsEffectiveGrid"),
            // §4 read-shaped POST — a POST because the request carries a body, persists nothing. Reads are
            // open by design, so these are exempt AS READS (operator, 2026-09-15) — not "deferred".
            new Exemption("POST", "/components/transform/([^/]+)/test", "read-shaped", "dry-runs a saved component against sample rows"),
            new Exemption("POST", "/components/grammar/([^/]+)/test", "read-shaped", "dry-runs a saved component against sample rows"),
            new Exemption("POST", "/components/sink/([^/]+)/test", "read-shaped", "dry-runs a saved component against sample rows"),
            new Exemption("POST", "/components/transform/preview", "read-shaped", "previews an unsaved draft"),
            new Exemption("POST", "/components/grammar/preview", "read-shaped", "previews an unsaved draft"),
            new Exemption("POST", "/components/sink/preview", "read-shaped", "previews an unsaved draft"),
            new Exemption("POST", "/components/mapping/validate", "read-shaped", "validates a draft, writes nothing"),
            new Exemption("POST", "/components/transform/describe", "read-shaped", "describes a draft's derived shape"),
            new Exemption("POST", "/components/sql/ast", "read-shaped", "parses author SQL into DuckDB's read-only tree; nothing binds or executes"),
            new Exemption("POST", "/validate", "read-shaped", "validates a config draft, writes nothing"),
            new Exemption("POST", "/config/preview/parsing", "read-shaped", "previews parsing of a draft"),
            new Exemption("POST", "/config/preview/schema", "read-shaped", "previews a derived schema"),
            new Exemption("POST", "/config/suggest/schema", "read-shaped", "suggests a schema from a sample"),
            // 2026-09-17: the three connection test/probe routes LEFT this list. "Persists nothing" was true and
            // beside the point — /connections/test dials any host:port in the body from the server's network
            // position, and /{id}/probe reads through a SAVED credential the caller was never granted. They now
            // demand canOnboardConnections, the capability that already guards the same credential's CRUD.
            new Exemption("POST", "/db/query", "read-shaped", "read-only SQL behind SqlGuard"),
            new Exemption("POST", "/bi/query", "read-shaped", "a Measure query; the body is the query spec"),
            new Exemption("POST", "/enrichment/preview", "read-shaped", "previews an enrichment over sample rows"),
            // Operator D4 2026-09-25 (parser-plugins-trust-design.md slice P4): the gate depends on the PARSER, so
            // it lives IN the handler and the manifest (all-or-nothing per route) records it here, as /spaces does.
            new Exemption("POST", "/parsers/([^/]+)/preview", "provenance-gated",
                    "gated IN the handler: canAuthorWorkbench when the parser came from a Job Pack (third-party code over caller-chosen bytes); built-in and classpath parsers stay open as read-shaped (ParserRoutes.preview) — pinned by ControlApiPackParserPreviewTest"),
            new Exemption("POST", "/import/preview", "read-shaped", "previews an import; nothing is written until the gated import"),
            new Exemption("POST", "/bundle/preview", "read-shaped", "previews a bundle's contents"),
            new Exemption("POST", "/bundle/export", "read-shaped", "an export is a read; the import half is gated"),
            new Exemption("POST", "/geo/projection", "read-shaped", "computes a projection from the body"),
            new Exemption("POST", "/geo/routes", "read-shaped", "computes routes from the body"),
            new Exemption("POST", "/inv/projection", "read-shaped", "computes a projection from the body"),
            new Exemption("POST", "/inv/projection/neighbors", "read-shaped", "computes neighbours from the body"),
            new Exemption("POST", "/inv/projection/multi", "read-shaped", "computes a multi-dataset projection from the body; persists nothing"),
            new Exemption("POST", "/inv/schema/overlap-profile", "read-shaped", "profiles column cardinality/overlap; persists nothing"),
            new Exemption("POST", "/inv/traversal/recursive-paths", "read-shaped", "walks paths over a Dataset (LA-11); persists nothing"),
            new Exemption("POST", "/inv/pattern/branching", "read-shaped", "matches a branching motif over a Dataset (LA-14b); persists nothing"),
            new Exemption("POST", "/inv/investigations/([^/]+)/replay", "read-shaped", "re-evaluates a sealed Investigation log (LA-10); persists nothing"),
            new Exemption("POST", "/inv/investigations/([^/]+)/dossier/verify", "read-shaped", "checks a Dossier manifest against the store (LA-12); persists nothing"),
            new Exemption("POST", "/recon/columns", "read-shaped", "lists comparable columns for a draft"),
            new Exemption("POST", "/recon/breaks", "read-shaped", "computes breaks for a draft; persists nothing"),
            new Exemption("POST", "/recon/rows", "read-shaped", "lists the raw rows behind one key (RECON-CARDINALITY-2); persists nothing"),
            new Exemption("POST", "/spaces/import", "recovery-route",
                    "gated IN the handler: canAdminister whenever at least one Space is hosted; open only on an empty container, where recovery needs it (SpaceRoutes.requireAdministerUnlessRecovering, 2026-09-17)"),
            new Exemption("POST", "/tags/rules/([^/]+)/apply", "collaboration",
                    "operator 2026-09-16: applying a tag rule is a collaboration act like assignments, not run operation"),
            new Exemption("POST", "/queries/([^/]+)/run", "read-shaped", "runs a saved read query"),
            new Exemption("POST", "/pipelines/authored/([^/]+)/dry-run", "read-shaped", "a dry run writes nothing (PIPELINE-DRYRUN-1)"),
            // 2026-09-17: the three evaluate routes LEFT this list. They were never read-shaped: an Expectation
            // evaluation persists lastResult, opens Incidents and emits EXPECTATION_FAILED; an Alert evaluation
            // emits ALERT_FIRED — and both events match the DEFAULT NotificationRules, so an ungated caller could
            // page or email through them. They now demand canOperateRuns: the Operations tier keeps checking
            // data quality (the "ops" seed grants it), and nobody below it triggers dispatch.
            // §7 self-limiting — the agent surface gates itself per tool (the assistant refuses mutating
            // tools it was not granted), and the governance routes that decide what it MAY do are gated.
            new Exemption("POST", "/agent/sessions", "self-limiting", "opens a conversation; no tool runs without its own gate"),
            new Exemption("POST", "/agent/sessions/(.+)/ask", "self-limiting", "a question to the assistant; tools self-gate"),
            new Exemption("POST", "/agent/sessions/(.+)/ask/stream", "self-limiting", "streaming form of /ask"),
            new Exemption("POST", "/agent/tools/(.+)", "self-limiting", "a tool call, each tool enforcing its own capability"),
            new Exemption("POST", "/agent/tools/(.+)/derive", "self-limiting", "derives tool arguments; runs nothing"),
            new Exemption("POST", "/assist/(.+)", "self-limiting", "the skill-intent catch-all; dispatch only, the skill's own tools gate"),
            // grounded one at a time 2026-09-15 (audit §5 GROUNDED)
            new Exemption("POST", "/spaces", "recovery-route", "gated IN the handler: canAdminister whenever at least one Space is hosted; a server hosting zero Spaces must still answer it without one or recovery is bricked — pinned by ControlApiSpacesTest.authenticatedCreateSucceedsWhenNoSpaceIsHostedYet (2026-09-17: the unconditional form let any authenticated caller create Spaces on a populated server)"),
            new Exemption("POST", "/recon/run", "stateless-compute", "triggers nothing: computes and returns, persists nothing, dispatches no job"),
            new Exemption("POST", "/tags/assignments/([^/]+)/([^/]+)", "target-visibility-gated", "gated per TARGET via AnnotationTargets: 'can tag' must not become independent of 'can see' (TagRoutes)"),
            new Exemption("DELETE", "/tags/assignments/([^/]+)/([^/]+)/([^/]+)", "target-visibility-gated", "same comment as the assignment POST"),
            // Incident/Case triage — the COLLABORATION half (operator decision 2026-09-15): adding to the
            // record is daily work; changing the disposition is administrative and is in ENTRIES.
            new Exemption("POST", "/objects/([^/]+)/comments", "collaboration", "adds a comment; the disposition is untouched"),
            new Exemption("POST", "/objects/([^/]+)/attachments", "collaboration", "attaches evidence"),
            new Exemption("POST", "/objects/([^/]+)/links", "collaboration", "correlates two objects; neither's state changes"),
            new Exemption("DELETE", "/objects/([^/]+)/links", "collaboration", "removes a correlation link"),
            new Exemption("POST", "/objects/([^/]+)/rca", "collaboration", "seeds an RCA skeleton as comments"),
            new Exemption("PUT", "/objects/([^/]+)/findings", "collaboration",
                    "operator 2026-09-25: saving Findings values is open to anyone who can see the Case, like a comment; writes ONLY attributes.findings + its flat copies and refuses every other key (422), so disposition stays on the canAdminister PATCH — pinned by ControlApiFindingsWriteTest"),
            new Exemption("POST", "/notes/([^/]+)/([^/]+)/comments", "collaboration", "adds a comment on any note-bearing object"),
            new Exemption("POST", "/notes/([^/]+)/([^/]+)/attachments", "collaboration", "attaches evidence on any note-bearing object"));

    /**
     * ✅ <b>EMPTY since 2026-09-16 — all four calls were answered in one sitting</b> (route-gating plan §2a):
     * {@code POST /recon/promote} and {@code POST /objects} now share the NEW {@link Roles#CAN_MANAGE_INCIDENTS}
     * (both are the one act of opening an Incident, so neither borrows a neighbouring capability);
     * {@code POST /spaces/import} inherits the {@code POST /spaces} additive/recovery posture and
     * {@code POST /tags/rules/{id}/apply} is a collaboration act like assignments — both recorded as
     * EXEMPTIONS with the decision on the line, not left absent.
     *
     * <p>⛔ Keep this table and its test: "ungated" must stay a RECORDED state. The next unlisted mutating
     * route belongs here, not nowhere.
     */
    static final List<Pending> PENDING_OPERATOR_CALLS = List.of();

    /** The declared capability gating {@code method path}, or null when the route is ungated —
     *  the A3 authorize stage classifies {@code operate} actions off this (a state-changing call
     *  whose gate is {@code canOperateRuns} is an operation, not an authoring write). */
    static String capabilityFor(String method, String path) {
        for (Entry e : ENTRIES)
            if (e.method().equals(method) && path.matches(e.pattern())) return e.capability();
        return null;
    }

    /** The capability vocabulary — every capability some route gate demands. */
    /**
     * Whether {@code (method, pattern)} is a recorded exemption — the lookup {@code ControlApi.register}'s
     * boot check uses. Matching is on the registered pattern STRING, exactly as the route class spells it,
     * so an exemption can never accidentally widen to a route it was not written for.
     */
    static boolean isExempt(String method, String pattern) {
        return exemptionFor(method, pattern) != null;
    }

    /** The recorded exemption for {@code (method, pattern)}, or {@code null} — the inventory needs its
     *  category and reason, not merely the fact that one exists. */
    static Exemption exemptionFor(String method, String pattern) {
        for (Exemption e : EXEMPTIONS)
            if (e.method().equals(method) && e.pattern().equals(pattern)) return e;
        return null;
    }

    static Set<String> capabilities() {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : ENTRIES) out.add(e.capability());
        return out;
    }
}
