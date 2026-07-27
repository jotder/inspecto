package com.gamma.control;

import com.gamma.ops.AnnotationKinds;
import com.gamma.ops.tag.TagAssignment;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A widget's {@code tags} array as a <b>projection</b> of the D7 tag assignment store (BACKLOG §6 widget
 * row, operator call (c), 2026-07-27) — the same move phase 2 made for an Incident's {@code attributes.tags}
 * CSV. Before this, a widget carried tags <em>inside its own config</em> with no vocabulary check, so
 * adopting the shared {@code TagAssignmentDialog} on the widget card would have put two unrelated tag
 * systems on one card: the split-brain phase 2 existed to end.
 *
 * <p><b>Edges are the truth; the array is derived.</b> Assignments are created and removed through
 * {@code /tags/assignments/widget/{id}}, and every path that can change them re-derives the array:
 * {@link #project} on a component write, {@link #reproject} after an assignment or a vocabulary-level
 * rename/delete, and {@link #backfill} once per Space for tags that exist only in a config array.
 *
 * <p><b>This lives at the edge, not in {@code ObjectService}</b>, for the reason D6's findings gate does:
 * the widget lives in the Space's {@link ComponentStore}, which the engine deliberately knows nothing
 * about. {@code ObjectService.renameTag}/{@code deleteTag} therefore still move only the object CSVs, and
 * {@link TagRoutes} composes the component half around them.
 */
final class WidgetTags {

    private static final Logger log = LoggerFactory.getLogger(WidgetTags.class);

    /** The one kind whose tags are config-embedded, so the one kind needing a projection. The
     *  {@link AnnotationKinds} target kind and the {@link ComponentStore} type are the same string. */
    static final String KIND = "widget";

    /** The content key holding the projection. */
    private static final String TAGS = "tags";

    private WidgetTags() {}

    /**
     * Re-derive {@code content.tags} from the assignment store before a widget component is written.
     *
     * <p>On <b>create</b> the submitted array is <em>adopted</em> first — a widget arriving from a bundle,
     * a Space seed or a template carries its tags in its config, and dropping them would be silent data
     * loss (the same reason {@code ObjectService.adoptTags} exists). Unknown tag names are registered via
     * {@code ensureTag}, because an assignment to an unregistered tag is a 404 at the route and would
     * otherwise be dropped on the floor.
     *
     * <p>On <b>update</b> the submitted array is <b>ignored</b> and overwritten from the edges. That is
     * deliberate: a stale client (or a re-save from a form that still remembers the old chips) must not be
     * able to resurrect a tag the operator removed through the dialog. Removing a tag is the dialog's job.
     */
    static void project(ApiContext api, String type, String id, Map<String, Object> content,
                        boolean create, Consumer<String> ensureTag) {
        if (!KIND.equals(type)) return;
        if (create) {
            for (String tag : names(content.get(TAGS))) {
                ensureTag.accept(tag);
                api.service().tagAssignments().add(TagAssignment.of(tag, KIND, id, "migration"));
            }
        }
        List<String> edges = api.service().tagAssignments().tagsOf(KIND, id);
        if (edges.isEmpty()) content.remove(TAGS);   // an empty array is noise in the persisted TOON
        else content.put(TAGS, edges);
    }

    /**
     * Rewrite the {@code tags} array of each named widget from the assignment store. Called after anything
     * that changes edges without going through a component write — an assignment, an unassignment, or a
     * vocabulary-level rename/delete.
     *
     * <p>A widget whose array already matches is <b>not</b> written: every {@code ComponentStore.write}
     * archives a version, so writing unconditionally would fill a widget's version history with tag
     * churn. Ids with no component behind them are skipped — assignments are not cascade-deleted, so a
     * stale edge is normal.
     *
     * @return how many widget components were actually rewritten
     */
    static int reproject(ApiContext api, Collection<String> widgetIds) {
        if (widgetIds.isEmpty() || api.writeRoot() == null) return 0;
        ComponentStore store = new ComponentStore(api.writeRoot().resolve("registry"));
        int done = 0;
        for (String id : widgetIds) {
            ComponentRegistry.Component c;
            try {
                c = store.get(KIND, id).orElse(null);
            } catch (IllegalArgumentException notReadable) {
                continue;
            }
            if (c == null) continue;
            List<String> edges = api.service().tagAssignments().tagsOf(KIND, id);
            if (edges.equals(names(c.content().get(TAGS)))) continue;   // no version for a no-op
            Map<String, Object> content = new LinkedHashMap<>(c.content());
            if (edges.isEmpty()) content.remove(TAGS);
            else content.put(TAGS, edges);
            try {
                store.write(KIND, id, content);
                done++;
            } catch (java.io.IOException io) {
                // The edge — the truth — is already stored, so the tag operation succeeded. Refusing it
                // because one widget file could not be rewritten would be the worse outcome; the next
                // write or the per-Space backfill re-derives the array.
                log.warn("[WIDGET-TAGS] '{}' tags could not be re-projected: {}", id, io.getMessage());
            }
        }
        return done;
    }

    /** The widget ids currently carrying {@code tag} — the component half of a vocabulary change. */
    static List<String> targetsOf(ApiContext api, String tag) {
        return api.service().tagAssignments().forTag(tag).stream()
                .filter(a -> KIND.equals(a.targetKind()))
                .map(TagAssignment::targetId)
                .distinct()
                .toList();
    }

    /**
     * Spaces whose widget tags have already been adopted. Weakly keyed on the Space's service so a closed
     * Space is not pinned in memory.
     *
     * <p>⚠ <b>The migration cannot run at route-registration time</b>, which is where it belongs by
     * symmetry with the object-CSV backfill in {@code CollectorService}: {@code register} runs before any
     * Space is hosted, so {@code api.service()} throws {@code IllegalState No spaces are hosted} and the
     * whole {@code ControlApi} fails to construct. It therefore runs lazily on the first tag read or write
     * in a Space — the same shape the offline mock uses.
     */
    private static final Map<Object, Boolean> MIGRATED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Run {@link #backfill} once per hosted Space, on the first tag read or write it sees. */
    static void backfillOnce(ApiContext api, Consumer<String> ensureTag) {
        Object space;
        try {
            space = api.service();
        } catch (RuntimeException noSpace) {
            return;   // nothing hosted yet — the next tag request in a live Space runs it
        }
        if (MIGRATED.putIfAbsent(space, Boolean.TRUE) != null) return;
        try {
            backfill(api, ensureTag);
        } catch (RuntimeException failed) {
            log.warn("Widget tag backfill failed, tags stay config-only for now: {}", failed.getMessage());
        }
    }

    /**
     * One-time migration, run once per Space: adopt every tag that exists only in a widget's config array
     * into the assignment store, registering unknown names first, then re-derive every array so the two
     * cannot disagree.
     *
     * <p>Idempotent twice over — a tag already in {@code tagsOf} is skipped, and the store's composite key
     * makes {@code add} idempotent anyway — so a second boot adopts nothing. A no-op when writes are
     * disabled: with no write root there are no components to migrate.
     *
     * @return how many assignments were created
     */
    static int backfill(ApiContext api, Consumer<String> ensureTag) {
        if (api.writeRoot() == null) return 0;
        List<ComponentRegistry.Component> widgets;
        try {
            widgets = new ComponentStore(api.writeRoot().resolve("registry")).list(KIND);
        } catch (RuntimeException unreadable) {
            log.warn("Widget tag backfill skipped — widgets are not readable: {}", unreadable.getMessage());
            return 0;
        }
        List<String> touched = new ArrayList<>();
        int created = 0;
        for (ComponentRegistry.Component w : widgets) {
            List<String> configured = names(w.content().get(TAGS));
            if (configured.isEmpty()) continue;
            List<String> known = api.service().tagAssignments().tagsOf(KIND, w.name());
            for (String tag : configured) {
                if (known.contains(tag)) continue;
                ensureTag.accept(tag);
                api.service().tagAssignments().add(TagAssignment.of(tag, KIND, w.name(), "migration"));
                created++;
            }
            touched.add(w.name());
        }
        if (created > 0) {
            log.info("Adopted {} widget tag assignment(s) from widget config (D7 (c)); re-projected {}",
                    created, reproject(api, touched));
        }
        return created;
    }

    /** A content {@code tags} value as clean names — accepts the {@code LIST} shape and a CSV string. */
    private static List<String> names(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> list) {
            for (Object o : list) add(out, o == null ? null : String.valueOf(o));
        } else if (raw instanceof String csv) {
            for (String s : csv.split(",")) add(out, s);
        }
        return out;
    }

    private static void add(List<String> out, String name) {
        if (name == null) return;
        String clean = name.trim();
        if (!clean.isEmpty() && !out.contains(clean)) out.add(clean);
    }
}
