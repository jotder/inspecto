import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { spaceScopedUrl } from './space-scope';
import { SpacesService } from './spaces.service';

/**
 * Scopes every feature API call to the active space by rewriting `/api/<path>` →
 * `/api/spaces/<id>/<path>`. The backend's request seam strips the `/spaces/{id}` prefix, binds the
 * request to that space, and matches the unchanged route remainder — so every existing feature
 * service stays oblivious to multi-space.
 *
 * The rule itself lives in {@link spaceScopedUrl}, because **`EventSource` never passes through an
 * `HttpInterceptorFn`** and each stream URL has to apply the same rule by hand.
 */
export const spaceInterceptor: HttpInterceptorFn = (req, next) => {
    const url = spaceScopedUrl(req.url, inject(SpacesService).currentSpaceId());
    return next(url === req.url ? req : req.clone({ url }));
};
