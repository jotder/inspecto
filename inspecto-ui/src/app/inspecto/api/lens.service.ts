import { computed, inject, Injectable, signal } from '@angular/core';
import { SessionService } from './session.service';

/** The three persona lenses over the one console (`docs/GLOSSARY.md` §1-A). Not a permission —
 *  under RBAC (Standard, R2) the subject's role grants constrain which lenses are selectable. */
export type Lens = 'business' | 'builder' | 'ops';

export interface LensMeta {
    id: Lens;
    label: string;
    icon: string;
}

const STORAGE_KEY = 'inspecto.currentLens';
const DEFAULT_LENS: Lens = 'builder';

const LENSES: LensMeta[] = [
    { id: 'business', label: 'Business', icon: 'heroicons_outline:briefcase' },
    { id: 'builder', label: 'Builder', icon: 'heroicons_outline:wrench-screwdriver' },
    { id: 'ops', label: 'Ops', icon: 'heroicons_outline:server-stack' },
];

/**
 * Holds the active persona lens as a signal, mirroring {@link SpacesService}'s restore/select shape:
 * restored from `localStorage` synchronously in the constructor, persisted on every change.
 *
 * Per the Wave-1 product-owner decision (`docs/superpower/frontend-review-and-completion-plan.md`
 * §6 Q1, recorded 2026-07-02): the **Business** lens can see every Workbench pane, but every
 * create/edit/delete (authoring) action is hidden — panes stay visible and read-only, they are not
 * removed from navigation.
 *
 * **Capability seam (RBAC groundwork, 2026-07-03; the promised re-derivation landed with RBAC R2,
 * 2026-07-23 — `docs/superpower/rbac-abac-plan.md` §3):** panes gate on the named **capability**
 * signals below, never on `readOnly`/lens identity directly. A Lens is a *view*, not a permission
 * (GLOSSARY §1-A). On Personal (`authMode 'none'`) every capability derives from the self-selected
 * lens — the honor-system preview, byte-identical to before. Under OIDC each capability additionally
 * requires the matching grant in {@link SessionService.capabilities} — the *effective* set
 * `/bootstrap` reports after the backend's role/Access-Profile enforcement — and the "View as"
 * switcher is constrained to the {@link allowedLenses} those grants project onto. Call sites are
 * unchanged, as designed. Add a new named capability per distinct authorization question; don't
 * reuse one because its current value happens to match.
 */
@Injectable({ providedIn: 'root' })
export class LensService {
    /** The three lenses, in display order — {@link allowedLenses} filters this. */
    static readonly LENSES = LENSES;

    private readonly session = inject(SessionService);

    /** The user's chosen ("View as") lens — persisted. May be constrained away by grants: the
     *  active lens is {@link currentLens}, which snaps back here whenever the preference is allowed
     *  again (e.g. an admin restores a revoked role), so a temporary revocation never overwrites
     *  the stored choice. */
    private readonly preferredLens = signal<Lens>(this.restore());

    /** The lenses this subject may view as. Honor system (Personal): all three. Under OIDC the
     *  subject's roles project onto lenses via the effective grants (rbac-groundwork §3 taxonomy):
     *  Builder needs `canAuthorWorkbench`, Ops needs `canOperateRuns`, Business (read-only) is
     *  always available. The switcher iterates this. */
    readonly allowedLenses = computed<LensMeta[]>(() => {
        if (this.session.authMode() !== 'oidc') return LENSES;
        const caps = this.session.capabilities();
        return LENSES.filter(
            (l) =>
                l.id === 'business' ||
                (l.id === 'builder' && caps.includes('canAuthorWorkbench')) ||
                (l.id === 'ops' && caps.includes('canOperateRuns')),
        );
    });

    /** The active lens: the preferred one when allowed, else the most capable allowed lens. */
    readonly currentLens = computed<Lens>(() => {
        const allowed = this.allowedLenses();
        const preferred = this.preferredLens();
        if (allowed.some((l) => l.id === preferred)) return preferred;
        return allowed[allowed.length - 1]?.id ?? 'business';
    });

    /** True while the active lens is the read-only one (Business). Internal derivation for the
     *  **lens-scoped** capabilities below — gate on a capability, not on this.
     *
     *  ⚠ This is a *presentation* flag, never a security boundary. Nothing outside this file reads it;
     *  the real gate is server-side (`CapabilityManifest` + `withCapability`), which is why the Business
     *  lens can safely stop suppressing identity capabilities — see {@link identityCapability}. */
    readonly readOnly = computed(() => this.currentLens() === 'business');

    /** Action-node grants pushed by {@code AccessStateService} once the saved lens Access Profiles
     *  load (`docs/superpower/lens-access-config-design.md` §7 — this is the "one file re-derives
     *  these signals" seam from rbac-groundwork, exercised with lens subjects). `null` = no config
     *  loaded ⇒ every action allowed, exactly the pre-profile behavior. */
    private readonly actionGrants =
        signal<Record<string, Partial<Record<Lens, boolean>>> | null>(null);

    /** Called by {@code AccessStateService} whenever lens Access Profiles (re)load. */
    setActionGrants(grants: Record<string, Partial<Record<Lens, boolean>>> | null): void {
        this.actionGrants.set(grants);
    }

    private allows(actionNodeId: string): boolean {
        return this.actionGrants()?.[actionNodeId]?.[this.currentLens()] ?? true;
    }

    /** Under OIDC, is `cap` among the subject's effective capabilities (`/bootstrap`, post
     *  server-side R2 enforcement)? Honor-system mode grants everything — the lens decides. */
    private granted(cap: string): boolean {
        return this.session.authMode() !== 'oidc' || this.session.capabilities().includes(cap);
    }

    /**
     * An **identity** capability: a property of *who the subject is*, not of what they are currently
     * looking at. It survives every lens, including the read-only Business one — an admin viewing the
     * console through the Business lens is still the admin.
     *
     * The distinction (BACKLOG §5, resolved 2026-07-25). Previously *every* capability carried
     * `!readOnly()`, and {@link allowedLenses} qualifies Builder/Ops only via `canAuthorWorkbench` /
     * `canOperateRuns`. A subject holding neither — notably the whole **admin** seed — was snapped to
     * Business and evaluated *every* capability false client-side while the server authorized the
     * calls. The worst case was a bootstrap deadlock: a fresh OIDC deployment's admin could not author
     * the Access matrix that grants access, because the matrix rendered read-only to them.
     *
     * Safe because `readOnly` guards nothing — see its javadoc. The server is the boundary.
     *
     * ⚠ The lens still suppresses these **in honor-system mode**. The exemption is justified by the
     * subject's *identity*, and off-OIDC there is no identity — {@link granted} short-circuits true for
     * everyone, so the lens is the only signal there is. Without this clause, Personal mode's Business
     * lens would start showing Connections and Requirements affordances, breaking the "View as" preview
     * whose entire job is showing what a business user sees. Fix the OIDC bug; leave the preview alone.
     */
    private identityCapability(cap: string, actionNode: string): boolean {
        const identityKnown = this.session.authMode() === 'oidc';
        return this.granted(cap) && (identityKnown || !this.readOnly()) && this.allows(actionNode);
    }

    /**
     * A **lens-scoped** capability: an *activity* the current lens represents. Legitimately suppressed
     * in the read-only Business view, because the point of that lens is "I am reading, not building".
     */
    private lensCapability(cap: string, actionNode: string): boolean {
        return this.granted(cap) && !this.readOnly() && this.allows(actionNode);
    }

    /** May author in the Workbench (Pipelines / Jobs / Components create-edit-delete). RBAC: Pipeline
     *  Developer, Power user, Super user. (Connection onboarding split out to {@link canOnboardConnections}
     *  2026-07-22 — the credential/egress surface is Admin-owned, not Builder.) */
    readonly canAuthorWorkbench = computed(
        () => this.lensCapability('canAuthorWorkbench', 'workbench.author'));

    /** May onboard/configure Connections (create / edit / delete a connection profile) — its own
     *  authorization question because Connections are the credential + network-egress surface, a worse
     *  blast radius than authoring a pipeline (rbac-groundwork §3/§4.1 Q1, product sign-off 2026-07-22).
     *  RBAC: Admin, Super. In the lens honor-system preview it defaults allowed for the non-Business
     *  lenses, exactly as Workbench authoring did before the split.
     *  {@link identityCapability}: the credential surface is Admin-owned, and admin never qualifies for
     *  a non-Business lens. */
    readonly canOnboardConnections = computed(
        () => this.identityCapability('canOnboardConnections', 'connections.onboard'));

    /** May operate runs (trigger / pause / resume / reprocess) — the plan's "read-only observe"
     *  exception for Business on the Runs pane. RBAC: Operations, Pipeline Developer, Power/Super. */
    readonly canOperateRuns = computed(
        () => this.lensCapability('canOperateRuns', 'runs.operate'));

    /** May triage Requirements (accept / reject / deliver) — the Builder-facing intake queue (C1).
     *  RBAC: Pipeline Developer, Operations, Power/Super — **and the `business` seed role**, whose only
     *  capability this is (`Roles.SEED`, product sign-off 2026-07-24).
     *
     *  {@link identityCapability} by that grant: a business subject is snapped to the Business lens, so
     *  treating triage as lens-scoped revoked the one thing the role was given. Deciding it is identity
     *  (operator call 2026-07-25) means **"Business lens ⇒ read-only" is no longer true of the product**
     *  — intake triage is the deliberate exception. */
    readonly canTriageRequirements = computed(
        () => this.identityCapability('canTriageRequirements', 'requirements.triage'));

    /** May author Alert Rules (create / edit / delete on the Alerts pane — audit C3). A distinct
     *  question from Workbench authoring: monitoring config is Ops-owned. RBAC: Operations,
     *  Power/Super. */
    readonly canAuthorAlertRules = computed(
        () => this.lensCapability('canAuthorAlertRules', 'alerts.author'));

    /** May curate the space's shared Menu tree (Settings ▸ Menu Builder, `PUT /nav/menus`). Split out
     *  of {@link canAuthorWorkbench} 2026-07-25 (BACKLOG D4): a nav change is visible to every business
     *  user in the space and is not a build activity. RBAC: Admin, Power, Super — note this is *not* a
     *  subset of Workbench authoring, so a Pipeline Developer authors freely but no longer re-arranges
     *  everyone's sidebar. {@link identityCapability} — its own rationale says curation "is not a build
     *  activity", so no lens represents it. */
    readonly canCurateMenus = computed(
        () => this.identityCapability('canCurateMenus', 'menus.curate'));

    /** May configure lens access (the Settings ▸ Access matrix). RBAC: Admin, Super.
     *  {@link identityCapability} — and the sharpest case for it: while this was lens-scoped, a fresh
     *  OIDC deployment's admin saw the Access matrix read-only and could not author the roles that
     *  grant access. */
    readonly canConfigureAccess = computed(
        () => this.identityCapability('canConfigureAccess', 'access.configure'));

    /** May offer a dataset or widget into the Exchange (`POST /exchange/offers|refresh`). RBAC: Admin,
     *  Super — `canOfferDatasets` moved Builder/Power → Admin on 2026-07-25 (BACKLOG D14) because
     *  cross-space data exposure has no second gate. {@link identityCapability} for that same reason:
     *  it is Admin-owned, and admin never qualifies for a non-Business lens. */
    readonly canOfferDatasets = computed(
        () => this.identityCapability('canOfferDatasets', 'exchange.offer'));

    /** May decide share requests — approve / deny / revoke a grant and set its expiry
     *  (`POST /exchange/grants/…`). RBAC: Admin, Super. {@link identityCapability}: deciding who may
     *  read another space's data is governance, not an activity any lens represents. */
    readonly canApproveShares = computed(
        () => this.identityCapability('canApproveShares', 'exchange.approve'));

    /** May request access to another Space's offer and pin a consumed snapshot
     *  (`POST /exchange/requests`, `POST /exchange/grants/…/pin`). RBAC: the builder roles, Operations,
     *  Power/Super — deliberately broad, because an owner still has to approve (`Roles.SEED`, D14).
     *
     *  {@link lensCapability}, unlike its two Exchange siblings (operator call 2026-07-26). Every seeded
     *  role holding it also holds `canAuthorWorkbench` or `canOperateRuns`, so it always qualifies for a
     *  non-Business lens and lens-scoping strands nobody — the deadlock that forced the Admin-owned
     *  capabilities to identity cannot arise here. The `business` seed pointedly does *not* hold it, and
     *  "I want this dataset to build with" is exactly an activity a lens represents. */
    readonly canRequestShares = computed(
        () => this.lensCapability('canRequestShares', 'exchange.request'));

    /** Set the preferred lens and persist it across reloads. A lens outside {@link allowedLenses}
     *  is remembered but not activated (the switcher never offers one). */
    selectLens(lens: Lens): void {
        this.preferredLens.set(lens);
        if (typeof localStorage === 'undefined') return;
        localStorage.setItem(STORAGE_KEY, lens);
    }

    private restore(): Lens {
        if (typeof localStorage === 'undefined') return DEFAULT_LENS;
        const saved = localStorage.getItem(STORAGE_KEY);
        return saved === 'business' || saved === 'builder' || saved === 'ops' ? saved : DEFAULT_LENS;
    }
}
