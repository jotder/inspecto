package com.gamma.control;

import com.gamma.objects.ObjectAccess;
import com.gamma.spi.http.ApiContext;
import com.gamma.workflow.ObjectType;
import com.sun.net.httpserver.HttpExchange;

import java.util.Locale;
import java.util.Optional;

/**
 * The processor's tests have no operational-objects module on the classpath (they run over
 * {@link FakeObjectEngineProvider}), so this stands in for {@code inspecto-ops}'s {@code OpsLinkedSubjects}: the same
 * resolve / visibility / open-or-reuse semantics over whatever {@code ObjectAccess} the Space holds. Registered in
 * {@code META-INF/services/com.gamma.control.LinkedSubjectProvider} of the test resources.
 */
public abstract class FakeLinkedSubjects implements LinkedSubjectProvider {
    private final ObjectType type;

    FakeLinkedSubjects(ObjectType type) {
        this.type = type;
    }

    @Override public String kind() { return type.name().toLowerCase(Locale.ROOT); }

    private static Optional<ObjectAccess> objects(ApiContext api) {
        return HostContext.of(api).service().objects();
    }

    @Override public boolean available(ApiContext api) { return objects(api).isPresent(); }

    @Override public Optional<Linked> resolve(ApiContext api, String id) {
        return objects(api).flatMap(o -> o.summary(id))
                .filter(s -> kind().equals(String.valueOf(s.get("kind")).toLowerCase(Locale.ROOT)))
                .map(s -> new Linked(id, kind(), String.valueOf(s.getOrDefault("title", id))));
    }

    @Override public boolean visibleTo(ApiContext api, HttpExchange ex, String id) {
        return objects(api).flatMap(o -> o.summary(id)).map(s -> AnnotationTargets.objectVisibleTo(ex, s)).orElse(false);
    }

    public static final class Incidents extends FakeLinkedSubjects {
        public Incidents() { super(ObjectType.INCIDENT); }

        @Override public Optional<String> open(ApiContext api, OpenRequest r) {
            ObjectAccess o = objects(api).orElse(null);
            if (o == null) return Optional.empty();
            String existing = o.activeAttributeIndex(ObjectType.INCIDENT, r.origin(), r.reuseAttribute()).get(r.reuseValue());
            return Optional.of(existing != null ? existing
                    : o.open(ObjectType.INCIDENT, r.title(), r.detail(), r.severity(), r.origin(), r.attributes()));
        }
    }

    public static final class Cases extends FakeLinkedSubjects {
        public Cases() { super(ObjectType.CASE); }
    }
}
