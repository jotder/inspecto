package com.gamma.entitystore;

import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Resolves an Investigation's pseudonym to the raw id it masks (LA-SUSPICION-FACTORS-INVESTIGATION-1, 2026-10-31). An
 * Investigation masks with its OWN key ({@code mask.key} in its directory, LA-19 D-U6), not the Space key
 * ({@link MaskTokens#spaceKey}), so a read keyed by entity (a Risk Score's {@code GET /risk-scores/{model}/{key}}) cannot
 * resolve the alias an Investigation canvas holds. Link Analysis owns the Investigation and its read gate and lives in a
 * module the readers cannot see, so it provides this ({@link ServiceLoader}); with no provider on the classpath no
 * Investigation alias resolves.
 */
public interface InvestigationAliases {

    /**
     * The raw id behind {@code alias} as Investigation {@code investigationId} masks it, or null when that Investigation did
     * not issue it. Runs the Investigation READ gate first and throws its refusal ({@code ApiException}: 404 absent or not
     * readable, 422 an unsafe id), so a caller who cannot read the Investigation learns nothing about the alias.
     */
    String resolve(ApiContext api, HttpExchange ex, String investigationId, String alias) throws IOException;

    static Optional<InvestigationAliases> provider() {
        return com.gamma.module.EntitledServices.stream(InvestigationAliases.class).findFirst();
    }
}
