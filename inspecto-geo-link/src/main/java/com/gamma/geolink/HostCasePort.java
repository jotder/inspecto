package com.gamma.geolink;

import com.gamma.control.AnnotationTargets;
import com.gamma.control.ApiContext;
import com.gamma.control.HostContext;
import com.gamma.la.core.CasePort;
import com.gamma.objects.ObjectAccess;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.Optional;

/**
 * The BRIDGE's implementation of Link Analysis's {@link CasePort} (LA separation D-1 step 5b/6): the host's
 * {@link ObjectAccess} seam ({@code CollectorService.objects()}, filled by the optional ops module) and
 * {@link AnnotationTargets#objectVisibleTo}. Registered through {@code META-INF/services/com.gamma.la.core.CasePort}.
 */
public final class HostCasePort implements CasePort {

    @Override
    public boolean available(ApiContext api) {
        return HostContext.of(api).service().objects().isPresent();
    }

    @Override
    public Optional<Map<String, Object>> summary(ApiContext api, String ref) {
        Optional<ObjectAccess> objects = HostContext.of(api).service().objects();
        return objects.isPresent() ? objects.get().summary(ref) : Optional.empty();
    }

    @Override
    public boolean visibleTo(HttpExchange ex, Map<String, Object> summary) {
        return AnnotationTargets.objectVisibleTo(ex, summary);
    }
}
