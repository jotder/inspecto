package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.ops.ObjectService;
import com.gamma.ops.tag.Tag;
import com.gamma.ops.tag.TagRule;
import com.gamma.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Tag registry + Tag Rules ({@code /tags*}; GLOSSARY §9) — the backend for the Incidents/Case Manager
 * mail view's user-created tags: a durable tag registry, and Gmail-filter <em>Tag Rules</em> (saved
 * searches that auto-tag new objects via {@link ObjectService#open} and bulk-apply to existing matches).
 *
 * <p>Writes follow the {@link WriteGates} fail-closed chain and persist one {@code <name>_tag.toon} /
 * {@code <name>_tagrule.toon} per entity under the write root (the {@link ConnectionRoutes} durability
 * pattern), which {@code ServiceBootstrap} rescans at the next boot — a tag created at runtime survives
 * a restart. Names must therefore be jail-safe filenames ({@link WriteGates#safeName}).
 */
final class TagRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(TagRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.get("/tags", (e, m) -> {
            // D7 (c): adopt tags that exist only inside a widget's own config array. Lazy and once per
            // Space — see WidgetTags.backfillOnce for why this cannot happen here at registration time.
            WidgetTags.backfillOnce(api, name -> ensureTag(api, name));
            return api.service().objects().tags().stream().map(Tag::toMap).toList();
        });
        api.post("/tags", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> createTag(api, api.body(e))));
        api.post("/tags/([^/]+)/rename", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> renameTag(api, ApiContext.name(m), api.body(e))));
        api.delete("/tags/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> deleteTag(api, ApiContext.name(m))));
        api.get("/tags/rules", (e, m) -> api.service().objects().tagRules().stream().map(TagRule::toMap).toList());
        api.post("/tags/rules", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> saveTagRule(api, api.body(e))));
        api.delete("/tags/rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> deleteTagRule(api, ApiContext.name(m))));
        // Bulk apply mutates OBJECTS (an operational action, like transition/assign) — not config; ungated.
        api.post("/tags/rules/([^/]+)/apply", (e, m) -> applyTagRule(api, ApiContext.name(m)));

        // ── cross-entity assignments (BACKLOG D7) ────────────────────────────────────────────────
        // Gated per TARGET via AnnotationTargets, not by a capability: labelling a thing you can already
        // see is a collaboration act, and a capability gate would make "can tag" independent of "can see"
        // — which is exactly how a tag would turn into an access grant. See AnnotationTargets.
        api.get("/tags/([^/]+)/targets", (e, m) -> targetsOf(api, e, ApiContext.name(m)));
        api.get("/tags/assignments/([^/]+)/([^/]+)", (e, m) ->
                tagsOn(api, e, ApiContext.name(m), ApiContext.param(m, 2)));
        api.post("/tags/assignments/([^/]+)/([^/]+)", (e, m) ->
                assign(api, e, ApiContext.name(m), ApiContext.param(m, 2), api.body(e)));
        api.delete("/tags/assignments/([^/]+)/([^/]+)/([^/]+)", (e, m) ->
                unassign(api, e, ApiContext.name(m), ApiContext.param(m, 2), ApiContext.param(m, 3)));
    }

    /**
     * Register {@code name} as a tag if it is not one already, persisting it exactly as {@code POST /tags}
     * would. Used by the widget projection: a widget's config tags are free text, while an assignment to an
     * unregistered tag is a 404, so a migration that did not register first would silently drop them.
     *
     * <p>Failing to persist is logged, not thrown: the in-memory registration still stands for this boot,
     * and refusing to tag a widget because a 40-byte file could not be written would be the worse outcome.
     */
    static void ensureTag(ApiContext api, String name) {
        if (api.service().objects().tag(name).isPresent()) return;
        Tag tag;
        try {
            tag = new Tag(name, System.currentTimeMillis());
        } catch (IllegalArgumentException unusable) {
            log.warn("[TAG-ADOPT] '{}' is not a usable tag name, skipped: {}", name, unusable.getMessage());
            return;
        }
        api.service().objects().registerTag(tag);
        if (api.writeRoot() == null) return;
        try {
            persist(api, tagFile(api, tag.name(), "_tag.toon", "tag name"),
                    Map.of("tag", tag.toMap()), ".tag-");
        } catch (IOException | ApiException io) {
            log.warn("[TAG-ADOPT] registered '{}' in memory but could not persist it: {}", tag.name(), io);
        }
    }

    /**
     * {@code GET /tags/{name}/targets} — everything carrying this tag, across kinds. The point of D7.
     *
     * <p>The result is filtered to what the caller may actually see. Two consequences worth keeping:
     * a tag cannot be used to discover the existence of something otherwise hidden, and two users can
     * legitimately get different counts for the same tag.
     */
    private Object targetsOf(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String name) {
        WidgetTags.backfillOnce(api, n -> ensureTag(api, n));   // a widget must be findable here too
        return AnnotationTargets.mapErrors(() -> api.service().tagAssignments().forTag(name).stream()
                .filter(a -> AnnotationTargets.visible(api, ex, a.targetKind(), a.targetId()))
                .map(com.gamma.ops.tag.TagAssignment::toMap)
                .toList());
    }

    /** {@code GET /tags/assignments/{targetKind}/{targetId}} — the tags on one thing, alphabetical. */
    private Object tagsOn(ApiContext api, com.sun.net.httpserver.HttpExchange ex,
                          String targetKind, String targetId) {
        WidgetTags.backfillOnce(api, n -> ensureTag(api, n));
        return AnnotationTargets.mapErrors(() -> {
            requireVisibleTarget(api, ex, targetKind, targetId);
            return Map.of("targetKind", targetKind, "targetId", targetId,
                    "tags", api.service().tagAssignments().tagsOf(targetKind, targetId));
        });
    }

    /**
     * {@code POST /tags/assignments/{targetKind}/{targetId}} — apply a tag; body {@code {tag, actor?}}.
     * Idempotent, so the UI may apply optimistically without checking first. The tag must already exist
     * in the registry (404 otherwise) — silently creating one on a typo is how a tag vocabulary rots.
     */
    private Object assign(ApiContext api, com.sun.net.httpserver.HttpExchange ex,
                          String targetKind, String targetId, Map<String, Object> body) {
        String tag = ApiContext.str(body, "tag");
        if (tag == null) throw new ApiException(400, "body must include 'tag'");
        return AnnotationTargets.mapErrors(() -> {
            requireVisibleTarget(api, ex, targetKind, targetId);
            if (api.service().objects().tag(tag).isEmpty())
                throw new ApiException(404, "no tag named '" + tag + "' — create it via POST /tags first");
            String actor = ApiContext.str(body, "actor");
            // An object's `tags` attribute is a projection of the assignment store (D7 phase 2), so object
            // targets go through ObjectService — writing the store directly would leave the CSV stale and
            // recreate exactly the split-brain phase 2 exists to remove.
            if (com.gamma.ops.AnnotationKinds.OBJECT.equals(targetKind)) {
                api.service().objects().applyTag(targetId, tag, actor);
            } else {
                api.service().tagAssignments()
                        .add(com.gamma.ops.tag.TagAssignment.of(tag, targetKind, targetId, actor));
                // A widget's `tags` array is the same kind of projection (D7 (c)) — the chips on its
                // gallery card are drawn from the config, so the edge alone would leave them stale.
                WidgetTags.reproject(api, List.of(targetId));
            }
            // Report the STORED edge, not the request: on a re-apply the original actor and timestamp win,
            // and echoing this caller's would misreport who first applied the tag.
            return api.service().tagAssignments().forTag(tag).stream()
                    .filter(a -> a.targets(targetKind, targetId))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(500, "tag assignment did not persist"))
                    .toMap();
        });
    }

    /** {@code DELETE /tags/assignments/{targetKind}/{targetId}/{tag}} — remove one label; idempotent. */
    private Object unassign(ApiContext api, com.sun.net.httpserver.HttpExchange ex,
                            String targetKind, String targetId, String tag) {
        return AnnotationTargets.mapErrors(() -> {
            requireVisibleTarget(api, ex, targetKind, targetId);
            boolean removed;
            if (com.gamma.ops.AnnotationKinds.OBJECT.equals(targetKind)) {
                removed = api.service().tagAssignments().tagsOf(targetKind, targetId).contains(tag);
                api.service().objects().removeTag(targetId, tag);   // also re-projects the CSV
            } else {
                removed = api.service().tagAssignments().remove(tag, targetKind, targetId);
                WidgetTags.reproject(api, List.of(targetId));   // drop the chip too, not just the edge
            }
            return Map.of("tag", tag, "targetKind", targetKind, "targetId", targetId, "removed", removed);
        });
    }

    /** 404 when the target is absent or invisible — existence-hiding, same as the notes surface. */
    private static void requireVisibleTarget(ApiContext api, com.sun.net.httpserver.HttpExchange ex,
                                             String targetKind, String targetId) {
        if (AnnotationTargets.gate(api, ex, targetKind, targetId) == null)
            throw new ApiException(404, "no " + targetKind + " '" + targetId + "'");
    }

    /** {@code POST /tags} — create a tag; body {@code {name}}. Duplicate → 409; persisted as {@code <name>_tag.toon}. */
    private Object createTag(ApiContext api, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "tag write");
        Tag tag;
        try {
            tag = Tag.fromMap(body);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
        Path file = tagFile(api, tag.name(), "_tag.toon", "tag name");
        WriteGates.conflictIf(api.service().objects().tag(tag.name()).isPresent(),
                "tag '" + tag.name() + "' already exists");
        persist(api, file, Map.of("tag", tag.toMap()), ".tag-");
        return api.service().objects().registerTag(tag).toMap();
    }

    /**
     * {@code POST /tags/{name}/rename} — rename a tag everywhere; body {@code {to}}. The operation the
     * central assignment store exists for: the registry entry, every assignment edge, every affected
     * object's {@code tags} projection and any Tag Rule applying the tag all move together.
     *
     * <p>Renaming onto an existing tag <b>merges</b> the two — the composite assignment key makes that
     * correct rather than a conflict — and the source tag stops existing.
     */
    private Object renameTag(ApiContext api, String from, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "tag write");
        String to = ApiContext.str(body, "to");
        if (to == null) throw new ApiException(400, "body must include 'to'");
        if (api.service().objects().tag(from).isEmpty())
            throw new ApiException(404, "no tag named '" + from + "'");

        // Persist the destination first (the createTag order): a failed write must not leave a renamed
        // in-memory vocabulary with nothing on disk to reload at the next boot.
        Path target = tagFile(api, to, "_tag.toon", "tag name");
        // Collect the widget targets BEFORE the rename — afterwards the old name has no edges left.
        // ObjectService moves the registry entry, the edges and the OBJECT CSVs; the component-side
        // projections are ours, because the engine has no ComponentStore (see WidgetTags).
        List<String> widgets = WidgetTags.targetsOf(api, from);
        ObjectService.TagVocabularyChange changed;
        try {
            persist(api, target, Map.of("tag", Map.of("name", to.trim(),
                    "createdAt", System.currentTimeMillis())), ".tag-");
            changed = api.service().objects().renameTag(from, to);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
        // A rule that followed the rename now disagrees with its own file until rewritten.
        for (String rule : changed.rules())
            api.service().objects().tagRule(rule).ifPresent(r -> {
                try {
                    persist(api, tagFile(api, rule, "_tagrule.toon", "tag rule name"),
                            Map.of("tag_rule", r.toMap()), ".tagrule-");
                } catch (IOException io) {
                    throw new ApiException(500, "renamed tag rule '" + rule + "' could not be persisted: " + io);
                }
            });
        int reprojected = WidgetTags.reproject(api, widgets);
        boolean fileRemoved = Files.deleteIfExists(tagFile(api, from, "_tag.toon", "tag name"));
        log.info("[TAG-RENAME] '{}' -> '{}': {} assignment(s), {} object(s) + {} widget(s) re-projected, rules {}",
                from, to, changed.assignments(), changed.objects(), reprojected, changed.rules());
        return Map.of("renamed", from, "to", to.trim(), "assignments", changed.assignments(),
                "objects", changed.objects(), "widgets", reprojected,
                "rules", changed.rules(), "fileRemoved", fileRemoved);
    }

    /**
     * {@code DELETE /tags/{name}} — retire a tag: the registry entry, its file, and every assignment it
     * has, re-projecting each affected object. 409 while a Tag Rule still applies it, because the rule
     * would immediately re-create it.
     */
    private Object deleteTag(ApiContext api, String name) throws IOException {
        WriteGates.requireWriteRoot(api, "tag write");
        List<String> widgets = WidgetTags.targetsOf(api, name);   // before the edges are removed
        ObjectService.TagVocabularyChange changed;
        try {
            changed = api.service().objects().deleteTag(name);
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalStateException conflict) {
            throw new ApiException(409, conflict.getMessage());
        }
        int reprojected = WidgetTags.reproject(api, widgets);
        boolean fileRemoved = Files.deleteIfExists(tagFile(api, name, "_tag.toon", "tag name"));
        return Map.of("deleted", name, "assignments", changed.assignments(),
                "objects", changed.objects(), "widgets", reprojected, "fileRemoved", fileRemoved);
    }

    /**
     * {@code POST /tags/rules} — save (create or replace) a Tag Rule; body {@code {name, tag,
     * filter:{type?,q?,status?,priority?,severity?,category?}}}. At least one criterion is required
     * (422 — an unconstrained rule would tag everything); saving implicitly registers the rule's tag.
     */
    private Object saveTagRule(ApiContext api, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "tag rule write");
        TagRule rule;
        try {
            rule = TagRule.fromMap(body);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
        Path file = tagFile(api, rule.name(), "_tagrule.toon", "tag rule name");
        persist(api, file, Map.of("tag_rule", rule.toMap()), ".tagrule-");
        return api.service().objects().registerTagRule(rule).toMap();
    }

    /** {@code DELETE /tags/rules/{name}} — remove a rule (registry + its persisted file); 404 if unknown. */
    private Object deleteTagRule(ApiContext api, String name) throws IOException {
        WriteGates.requireWriteRoot(api, "tag rule write");
        if (api.service().objects().tagRule(name).isEmpty())
            throw new ApiException(404, "no tag rule named '" + name + "'");
        boolean fileRemoved = Files.deleteIfExists(tagFile(api, name, "_tagrule.toon", "tag rule name"));
        api.service().objects().removeTagRule(name);
        return Map.of("deleted", name, "fileRemoved", fileRemoved);
    }

    /**
     * {@code POST /tags/rules/{name}/apply} — bulk-apply the rule to every existing match (Gmail's
     * "also apply to existing"); idempotent. Returns {@code {matched, updated}}; unknown rule → 404.
     */
    private Object applyTagRule(ApiContext api, String name) {
        try {
            ObjectService.TagRuleApplication result = api.service().objects().applyTagRule(name);
            return Map.of("matched", result.matched(), "updated", result.updated());
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    /** The jailed {@code <name><suffix>} path under the write root; 422 on an unsafe name, 403 on escape. */
    private static Path tagFile(ApiContext api, String name, String suffix, String what) {
        String safe = WriteGates.safeName(name, what);
        Path root = api.writeRoot();
        return WriteGates.jail(root, root.resolve(safe + suffix), "resolved path");
    }

    /** Encode {@code doc} as TOON and write it atomically under the write root. */
    private static void persist(ApiContext api, Path target, Map<String, Object> doc, String tmpPrefix) throws IOException {
        byte[] bytes = ConfigCodec.toToon(doc).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, tmpPrefix);
        log.info("[TAG-WRITE] wrote {} ({} bytes)",
                api.writeRoot().relativize(target).toString().replace('\\', '/'), bytes.length);
    }
}
