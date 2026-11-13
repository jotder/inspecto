package com.gamma.control;

import com.gamma.service.ImportPaths;
import com.gamma.service.SpaceOrigin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * SE-5b — a Space Template upgrade proposal ({@code docs/okf/product/module/config/spec-space-origin.md} §5): the three-way
 * comparison of what the template was when the Space was made (the recorded hash, {@code created-from.sha256}), what the
 * installed template is now (its file rendered with {@code ${SPACE}} = the Space id, as creation renders it) and what the
 * Space holds now. The server keeps no older template copies, so "the template changed" means "the template's file now
 * differs from the recorded hash".
 *
 * <p>Per file, in this order:
 * <ul>
 *   <li>the Space already holds what the template now says ({@link InstalledApps#sameConfig}: bytes, or decoded TOON less
 *       the server's Job stamps) - nothing to do, left out;</li>
 *   <li>{@code template-removed} - recorded, the template no longer has it, the Space still does. Report only, never deleted
 *       (the record also holds files the server writes into every Space, so "removed" may never have been the template's);</li>
 *   <li>{@code add} - the template now has a file the Space never had (not recorded, absent);</li>
 *   <li>{@code update} - the Space's file still equals the recorded hash, so taking the template's loses nothing;</li>
 *   <li>{@code kept} - the Space changed (or deleted) the file and the template did not: the Space's edit stays;</li>
 *   <li>{@code conflict} - both changed (or the Space has its own file where the template now adds one): never applied,
 *       listed with the recorded, template and Space hashes so the operator resolves it by hand.</li>
 * </ul>
 * An {@code update} / {@code add} that no import may write ({@link ImportPaths}: not a config file, a reserved settings or
 * access document, a suffix-scanned ops config) carries {@code refused} and is never applied by the upgrade - its own
 * route writes it.
 *
 * <p>⚠ A seeded Job is stamped at creation, so its recorded hash is of the stamped file, never of the template's. A Space
 * that edited such a Job therefore reads as {@code conflict} even when the template left the Job alone - the safe side
 * (never applied), stated rather than guessed.
 */
final class SpaceTemplateUpgrade {

    static final String UPDATE = "update", ADD = "add", CONFLICT = "conflict", KEPT = "kept", TEMPLATE_REMOVED = "template-removed";

    private SpaceTemplateUpgrade() {}

    /** One file of a proposal. Hashes are null where the file is absent (Space / template) or was not recorded. */
    record Entry(String path, String change, String recorded, String template, String space, String refused) {
        boolean applicable() {
            return (UPDATE.equals(change) || ADD.equals(change)) && refused == null;
        }

        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            String kind = PendingChanges.kindOfConfigPath(path);   // the policy kind the hold classifies it by
            if (kind != null) m.put("kind", kind);
            m.put("change", change);
            m.put("recordedSha256", recorded);
            m.put("templateSha256", template);
            m.put("spaceSha256", space);
            if (refused != null) m.put("refused", refused);
            return m;
        }
    }

    /** A proposal: {@code files} in path order; {@code templateFiles} the rendered bytes of every template file. */
    record Proposal(String template, String fromVersion, String toVersion, List<Entry> files,
                    Map<String, byte[]> templateFiles) {

        /** What an apply without {@code files} takes: every {@code update} and {@code add} no import judge refuses. */
        List<String> applicable() {
            return files.stream().filter(Entry::applicable).map(Entry::path).toList();
        }

        Entry entry(String path) {
            return files.stream().filter(e -> e.path().equals(path)).findFirst().orElse(null);
        }

        /** A fingerprint of the whole proposal: an apply naming it is refused (409) once the template or the Space moved. */
        String id() {
            StringBuilder s = new StringBuilder(template).append('\n').append(fromVersion).append('\n').append(toVersion).append('\n');
            for (Entry e : files)
                s.append(e.path()).append('|').append(e.change()).append('|').append(e.recorded()).append('|')
                        .append(e.template()).append('|').append(e.space()).append('|').append(e.refused() != null).append('\n');
            return SpaceOrigin.sha256(s.toString().getBytes(StandardCharsets.UTF_8));
        }

        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "proposal");
            m.put("template", template);
            m.put("fromVersion", fromVersion);
            m.put("toVersion", toVersion);
            m.put("proposalId", id());
            m.put("files", files.stream().map(Entry::view).toList());
            m.put("applicable", applicable());
            return m;
        }
    }

    /** The proposal for a Space with origin {@code o}, whose config root is {@code config}, against {@code templateDir} as installed now. */
    static Proposal propose(SpaceOrigin.Origin o, Path config, Path templateDir, String spaceId) throws IOException {
        Map<String, byte[]> tpl = SpaceOrigin.renderedTemplateFiles(templateDir, spaceId);
        Set<String> referenced = ImportPaths.referencedEntries(tpl);
        TreeSet<String> names = new TreeSet<>(o.hashes().keySet());
        names.addAll(tpl.keySet());
        List<Entry> out = new ArrayList<>();
        for (String f : names) {
            String r = o.hashes().get(f);
            byte[] t = tpl.get(f);
            Path p = config.resolve(f).normalize();
            byte[] s = p.startsWith(config) && Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
            String th = t == null ? null : SpaceOrigin.sha256(t), sh = s == null ? null : SpaceOrigin.sha256(s);
            String change;
            if (t == null) {
                if (s == null) continue;   // gone from both: nothing to say
                change = TEMPLATE_REMOVED;
            } else if (s != null && InstalledApps.sameConfig(f, s, t)) {
                continue;                  // the Space already holds what the template says
            } else if (s == null) {
                change = r == null ? ADD : th.equals(r) ? KEPT : CONFLICT;
            } else if (r == null) {
                change = CONFLICT;         // the Space made its own file where the template now adds one
            } else if (sh.equals(r)) {
                change = UPDATE;
            } else {
                change = th.equals(r) ? KEPT : CONFLICT;
            }
            String refused = UPDATE.equals(change) || ADD.equals(change) ? ImportPaths.refusal(config, f, referenced) : null;
            out.add(new Entry(f, change, r, th, sh, refused));
        }
        return new Proposal(o.template(), o.templateVersion(), SpaceOrigin.templateVersion(templateDir), out, tpl);
    }
}
