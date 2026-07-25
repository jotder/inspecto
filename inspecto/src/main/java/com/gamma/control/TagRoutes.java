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
        api.get("/tags", (e, m) -> api.service().objects().tags().stream().map(Tag::toMap).toList());
        api.post("/tags", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> createTag(api, api.body(e))));
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
     * {@code GET /tags/{name}/targets} — everything carrying this tag, across kinds. The point of D7.
     *
     * <p>The result is filtered to what the caller may actually see. Two consequences worth keeping:
     * a tag cannot be used to discover the existence of something otherwise hidden, and two users can
     * legitimately get different counts for the same tag.
     */
    private Object targetsOf(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String name) {
        return AnnotationTargets.mapErrors(() -> api.service().tagAssignments().forTag(name).stream()
                .filter(a -> AnnotationTargets.visible(api, ex, a.targetKind(), a.targetId()))
                .map(com.gamma.ops.tag.TagAssignment::toMap)
                .toList());
    }

    /** {@code GET /tags/assignments/{targetKind}/{targetId}} — the tags on one thing, alphabetical. */
    private Object tagsOn(ApiContext api, com.sun.net.httpserver.HttpExchange ex,
                          String targetKind, String targetId) {
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
            return api.service().tagAssignments()
                    .add(com.gamma.ops.tag.TagAssignment.of(tag, targetKind, targetId,
                            ApiContext.str(body, "actor")))
                    .toMap();
        });
    }

    /** {@code DELETE /tags/assignments/{targetKind}/{targetId}/{tag}} — remove one label; idempotent. */
    private Object unassign(ApiContext api, com.sun.net.httpserver.HttpExchange ex,
                            String targetKind, String targetId, String tag) {
        return AnnotationTargets.mapErrors(() -> {
            requireVisibleTarget(api, ex, targetKind, targetId);
            boolean removed = api.service().tagAssignments().remove(tag, targetKind, targetId);
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
