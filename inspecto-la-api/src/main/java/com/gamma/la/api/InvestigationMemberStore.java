package com.gamma.la.api;

import com.gamma.control.ApiContext;
import com.gamma.la.core.InvestigationMembers;
import com.gamma.la.core.InvestigationMembers.Entry;
import com.gamma.la.core.InvestigationMembers.Op;
import com.gamma.la.core.InvestigationMembers.Role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D7-1 — the file IO of an Investigation's membership: {@code investigations/<id>/members.jsonl}, beside
 * {@code header.json}, one JSON line per grant or revoke, only ever APPENDED (never rewritten). The pure model and the
 * fold live in {@link InvestigationMembers}. A file that does not exist means "no grants": the owner is the sole lead,
 * exactly as before membership existed.
 *
 * <p>Appends are serialised by the caller under the Investigation's directory lock
 * ({@link InvestigationRoutes#lock}), so the last-lead check and the append are one critical section.
 */
final class InvestigationMemberStore {

    static final String FILE = "members.jsonl";

    private InvestigationMemberStore() {}

    /** Whether the Investigation has ever had a grant or revoke (the file exists). Without one, only the legacy rules apply. */
    static boolean explicit(Path investigationDir) {
        return Files.isRegularFile(investigationDir.resolve(FILE));
    }

    /** The entries in order. A line that does not parse is a corrupt record: fail closed (the IOException is the caller's 500). */
    @SuppressWarnings("unchecked")
    static List<Entry> read(Path investigationDir) throws IOException {
        Path f = investigationDir.resolve(FILE);
        if (!Files.isRegularFile(f)) return List.of();
        List<Entry> out = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Map<String, Object> m = ApiContext.JSON.readValue(line, Map.class);
            Role role = Role.parse(String.valueOf(m.get("role")))
                    .orElseThrow(() -> new IOException("members.jsonl: unknown role in " + line));
            Op op = Op.parse(String.valueOf(m.get("op")))
                    .orElseThrow(() -> new IOException("members.jsonl: unknown op in " + line));
            out.add(new Entry(((Number) m.get("seq")).longValue(), String.valueOf(m.get("ts")),
                    String.valueOf(m.get("actor")), String.valueOf(m.get("subject")), role, op));
        }
        return out;
    }

    /** The current role per Subject: the header's owner as first lead, then the folded entries. */
    static Map<String, Role> roles(Path investigationDir, Object owner) throws IOException {
        return InvestigationMembers.fold(owner == null ? null : String.valueOf(owner), read(investigationDir));
    }

    /** Append one entry (seq = previous count + 1). Call under the Investigation's lock. */
    static Entry append(Path investigationDir, String ts, String actor, String subject, Role role, Op op) throws IOException {
        long seq = read(investigationDir).size() + 1L;
        Entry e = new Entry(seq, ts, actor, subject, role, op);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("ts", e.ts());
        m.put("actor", e.actor());
        m.put("subject", e.subject());
        m.put("role", e.role().wire());
        m.put("op", e.op().wire());
        Files.writeString(investigationDir.resolve(FILE), ApiContext.JSON.writeValueAsString(m) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return e;
    }
}
