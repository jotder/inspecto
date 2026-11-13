package com.gamma.service;

import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SE-5a — a Space's origin: which Space Template (and template version) it was made from, and the SHA-256 of each config file
 * at that moment ({@code docs/okf/product/module/config/spec-space-origin.md}). ONE record, the one PA-7d already writes:
 * {@code <space>/.inspecto/created-from.toon} plus {@code created-from.sha256} beside it. SE-5a widens it from "App templates
 * only" to every template and adds {@code templateVersion} and {@code origin: created|adopted}; the App fields PA-7d reads
 * ({@code template}, {@code app}, {@code version}) are unchanged.
 *
 * <p>Report-only: nothing here changes a Space's config. A Space without the record behaves exactly as before.
 */
public final class SpaceOrigin {

    public static final String RECORD = ".inspecto/created-from.toon";
    public static final String HASHES = ".inspecto/created-from.sha256";
    /** What {@code template.toon} without {@code version:} reads as. */
    public static final String UNVERSIONED = "unversioned";
    /** A template version a caller may name: plain, so it is written into TOON unescaped. */
    public static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");

    private SpaceOrigin() {}

    /** A Space's origin record. {@code hashes}: config-relative path to SHA-256, as recorded. */
    public record Origin(String template, String templateVersion, String origin, String recordedAt,
                         Map<String, String> hashes) {}

    /** A template's {@code version:}, or {@link #UNVERSIONED}. */
    public static String templateVersion(Path templateDir) {
        try {
            Map<String, Object> meta = ToonHelper.load(templateDir.resolve("template.toon").toString());
            Object v = meta.get("version");
            String s = v == null ? "" : String.valueOf(v).trim();
            return s.isEmpty() ? UNVERSIONED : s;
        } catch (IOException | RuntimeException unreadable) {
            return UNVERSIONED;
        }
    }

    /** The record of {@code spaceBase}, or empty when it has none (or it does not read - the Space then behaves as unrecorded). */
    public static Optional<Origin> read(Path spaceBase) {
        Path rec = spaceBase.resolve(".inspecto").resolve("created-from.toon");   // = RECORD, named for ImportLoaderInventoryTest
        if (!Files.isRegularFile(rec)) return Optional.empty();
        try {
            Map<String, Object> m = ToonHelper.decode(Files.readString(rec));
            String tpl = ToonHelper.opt(m, "template", "");
            if (tpl.isBlank()) return Optional.empty();
            return Optional.of(new Origin(tpl, ToonHelper.opt(m, "templateVersion", UNVERSIONED),
                    ToonHelper.opt(m, "origin", "created"), ToonHelper.opt(m, "recordedAt", ""), hashes(spaceBase)));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Write the record for a Space just created from {@code templateDir}: the hashes are of the Space's own config as it now
     * stands (seeded and stamped). {@code app}/{@code appVersion} are the PA-7d fields, blank for a shipped template.
     */
    static void recordCreated(Path templateDir, Path spaceBase, String templateId, String app, String appVersion) throws IOException {
        write(spaceBase, templateId, templateVersion(templateDir), "created", app, appVersion, hashTree(spaceBase.resolve("config")));
    }

    // ── adopt ──

    /** An adopt's answer: per-file comparison, and whether the record was written. */
    public record Adoption(String template, String templateVersion, List<String> matching, List<String> differing,
                           List<String> onlyInTemplate, List<String> onlyInSpace, boolean recorded) {
        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("template", template);
            m.put("templateVersion", templateVersion);
            // full = every template file is in the Space unchanged; files only the Space has (its own, or ones the server
            // writes into every Space such as egress.toon) do not make it partial
            m.put("match", matching.isEmpty() ? "none" : differing.isEmpty() && onlyInTemplate.isEmpty() ? "full" : "partial");
            m.put("recorded", recorded);
            m.put("matching", matching);
            m.put("differing", differing);
            m.put("onlyInTemplate", onlyInTemplate);
            m.put("onlyInSpace", onlyInSpace);
            return m;
        }
    }

    /**
     * Match an existing Space against {@code templateDir} as it now is, by file hash (a template {@code .toon} rendered with
     * {@code ${SPACE}} = {@code spaceId}, as creation would have). Records the origin when at least one file matches, with the
     * TEMPLATE's hashes as the baseline: a file the Space holds differently then reads as "changed by the Space". With no
     * file in common nothing is recorded. The caller refuses a Space that already has a record.
     */
    public static Adoption adopt(Path templateDir, Path spaceBase, String templateId, String spaceId) throws IOException {
        Map<String, String> tpl = renderedTemplateHashes(templateDir, spaceId);
        Map<String, String> mine = hashTree(spaceBase.resolve("config"));
        List<String> matching = new ArrayList<>(), differing = new ArrayList<>(), onlyT = new ArrayList<>(), onlyS = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(tpl.keySet());
        names.addAll(mine.keySet());
        for (String f : names) {
            String t = tpl.get(f), s = mine.get(f);
            if (t == null) onlyS.add(f);
            else if (s == null) onlyT.add(f);
            else if (t.equals(s)) matching.add(f);
            else differing.add(f);
        }
        String version = templateVersion(templateDir);
        boolean record = !matching.isEmpty();
        if (record) write(spaceBase, templateId, version, "adopted", "", "", tpl);
        return new Adoption(templateId, version, matching, differing, onlyT, onlyS, record);
    }

    // ── report ──

    /**
     * For a Space with an origin: files unchanged since the origin, files the Space changed, files the Space removed, files
     * the Space added; and - when the template's current version differs from the recorded one - files the template added,
     * changed or removed since. {@code templateDir} may be null (the template is gone): the template section then says so.
     */
    public static Map<String, Object> report(Origin o, Path spaceBase, Path templateDir, String spaceId) throws IOException {
        Map<String, String> mine = hashTree(spaceBase.resolve("config"));
        List<String> unchanged = new ArrayList<>(), changed = new ArrayList<>(), removed = new ArrayList<>(), added = new ArrayList<>();
        o.hashes().forEach((f, h) -> {
            String s = mine.get(f);
            if (s == null) removed.add(f);
            else if (s.equals(h)) unchanged.add(f);
            else changed.add(f);
        });
        mine.keySet().stream().filter(f -> !o.hashes().containsKey(f)).forEach(added::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "recorded");
        out.put("template", o.template());
        out.put("templateVersion", o.templateVersion());
        out.put("origin", o.origin());
        out.put("recordedAt", o.recordedAt());
        Map<String, Object> space = new LinkedHashMap<>();
        space.put("unchanged", unchanged);
        space.put("changed", changed);
        space.put("removed", removed);
        space.put("added", added);
        out.put("space", space);
        Map<String, Object> template = new LinkedHashMap<>();
        if (templateDir == null || !Files.isRegularFile(templateDir.resolve("template.toon"))) {
            template.put("status", "missing");
        } else {
            String now = templateVersion(templateDir);
            template.put("currentVersion", now);
            if (now.equals(o.templateVersion())) {
                template.put("status", "same-version");
            } else {
                // The record's hashes are the Space's files at creation (TemplateSeedGate may have stamped seeded Jobs),
                // so a stamped Job can read as "changed" here; SE-5b's three-way proposal compares decoded TOON.
                Map<String, String> tpl = renderedTemplateHashes(templateDir, spaceId);
                // No "removed": the recorded hashes also hold files the server wrote at creation (egress.toon), so a
                // recorded file the template lacks may never have been the template's. SE-5b compares old vs new template.
                List<String> tAdded = new ArrayList<>(), tChanged = new ArrayList<>();
                tpl.forEach((f, h) -> {
                    String was = o.hashes().get(f);
                    if (was == null) tAdded.add(f);
                    else if (!was.equals(h)) tChanged.add(f);
                });
                template.put("status", "newer-version");
                template.put("added", tAdded);
                template.put("changed", tChanged);
            }
        }
        out.put("templateSince", template);
        return out;
    }

    // ── upgrade (SE-5b) ──

    /**
     * After an upgrade applied some of a newer template's files: the record now names {@code newVersion} (the caller passes
     * the old version while updates or adds of the newer one remain unapplied) and holds
     * {@code applied} (path to the SHA-256 of the bytes written) in place of those files' old hashes. Every other hash - a
     * conflict, a file the operator left out - stays as recorded, so it still reads against the old baseline. The record's
     * {@code template}, {@code origin} and the PA-7d App fields are kept; {@code recordedAt} is now. Temp file + move.
     */
    public static void recordUpgrade(Path spaceBase, String newVersion, Map<String, String> applied) throws IOException {
        Map<String, Object> m = ToonHelper.decode(Files.readString(spaceBase.resolve(RECORD)));
        Map<String, String> merged = hashes(spaceBase);
        merged.putAll(applied);
        write(spaceBase, ToonHelper.opt(m, "template", ""), newVersion, ToonHelper.opt(m, "origin", "created"),
                ToonHelper.opt(m, "app", ""), ToonHelper.opt(m, "version", ""), merged);
    }

    // ── files ──

    private static void write(Path spaceBase, String templateId, String version, String origin, String app, String appVersion,
                              Map<String, String> hashes) throws IOException {
        Path rec = spaceBase.resolve(RECORD);
        Files.createDirectories(rec.getParent());
        StringBuilder toon = new StringBuilder()
                .append("template: ").append(templateId).append('\n')
                .append("templateVersion: \"").append(quoted(version)).append("\"\n")
                .append("origin: ").append(origin).append('\n')
                .append("recordedAt: \"").append(Instant.now()).append("\"\n");
        if (!app.isBlank()) toon.append("app: ").append(app).append('\n').append("version: \"").append(quoted(appVersion)).append("\"\n");
        StringBuilder sums = new StringBuilder();
        hashes.forEach((f, h) -> sums.append(h).append("  ").append(f).append('\n'));
        atomicWrite(rec.resolveSibling("created-from.sha256"), sums.toString());
        atomicWrite(rec, toon.toString());   // the record last: a half-written pair never reads as an origin
    }

    private static String quoted(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void atomicWrite(Path target, String text) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Map<String, String> hashes(Path spaceBase) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Path h = spaceBase.resolve(HASHES);
        if (!Files.isRegularFile(h)) return out;
        for (String line : Files.readAllLines(h)) if (line.length() > 66 && line.charAt(64) == ' ') out.put(line.substring(66), line.substring(0, 64));
        return out;
    }

    /** {@code config-relative path -> sha256} of every regular file under {@code config}, '/'-separated; empty if absent. */
    static Map<String, String> hashTree(Path config) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(config)) return out;
        try (Stream<Path> files = Files.walk(config)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList())
                out.put(config.relativize(f).toString().replace('\\', '/'), sha256(Files.readAllBytes(f)));
        }
        return out;
    }

    /** The template's config files as creation would write them for {@code spaceId}: each {@code .toon} with {@code ${SPACE}} rewritten. */
    private static Map<String, String> renderedTemplateHashes(Path templateDir, String spaceId) throws IOException {
        Map<String, String> out = new TreeMap<>();
        renderedTemplateFiles(templateDir, spaceId).forEach((f, b) -> out.put(f, sha256(b)));
        return out;
    }

    /**
     * SE-5b: the template's config files, {@code config-relative path -> bytes}, as creation would write them for
     * {@code spaceId} (each {@code .toon} with {@code ${SPACE}} rewritten, any other file as is); empty if it has no config.
     */
    public static Map<String, byte[]> renderedTemplateFiles(Path templateDir, String spaceId) throws IOException {
        Path config = templateDir.resolve("config");
        Map<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(config)) return out;
        try (Stream<Path> files = Files.walk(config)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                byte[] b = f.getFileName().toString().endsWith(".toon")
                        ? Files.readString(f).replace("${SPACE}", spaceId).getBytes(StandardCharsets.UTF_8)
                        : Files.readAllBytes(f);
                out.put(config.relativize(f).toString().replace('\\', '/'), b);
            }
        }
        return out;
    }

    /** Hex SHA-256 of {@code b}, the hash the record keeps. */
    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
