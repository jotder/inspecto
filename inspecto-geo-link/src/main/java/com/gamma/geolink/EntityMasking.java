package com.gamma.geolink;

import com.gamma.control.LinkAnalysisSettings;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.gamma.geolink.InvestigationEvaluator.strings;

/**
 * Entity masking for Investigation responses (LA-19, decision D-U6 — operator 2026-09-24).
 *
 * <p><b>Where.</b> RENDER time only, on the way out of every Investigation response that carries entity ids — the
 * {@code /ops}, {@code /undo}, {@code /replay} and {@code /log} answers, the Working Set relation, and the Dossier
 * (including the score vectors of the snapshots it embeds). ⛔ Never at storage: the sealed log binds raw ids and
 * every hash is over raw content, so masking the store would break replay and custody. ⚠ Consequence: a masked
 * Dossier's exposed lists no longer hash to its manifest's {@code content} entries locally — custody is verified
 * against the store with {@code POST .../dossier/verify}, which is unaffected.
 *
 * <p><b>What — the Space's {@code maskingMode}</b> ({@code link-analysis.toon}, declared in
 * {@code ConfigSpecs.linkAnalysisSettings()}):
 * <ul>
 *   <li>{@code none} — nothing is masked.</li>
 *   <li>{@code all} — every entity id the Investigation knows (every id an op named, every endpoint of every sealed
 *       read row, every frontier/exclusion id) — plus, in a Dossier, every node id of an embedded snapshot.</li>
 *   <li>{@code typed} (the DEFAULT) — typed identifiers: {@link #TYPED_IDENTIFIERS}. ⚠ Entity typing (LA-17) is NOT
 *       built, so "typed" is decided from the only type marks that exist TODAY, and masks exactly this:
 *       <ol>
 *         <li>any id SEEDED with {@code entityType} MSISDN, IMSI or ACCOUNT (case-insensitive) — per entity; and</li>
 *         <li>if the bound Dataset's registry {@code columns[]} entry for the Investigation's {@code sourceCol} or
 *             {@code targetCol} carries {@code classification} MSISDN, IMSI or ACCOUNT — EVERY entity id, because
 *             an id does not record which column it was read from.</li>
 *       </ol>
 *       Nothing else. An entity ADMITTED by an expand has no type, so a neighbour of a typed seed is NOT masked
 *       unless rule 2 applies; and no shipped Dataset declares such a column classification today, so on the
 *       shipped demo data {@code typed} masks only seeds typed by the analyst. The schema-file
 *       {@code raw.fields[].classification} is not consulted: nothing resolves a Dataset to its schema file.</li>
 * </ul>
 *
 * <p><b>How.</b> A masked id becomes {@code masked:<16 hex>} — an HMAC-SHA256 of the id under a random per-Investigation
 * key ({@code mask.key} in the Investigation's directory, created on first use, never served). Keyed, because a
 * plain hash of a phone number is reversible by enumerating the number space. Stable, so the same entity reads as the
 * same pseudonym in every response. Exact string values and map keys are replaced; free text ({@link #FREE_TEXT})
 * has each id replaced where it stands as a whole token. A pseudonym sent BACK in an op's {@code ids} is resolved to
 * its entity, so an analyst can keep working on what they see.
 */
final class EntityMasking {

    /** The typed identifiers {@code typed} masks — the D-U6 list. */
    static final Set<String> TYPED_IDENTIFIERS = Set.of("MSISDN", "IMSI", "ACCOUNT");
    /** Response keys whose values are prose that may mention an id inline. */
    private static final Set<String> FREE_TEXT = Set.of("text", "note", "reason", "method", "steps", "purpose", "title");
    static final String TOKEN_PREFIX = "masked:";
    private static final String KEY_FILE = "mask.key";

    private final String mode;
    private final String basis;
    private final Map<String, String> tokenOf;   // raw id → pseudonym
    private final Map<String, String> rawOf;     // pseudonym → raw id
    private Pattern inText;

    private EntityMasking(String mode, String basis, Map<String, String> tokenOf) {
        this.mode = mode;
        this.basis = basis;
        this.tokenOf = tokenOf;
        this.rawOf = new LinkedHashMap<>();
        tokenOf.forEach((raw, token) -> rawOf.put(token, raw));
    }

    /** The masking in force for one Investigation, from its sealed log (read here) plus any {@code extraIds}. */
    static EntityMasking of(InvestigationRoutes.Inv inv, Collection<String> extraIds) throws IOException {
        List<Map<String, Object>> log = new ArrayList<>();
        for (String line : inv.store().readLog(inv.id())) {
            @SuppressWarnings("unchecked") Map<String, Object> m = com.gamma.control.ApiContext.JSON.readValue(line, Map.class);
            log.add(m);
        }
        return of(inv, log, extraIds);
    }

    static EntityMasking of(InvestigationRoutes.Inv inv, List<Map<String, Object>> log, Collection<String> extraIds)
            throws IOException {
        String mode = LinkAnalysisSettings.forRoot(inv.writeRoot()).effectiveMaskingMode();
        Set<String> universe = new TreeSet<>(extraIds);
        Set<String> typedSeeds = new TreeSet<>();
        for (Map<String, Object> e : log) collect(e, universe, typedSeeds);
        Set<String> masked;
        String basis;
        switch (mode) {
            case "none" -> {
                masked = Set.of();
                basis = "masking is off for this Space (maskingMode none)";
            }
            case "all" -> {
                masked = universe;
                basis = "every entity id (maskingMode all)";
            }
            default -> {
                List<String> typedCols = typedColumns(inv);
                if (!typedCols.isEmpty()) {
                    masked = universe;
                    basis = "typed: bound column(s) " + typedCols + " are classified as a typed identifier "
                            + TYPED_IDENTIFIERS.stream().sorted().toList() + ", and an id does not record which "
                            + "column it was read from, so every entity id is masked";
                } else {
                    masked = typedSeeds;
                    basis = "typed: entities seeded with entityType " + TYPED_IDENTIFIERS.stream().sorted().toList()
                            + "; no bound column is classified as a typed identifier, and entities admitted by an "
                            + "expand carry no type (entity typing, LA-17, is not built)";
                }
            }
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        if (!masked.isEmpty()) {
            byte[] key = key(inv.dir());
            for (String id : masked) tokens.put(id, token(key, id));
        }
        return new EntityMasking(mode, basis, tokens);
    }

    /** Every id a log entry names, and the ids its {@code seed} typed as a typed identifier. */
    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> e, Set<String> universe, Set<String> typedSeeds) {
        if (e.get("params") instanceof Map<?, ?> p) {
            List<String> ids = strings(p.get("ids"));
            universe.addAll(ids);
            if ("seed".equals(e.get("op")) && p.get("entityType") != null
                    && TYPED_IDENTIFIERS.contains(String.valueOf(p.get("entityType")).toUpperCase(Locale.ROOT)))
                typedSeeds.addAll(ids);
        }
        if (e.get("read") instanceof Map<?, ?> r) {
            if (r.get("query") instanceof Map<?, ?> q) {
                universe.addAll(strings(q.get("frontier")));
                universe.addAll(strings(q.get("excluded")));
            }
            if (r.get("rows") instanceof List<?> rows)
                for (Object o : rows)
                    if (o instanceof Map<?, ?> row) {
                        if (row.get("source") != null) universe.add(String.valueOf(row.get("source")));
                        if (row.get("target") != null) universe.add(String.valueOf(row.get("target")));
                    }
        }
    }

    /** The Investigation's bound source/target columns the Dataset's registry {@code columns[]} classify as typed. */
    private static List<String> typedColumns(InvestigationRoutes.Inv inv) {
        Map<String, Object> ds = new ComponentStore(inv.writeRoot().resolve("registry")).get("dataset", inv.dataset())
                .map(ComponentRegistry.Component::content).orElse(Map.of());
        List<String> out = new ArrayList<>();
        if (!(ds.get("columns") instanceof List<?> cols)) return out;
        for (String bound : List.of("sourceCol", "targetCol")) {
            Object name = inv.header().get(bound);
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && name != null && String.valueOf(name).equalsIgnoreCase(String.valueOf(c.get("name")))
                        && c.get("classification") != null
                        && TYPED_IDENTIFIERS.contains(String.valueOf(c.get("classification")).trim().toUpperCase(Locale.ROOT)))
                    out.add(String.valueOf(name));
        }
        return out;
    }

    /** The per-Investigation HMAC key, created on first use. Never served. {@link EntityListRoutes} keys
     *  Entity List members the same way, with one key per Space fact log. ⚠ The key is written to a sibling temp
     *  file and only then published by {@link EntityFactLog#publishNew} (a hard link, never a replace), so a
     *  concurrent first reader never sees a partly written {@code mask.key}; the loser of a race reads the winner's. */
    static byte[] key(Path dir) throws IOException {
        Path f = dir.resolve(KEY_FILE);
        if (!Files.isRegularFile(f)) {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            Path tmp = Files.createTempFile(dir, ".mask-", ".tmp");
            try {
                Files.writeString(tmp, HexFormat.of().formatHex(k), StandardCharsets.UTF_8);
                EntityFactLog.publishNew(tmp, f);
            } catch (FileAlreadyExistsException raced) {
                // another request created it first — read theirs below
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        return HexFormat.of().parseHex(Files.readString(f, StandardCharsets.UTF_8).trim());
    }

    static String token(byte[] key, String id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return TOKEN_PREFIX + HexFormat.of().formatHex(mac.doFinal(id.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── use ────────────────────────────────────────────────────────────────────────────────────────────

    /** A masked copy of a response tree (Maps, Lists, Strings); the input is not modified. */
    Object apply(Object node) {
        return tokenOf.isEmpty() ? node : apply(node, null);
    }

    private Object apply(Object node, String key) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                out.put(tokenOf.getOrDefault(k, k), apply(e.getValue(), k));
            }
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object v : l) out.add(apply(v, key));
            return out;
        }
        if (node instanceof String s) {
            String t = tokenOf.get(s);
            if (t != null) return t;
            return key != null && FREE_TEXT.contains(key) ? inText(s) : s;
        }
        return node;
    }

    /** Replace every masked id standing as a whole token inside prose. */
    String inText(String s) {
        if (tokenOf.isEmpty() || s == null) return s;
        if (inText == null)
            inText = Pattern.compile("(?<![\\p{Alnum}_])(?:" + tokenOf.keySet().stream()
                    .sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote)
                    .collect(Collectors.joining("|")) + ")(?![\\p{Alnum}_])");
        return inText.matcher(s).replaceAll(mr -> Matcher.quoteReplacement(tokenOf.get(mr.group())));
    }

    /** Each id resolved from its pseudonym when it is one this Investigation issued; anything else unchanged. */
    List<String> resolve(List<String> ids) {
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) out.add(rawOf.getOrDefault(id, id));
        return out;
    }

    /** The raw id behind a pseudonym this Investigation issued, or null. */
    String reveal(String token) {
        return rawOf.get(token);
    }

    /** What the response says about masking — the mode, how many ids are masked, and on what basis. */
    Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("masked", tokenOf.size());
        m.put("basis", basis);
        return m;
    }
}
