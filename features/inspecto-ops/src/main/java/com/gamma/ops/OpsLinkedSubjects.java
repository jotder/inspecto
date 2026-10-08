package com.gamma.ops;

import com.gamma.control.AnnotationTargets;
import com.gamma.control.HostContext;
import com.gamma.control.LinkedSubjectProvider;
import com.gamma.objects.ObjectAccess;
import com.gamma.workflow.ObjectType;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The operational objects' contribution to {@link LinkedSubjectProvider} (MODULE-REORG-P7): an Action Request is
 * raised from an Incident or a Case. Both resolve through the object engine's summary, and are visible exactly when
 * {@code AnnotationTargets.objectVisibleTo} says so. Only the Incident provider opens a subject (the
 * {@code invoke-api} consequence raises one when the rule has none open); the Case provider keeps the default.
 */
public abstract class OpsLinkedSubjects implements LinkedSubjectProvider {

    private final ObjectType type;

    OpsLinkedSubjects(ObjectType type) {
        this.type = type;
    }

    @Override
    public String kind() {
        return type.name().toLowerCase(Locale.ROOT);
    }

    static Optional<ObjectAccess> objects(ApiContext api) {
        return api instanceof HostContext host ? host.service().objects() : Optional.empty();
    }

    @Override
    public boolean available(ApiContext api) {
        return objects(api).isPresent();
    }

    @Override
    public Optional<Linked> resolve(ApiContext api, String id) {
        return objects(api).flatMap(o -> o.summary(id))
                .filter(s -> kind().equals(String.valueOf(s.get("kind")).toLowerCase(Locale.ROOT)))
                .map(s -> new Linked(id, kind(), String.valueOf(s.getOrDefault("title", id))));
    }

    @Override
    public boolean visibleTo(ApiContext api, HttpExchange ex, String id) {
        Map<String, Object> o = objects(api).flatMap(objects -> objects.summary(id)).orElse(null);
        return o != null && AnnotationTargets.objectVisibleTo(ex, o);
    }

    /** {@code incident}: opens one for the origin, or reuses the active one the origin's attribute index names. */
    public static final class Incidents extends OpsLinkedSubjects {
        public Incidents() {
            super(ObjectType.INCIDENT);
        }

        @Override
        public Optional<String> open(ApiContext api, OpenRequest r) {
            ObjectAccess objects = objects(api).orElse(null);
            if (objects == null) return Optional.empty();
            String existing = objects.activeAttributeIndex(ObjectType.INCIDENT, r.origin(), r.reuseAttribute()).get(r.reuseValue());
            return Optional.of(existing != null ? existing
                    : objects.open(ObjectType.INCIDENT, r.title(), r.detail(), r.severity(), r.origin(), r.attributes()));
        }
    }

    /** {@code case}: resolved and visibility-checked, never opened by a consequence. */
    public static final class Cases extends OpsLinkedSubjects {
        public Cases() {
            super(ObjectType.CASE);
        }
    }
}
