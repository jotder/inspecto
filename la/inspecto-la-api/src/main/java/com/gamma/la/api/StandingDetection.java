package com.gamma.la.api;

import com.gamma.control.ComponentAccess;
import com.gamma.control.EntityTypes;
import com.gamma.control.LinkAnalysisSettings;
import com.gamma.control.RowScope;
import com.gamma.control.Subject;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.InvestigationMembers;
import com.gamma.la.core.InvestigationStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <b>Standing detection authority</b> (LA-LIVE-DETECTION-1, slices LD-1..LD-3,
 * {@code docs/archived-documents/plans-archive/la-live-detection-design.md}; operator decision D-LD1 = option A, 2026-10-04).
 *
 * <p>A scheduled sweep has no caller, so the gates a read passes (R3 sharing, the Enterprise PDP) have nothing to
 * judge. The sweep therefore acts as a service principal {@code sweep:<investigation id>} that holds NO capability of
 * its own. Its authority is the {@link Authority} recorded beside the Alert Rule binding when the owner ENABLED
 * standing detection, and {@link #decide} RE-DECIDES it before every read: the sweep may read the bound Dataset only
 * while the owner, as a user id, still could. Authority is therefore always a subset of the owner's live authority,
 * and anything this class cannot decide is a refusal (the C floor of the design).
 *
 * <p>What is re-decided, in order, each answering a {@link Verdict} code on refusal:
 * <ol>
 *   <li>a recorded owner id exists, and the Investigation still has that owner and the recorded Dataset;</li>
 *   <li>the Dataset still exists, and {@link ComponentAccess#canViewAs} grants the OWNER ID view on its CURRENT
 *       sharing envelope — owner id and {@code user} shares only. A {@code role} share cannot be re-resolved off a
 *       request (roles are stamped at token validation and never stored), so a Dataset whose only grant is a role share
 *       is refused (D-LD2). {@code canConfigureAccess} is never honoured: the hatch is a live capability the sweep cannot
 *       confirm, so it would exceed the owner's live authority;</li>
 *   <li>the owner is still a LEAD of the Investigation;</li>
 *   <li>the masking basis has not TIGHTENED since enable (a higher {@code maskingMode}, a newly masked bound column,
 *       or lineage that can no longer be traced). Loosening never stops a sweep: it reads and returns aggregates only;</li>
 *   <li>the Enterprise PDP, asked as a synthetic Subject built from the recorded owner id, capabilities, data scopes and
 *       IdP attributes ({@link RowScope#visibleAs}), does not DENY the Investigation. ABSTAIN and ALLOW grant nothing.</li>
 * </ol>
 *
 * <p>⚠ Residuals, recorded in the design doc: the synthetic Subject's capabilities, scopes and attributes are the
 * ENABLE-time snapshot (nothing off-request can refresh them), and it carries no role names (guideline 13), so a DENY
 * policy that keys on {@code subject.roles} cannot be reproduced.
 */
public final class StandingDetection {
    private StandingDetection() {}

    /** The key under which the authority rides in the Alert Rule binding record. */
    public static final String KEY = "standing";

    /** Reason codes of a refusal (stable: they ride the audit trail and the response). */
    public static final String NOT_ENABLED = "NOT_ENABLED", NO_OWNER = "NO_OWNER", BINDING_CHANGED = "BINDING_CHANGED",
            DATASET_GONE = "DATASET_GONE", DATASET_NOT_SHARED = "DATASET_NOT_SHARED",
            ROLE_SHARE_ONLY = "ROLE_SHARE_ONLY", NOT_LEAD = "NOT_LEAD", MASKING_TIGHTENED = "MASKING_TIGHTENED",
            POLICY_DENIED = "POLICY_DENIED", UNDECIDABLE = "UNDECIDABLE";

    /** The recorded authority: who the sweep stands in for, and what was in force when it was enabled. */
    public record Authority(String principal, String ownerId, Set<String> capabilities, Set<String> dataScopes,
                            Map<String, Object> attributes, String dataset, Map<String, Object> masking,
                            String enabledBy, String enabledAt) {

        /** Canonical map form (JSON-safe), the value stored under {@link StandingDetection#KEY}. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("principal", principal);
            m.put("ownerId", ownerId);
            m.put("capabilities", new ArrayList<>(new TreeSet<>(capabilities)));
            m.put("dataScopes", dataScopes == null ? null : new ArrayList<>(new TreeSet<>(dataScopes)));
            m.put("attributes", attributes);
            m.put("dataset", dataset);
            m.put("masking", masking);
            m.put("enabledBy", enabledBy);
            m.put("enabledAt", enabledAt);
            return m;
        }

        /** The authority recorded in a binding, or empty when there is none or it is malformed (fail closed). */
        public static Optional<Authority> from(Map<String, Object> binding) {
            if (binding == null || !(binding.get(KEY) instanceof Map<?, ?> s)) return Optional.empty();
            try {
                @SuppressWarnings("unchecked") Map<String, Object> attrs = s.get("attributes") instanceof Map<?, ?> a
                        ? (Map<String, Object>) a : Map.of();
                @SuppressWarnings("unchecked") Map<String, Object> mask = (Map<String, Object>) s.get("masking");
                if (mask == null) return Optional.empty();
                return Optional.of(new Authority(str(s.get("principal")), str(s.get("ownerId")),
                        strings(s.get("capabilities")), s.get("dataScopes") == null ? null : strings(s.get("dataScopes")),
                        attrs, str(s.get("dataset")), mask, str(s.get("enabledBy")), str(s.get("enabledAt"))));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
    }

    /** The outcome of a re-decision: {@code allowed}, or a refusal {@code code} with its {@code reason}. */
    public record Verdict(boolean allowed, String code, String reason) {
        static Verdict allow() { return new Verdict(true, null, null); }
        static Verdict refuse(String code, String reason) { return new Verdict(false, code, reason); }
    }

    /** Everything {@link #decide} judges against, read at the moment of the decision. */
    record Live(String owner, String dataset, Map<String, Object> datasetContent, boolean ownerIsLead,
                Map<String, Object> masking, Path configRoot, Map<String, Object> resource) {}

    /**
     * The pure decision (no I/O besides the PDP call): {@code a}, the recorded authority, against the {@code live}
     * facts. Never throws for malformed input — it refuses.
     */
    static Verdict decide(Authority a, Live live) {
        if (a == null) return Verdict.refuse(NOT_ENABLED, "standing detection is not enabled for this Alert Rule");
        if (a.ownerId() == null || a.ownerId().isBlank() || a.principal() == null || a.principal().isBlank())
            return Verdict.refuse(NO_OWNER, "the binding records no owner id, so nothing can be decided");
        if (live == null) return Verdict.refuse(UNDECIDABLE, "the Investigation could not be read");
        if (!Objects.equals(a.ownerId(), live.owner()) || !Objects.equals(a.dataset(), live.dataset()))
            return Verdict.refuse(BINDING_CHANGED, "the Investigation's owner or Dataset differs from the one enabled");
        if (live.datasetContent() == null)
            return Verdict.refuse(DATASET_GONE, "Dataset '" + a.dataset() + "' no longer exists");
        // The sweep passes NO capabilities: canConfigureAccess is a live grant it cannot confirm.
        switch (ComponentAccess.canViewAs(a.ownerId(), Set.of(), live.datasetContent())) {
            case ALLOWED -> { }
            case ROLE_ONLY -> {
                return Verdict.refuse(ROLE_SHARE_ONLY, "the Dataset is shared to the owner only through a role share, "
                        + "which cannot be re-resolved without a request; share it to the owner by user id");
            }
            case DENIED -> {
                return Verdict.refuse(DATASET_NOT_SHARED, "the owner can no longer view Dataset '" + a.dataset() + "'");
            }
        }
        if (!live.ownerIsLead()) return Verdict.refuse(NOT_LEAD, "the owner is no longer a lead of the Investigation");
        if (live.masking() == null)
            return Verdict.refuse(UNDECIDABLE, "the masking basis could not be determined");
        String tightened = tightened(a.masking(), live.masking());
        if (tightened != null) return Verdict.refuse(MASKING_TIGHTENED, tightened + "; re-enable standing detection to accept it");
        if (!RowScope.visibleAs(live.configRoot(), synthetic(a), "investigation", live.resource()))
            return Verdict.refuse(POLICY_DENIED, "an access policy denies the owner this Investigation");
        return Verdict.allow();
    }

    /** The synthetic Subject: the owner's recorded id and grants, tagged as the sweep. Never holds more than was recorded. */
    static Subject synthetic(Authority a) {
        Map<String, Object> attrs = new LinkedHashMap<>(a.attributes());
        attrs.put("sweepPrincipal", a.principal());
        return new Subject(a.ownerId(), a.capabilities(), a.dataScopes(), attrs);
    }

    private static final Map<String, Integer> RANK = Map.of("none", 0, "typed", 1, "all", 2);

    /** A sentence when {@code now} masks MORE than {@code snapshot} did, else null. Unknown modes count as strictest. */
    static String tightened(Map<String, Object> snapshot, Map<String, Object> now) {
        int before = RANK.getOrDefault(str(snapshot.get("mode")), 2), after = RANK.getOrDefault(str(now.get("mode")), 2);
        if (after > before) return "maskingMode rose from '" + snapshot.get("mode") + "' to '" + now.get("mode") + "'";
        Set<String> was = strings(snapshot.get("columns")), is = strings(now.get("columns"));
        Set<String> added = new TreeSet<>(is);
        added.removeAll(was);
        return added.isEmpty() ? null : "newly masked or untraceable bound column(s) " + added;
    }

    /**
     * Read the live facts and decide. Fail CLOSED: any exception is an {@link #UNDECIDABLE} refusal, never a throw,
     * because a sweep has nobody to answer to and must not die half-way.
     */
    public static Verdict check(Path writeRoot, InvestigationStore store, String id, Map<String, Object> header,
                                Authority a) {
        try {
            if (a == null) return decide(null, null);
            Object owner = header.get("owner");
            String dataset = String.valueOf(header.get("dataset"));
            Map<String, Object> content = DatasetProviders.require().dataset(writeRoot, dataset).orElse(null);
            Map<String, InvestigationMembers.Role> roles = InvestigationMemberStore.roles(store, id, owner);
            InvestigationMembers.Role r = owner == null ? null : roles.get(String.valueOf(owner));
            InvestigationRoutes.Inv inv = new InvestigationRoutes.Inv(store, writeRoot, id, header);
            Live live = new Live(owner == null ? null : String.valueOf(owner), dataset, content,
                    r != null && r.canWriteMainLog(), masking(inv), writeRoot, InvestigationRoutes.resource(inv, roles));
            return decide(a, live);
        } catch (Exception | LinkageError e) {
            return Verdict.refuse(UNDECIDABLE, "could not be decided: " + e.getClass().getSimpleName());
        }
    }

    /** The masking basis in force now: {@code {mode, columns[]}}, or null when it cannot be computed. */
    public static Map<String, Object> masking(InvestigationRoutes.Inv inv) {
        try {
            LinkAnalysisSettings settings = LinkAnalysisSettings.forRoot(inv.writeRoot());
            List<EntityTypes.EntityType> types = settings.effectiveEntityTypes();
            Set<String> columns = new TreeSet<>();
            EntityMasking.maskedColumns(inv, types).forEach((col, type) -> columns.add(col + ":" + type));
            Map<String, Object> m = new TreeMap<>();
            m.put("mode", settings.effectiveMaskingMode());
            m.put("columns", new ArrayList<>(columns));
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Set<String> strings(Object o) {
        Set<String> out = new TreeSet<>();
        if (o instanceof Collection<?> c) for (Object x : c) if (x != null) out.add(String.valueOf(x));
        return out;
    }
}
