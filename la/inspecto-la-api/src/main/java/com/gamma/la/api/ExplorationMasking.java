package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.MaskTokens;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity masking for the STATELESS exploration reads — {@code /inv/projection}, {@code /neighbors}, {@code /multi},
 * {@code /traversal/recursive-paths}, {@code /pattern/*} and {@code /value-measures} — which have no Investigation
 * (DR-D2, operator 2026-10-10: <i>mask the same way everywhere</i>).
 *
 * <p><b>Rule</b> — the Space's {@code maskingMode}, judged from the read's bound Dataset and endpoint columns:
 * {@code none} shows raw; {@code all} masks every id the read returns; {@code typed} (the default) masks every id when a
 * bound endpoint column's classification is claimed by a masked Entity Type (the same
 * {@link EntityMasking#maskedColumns} an Investigation applies, so a read and the Investigation over the same columns
 * agree on WHETHER to mask). An Investigation additionally masks a seed's explicit {@code entityType}; a stateless read
 * carries none.
 *
 * <p><b>Alias</b> — {@code masked:<16 hex>} under the Space's ONE key (the identity fact log's {@code mask.key}, the key
 * an Entity List member uses), so an id has one alias across every exploration read and every Entity List.
 * An Investigation's key is its own (§3.4: unlinkability between Investigations), so a seeded entity gains a SECOND
 * alias there; the Working Set carries the exploration alias beside it ({@code exploreAlias}) so the two stay one entity
 * in the UI.
 *
 * <p><b>Resolution</b> — a client hands an alias back (an expand's {@code value}, a traversal's {@code startNode}, an
 * Investigation seed). It is resolved from {@link #BOOK}: the alias → raw pairs this server has minted for this Space,
 * kept in memory (bounded, least-recently-used out) and appended to the Space's book file in the secrets directory beside
 * the root ({@code .exploration-alias-book}, outside every exported tree; LA-EDITIONS-RESIDUALS-1 4c), so an alias
 * still resolves after a restart: a miss reads that file once per Space per process. Nothing raw is ever sent to a
 * client. ⚠ An alias never minted, or whose book file was removed, is answered 422 {@code "alias not known"}.
 */
final class ExplorationMasking {

    private static final int BOOK_MAX = 500_000;
    private static final Map<String, String> BOOK = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > BOOK_MAX;
        }
    });

    /** Spaces whose book file has been read into {@link #BOOK} by this process. */
    private static final Set<Path> LOADED = ConcurrentHashMap.newKeySet();

    private final Path root;
    private final String mode;
    private final boolean active;
    private final String basis;
    private byte[] key;

    private ExplorationMasking(Path root, String mode, boolean active, String basis) {
        this.root = root;
        this.mode = mode;
        this.active = active;
        this.basis = basis;
    }

    /** The masking in force for a read over {@code dataset}; {@code roles} holds its {@code sourceCol}/{@code targetCol}. */
    static ExplorationMasking of(Path writeRoot, String dataset, Map<String, ?> roles) {
        LinkAnalysisSettings settings = LinkAnalysisSettings.forRoot(writeRoot);
        String mode = settings.effectiveMaskingMode();
        switch (mode) {
            case "none":
                return new ExplorationMasking(writeRoot, mode, false, "masking is off for this Space (maskingMode none)");
            case "all":
                return new ExplorationMasking(writeRoot, mode, true, "every entity id (maskingMode all)");
            default: {
                List<EntityTypes.EntityType> types = settings.effectiveEntityTypes();
                Map<String, String> cols = EntityMasking.maskedColumns(writeRoot, dataset, roles, types);
                return cols.isEmpty()
                        ? new ExplorationMasking(writeRoot, mode, false, "typed: no bound column of '" + dataset
                                + "' is classified as a masked Entity Type, so its ids are shown as they are")
                        : new ExplorationMasking(writeRoot, mode, true, "typed: bound column(s) " + List.copyOf(cols.keySet())
                                + " are classified as masked Entity Type(s) " + cols.values().stream().distinct().toList()
                                + ", so every entity id is masked");
            }
        }
    }

    /** An ACTIVE masking under the Space's key whatever the column rule says - for a read whose caller already holds
     *  aliases, or a classified non-endpoint column ({@code /inv/reference-rows}). */
    static ExplorationMasking forced(Path writeRoot, String mode, String basis) {
        return new ExplorationMasking(writeRoot, mode, true, basis);
    }

    boolean active() {
        return active;
    }

    /** The id as the caller sees it: its alias when masking is active, else unchanged. Null stays null. */
    String out(String raw) {
        if (!active || raw == null) return raw;
        String alias = MaskTokens.token(key(), raw);
        remember(root, alias, raw);
        return alias;
    }

    /** {@link #out} for a cell value that may not be a String. */
    Object out(Object raw) {
        return raw == null || !active ? raw : out(String.valueOf(raw));
    }

    /** The raw id behind an alias this Space served; anything else unchanged. 422 for an alias this server does not know. */
    String in(String id) {
        if (id == null || !id.startsWith(MaskTokens.TOKEN_PREFIX)) return id;
        String raw = lookup(root, id);
        if (raw == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "alias '" + id + "' is not known to this server "
                    + "(it was never minted here, or its book was removed) - run the query again to mint it, then pick the node");
        return raw;
    }

    /** The raw id behind an alias served for this Space's exploration reads, or null. */
    static String lookup(Path writeRoot, String alias) {
        String raw = BOOK.get(writeRoot + "\u0000" + alias);
        if (raw == null && LOADED.add(writeRoot)) {
            load(writeRoot);
            raw = BOOK.get(writeRoot + "\u0000" + alias);
        }
        return raw;
    }

    /**
     * The exploration alias of {@code raw} under this Space's key - what a Working Set row carries beside its own. An alias handed
     * out is one the server can resolve: it enters the {@link #BOOK} like {@link #out} does, so the Neighbours list of an
     * Investigation entity (LA-EXPAND-PICK-NEIGHBOURS-1) can send it back without a prior exploration read.
     */
    static String alias(Path writeRoot, String raw) {
        String alias = MaskTokens.token(spaceKey(writeRoot), raw);
        remember(writeRoot, alias, raw);
        return alias;
    }

    /** Puts the pair in the book; a pair this process did not hold yet is appended to the Space's book file. */
    private static void remember(Path writeRoot, String alias, String raw) {
        if (BOOK.put(writeRoot + "\u0000" + alias, raw) != null) return;
        String line = alias + "\t" + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)) + "\n";
        Path f = bookFile(writeRoot);
        synchronized (BOOK) {
            try {
                Files.createDirectories(f.getParent());
                Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void load(Path writeRoot) {
        Path f = bookFile(writeRoot);
        if (!Files.isRegularFile(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                try {
                    BOOK.putIfAbsent(writeRoot + "\u0000" + line.substring(0, tab),
                            new String(Base64.getDecoder().decode(line.substring(tab + 1)), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException torn) {
                    // a line torn by a crash mid-append: skipped, the next read mints that alias again
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path bookFile(Path writeRoot) {
        return com.gamma.util.SpaceSecretKeys.keyFile(writeRoot, ".exploration-alias-book");
    }

    /** Test seam: drops the in-memory book, as a restart does. */
    static void forgetInMemoryForTest() {
        BOOK.clear();
        LOADED.clear();
    }

    private static byte[] spaceKey(Path writeRoot) {
        try {
            return MaskTokens.spaceKey(writeRoot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the response says about masking. */
    Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("masked", active);
        m.put("basis", basis);
        return m;
    }

    private byte[] key() {
        if (key == null) key = spaceKey(root);
        return key;
    }
}
