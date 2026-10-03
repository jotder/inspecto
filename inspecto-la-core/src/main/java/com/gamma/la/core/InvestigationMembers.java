package com.gamma.la.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * D7-1 — the pure membership model of an Investigation (D19; design {@code la-separation-d7-design.md} §9). Host-free:
 * the file IO and the HTTP gate live in {@code inspecto-la-api}.
 *
 * <p>The record is an append-only list of {@link Entry grants and revokes}; the current role of a Subject is the FOLD
 * of that list ({@link #fold}) — the last operation on a Subject wins, a revoke removes the role. The creator is the
 * implicit FIRST lead, so an Investigation with no entries at all behaves exactly as the owner-only rule did: the
 * owner is its sole lead.
 *
 * <p>What a role may do ({@link Role#canRead}, {@link Role#canWriteMainLog}, {@link Role#canManageMembers}):
 * a {@code lead} reads, writes the main log and grants/revokes members; an {@code analyst} and a {@code reviewer} read
 * only (in D7-1 — their Draft rights arrive with the Draft object). The last lead cannot be removed or demoted
 * ({@link #refusal}), so an Investigation can never be orphaned.
 */
public final class InvestigationMembers {

    private InvestigationMembers() {}

    /** A member's role. {@link #wire()} is the lower-case name used in the file, the API and the audit trail. */
    public enum Role {
        LEAD, ANALYST, REVIEWER;

        public String wire() { return name().toLowerCase(java.util.Locale.ROOT); }

        public static Optional<Role> parse(String s) {
            if (s == null) return Optional.empty();
            for (Role r : values()) if (r.wire().equals(s)) return Optional.of(r);
            return Optional.empty();
        }

        /** Every role reads the Investigation (its log, Working Set, Dossier, Graph Runs, oversight). */
        public boolean canRead() { return true; }

        /** Only a lead writes the MAIN log in D7-1 (analysts write their own Draft and promote, from D7-3). */
        public boolean canWriteMainLog() { return this == LEAD; }

        /** Only a lead grants and revokes. */
        public boolean canManageMembers() { return this == LEAD; }

        /** The four-eyes decision on a pending expand: a lead or a reviewer (the requester still never decides own). */
        public boolean canApprove() { return this == LEAD || this == REVIEWER; }
    }

    public enum Op { GRANT, REVOKE;
        public String wire() { return name().toLowerCase(java.util.Locale.ROOT); }
        public static Optional<Op> parse(String s) {
            if (s == null) return Optional.empty();
            for (Op o : values()) if (o.wire().equals(s)) return Optional.of(o);
            return Optional.empty();
        }
    }

    /** One line of {@code members.jsonl}. For a revoke, {@code role} is the role that was removed (for the trail). */
    public record Entry(long seq, String ts, String actor, String subject, Role role, Op op) {}

    /** A Subject id is a bounded single line of printable text. */
    private static final Pattern SUBJECT = Pattern.compile("[^\\p{Cntrl}]{1,200}");

    public static boolean validSubject(String s) {
        return s != null && SUBJECT.matcher(s).matches() && s.equals(s.strip());
    }

    /**
     * The current role per Subject: the owner as lead, then every entry in order (grant sets, revoke removes). The
     * owner is only the FIRST lead — a revoke removes the owner like anyone else (the last-lead rule prevents orphaning).
     */
    public static Map<String, Role> fold(String owner, List<Entry> entries) {
        Map<String, Role> roles = new LinkedHashMap<>();
        if (owner != null) roles.put(owner, Role.LEAD);
        for (Entry e : entries) {
            if (e.op() == Op.GRANT) roles.put(e.subject(), e.role());
            else roles.remove(e.subject());
        }
        return roles;
    }

    /**
     * Why {@code proposed} must be refused against the CURRENT roles, or empty when it is allowed: it would leave the
     * Investigation with no lead (the last lead revoked, or demoted by a grant of another role).
     */
    public static Optional<String> refusal(Map<String, Role> current, Op op, String subject, Role role) {
        if (current.get(subject) != Role.LEAD) return Optional.empty();
        boolean staysLead = op == Op.GRANT && role == Role.LEAD;
        if (staysLead) return Optional.empty();
        long leads = current.values().stream().filter(r -> r == Role.LEAD).count();
        return leads <= 1
                ? Optional.of("'" + subject + "' is the last lead of this Investigation — grant lead to someone else first")
                : Optional.empty();
    }

    /** The role map as the API and the Enterprise PDP see it: {@code subject → "lead"|"analyst"|"reviewer"}. */
    public static Map<String, Object> asWire(Map<String, Role> roles) {
        Map<String, Object> out = new LinkedHashMap<>();
        roles.forEach((s, r) -> out.put(s, r.wire()));
        return out;
    }
}
