package com.gamma.la.api;

import com.gamma.entitystore.InvestigationAliases;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;

/**
 * {@link InvestigationAliases} for Link Analysis (LA-SUSPICION-FACTORS-INVESTIGATION-1): opens the Investigation through
 * its READ gate ({@link InvestigationRoutes#openForRead}: members and linked-Case members, R3, the Advanced PDP) and
 * resolves the alias with the masking in force for it ({@link EntityMasking#reveal}) — only a pseudonym this
 * Investigation issued resolves.
 */
public final class InvestigationAliasResolver implements InvestigationAliases {

    @Override
    public String resolve(ApiContext api, HttpExchange ex, String investigationId, String alias) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, investigationId);
        return EntityMasking.of(inv, List.of()).reveal(alias);
    }
}
